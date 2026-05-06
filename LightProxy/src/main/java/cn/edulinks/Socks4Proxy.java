package cn.edulinks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * SOCKS4 代理处理器
 *
 * RFC 1928 的简化版，支持:
 *   - CONNECT 命令 (0x01)
 *   - 仅 IPv4 地址 (不支持域名)
 *   - 用户名标识 (可选)
 *
 * 与 SOCKS5 的主要区别:
 *   - 无版本协商阶段
 *   - 仅支持 IPv4 地址
 *   - 认证基于用户名 (较弱)
 *   - 不支持 UDP ASSOCIATE
 */
public class Socks4Proxy implements Runnable {

    private final int port;
    private final ProxyConfig config;
    private java.net.ServerSocket serverSocket;
    private volatile boolean running = true;

    // SOCKS4 常量
    private static final byte SOCKS4_VERSION = 0x04;
    private static final byte CMD_CONNECT = 0x01;
    private static final byte CMD_BIND = 0x02;

    // 响应代码
    private static final byte REP_REQUEST_GRANTED = 0x5A;
    private static final byte REP_REQUEST_REJECTED = 0x5B;
    private static final byte REP_REQUEST_REJECTED_IDENTD = 0x5C;
    private static final byte REP_REQUEST_REJECTED_USERID = 0x5D;

    public Socks4Proxy(int port, ProxyConfig config) {
        this.port = port;
        this.config = config;
    }

    @Override
    public void run() {
        try {
            serverSocket = new java.net.ServerSocket(port);
            System.out.println("[SOCKS4] Listening on port " + port);
            running = true;
        } catch (IOException e) {
            System.err.println("[SOCKS4] Failed to listen on port " + port + ": " + e.getMessage());
            running = false;
            return;
        }

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                LightProxy.getThreadPool().submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("[SOCKS4] Accept error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 停止监听
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * 处理单个 SOCKS4 客户端连接
     *
     * SOCKS4 协议格式 (CONNECT):
     *   1 字节: 0x04 (VERSION)
     *   1 字节: 0x01 (CMD - CONNECT) 或 0x02 (CMD - BIND)
     *   2 字节: 目标端口 (PORT)
     *   4 字节: 目标 IP 地址 (IP ADDR)
     *   变长:  用户ID (NULL TERMINATED STRING)
     *   
     * 响应格式:
     *   1 字节: 0x00 (NULL)
     *   1 字节: 响应码 (0x5A=SUCCESS, 0x5B=REJECT, etc.)
     *   2 字节: 绑定端口 (BND.PORT) - 通常与请求相同
     *   4 字节: 绑定地址 (BND.ADDR) - 通常与请求相同
     */
    private void handleClient(Socket clientSocket) {
        try {
            clientSocket.setSoTimeout(config.getSocketTimeout());
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // 读取 SOCKS4 请求头 (固定 8 字节 + 可变长度的 userid)
            byte[] header = new byte[8];
            if (readExact(in, header) == null) {
                sendReply(out, REP_REQUEST_REJECTED, 0, new byte[]{0, 0, 0, 0});
                return;
            }

            byte version = header[0];
            byte cmd = header[1];
            int port = ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
            byte[] ipBytes = new byte[]{header[4], header[5], header[6], header[7]};

            if (version != SOCKS4_VERSION) {
                System.err.println("[SOCKS4] Unsupported version: " + version);
                sendReply(out, REP_REQUEST_REJECTED, 0, new byte[]{0, 0, 0, 0});
                return;
            }

            // 读取 NULL 结尾的 userid
            StringBuilder userIdBuilder = new StringBuilder();
            int ch;
            while ((ch = in.read()) != -1 && ch != 0) {
                userIdBuilder.append((char) ch);
            }
            String userId = userIdBuilder.toString();

            // SOCKS4a 扩展: 如果 IP 地址的前三个字节都是 0，第四个字节非 0，则表示域名
            // 在这种情况下，后面跟着 NULL 结尾的域名字符串
            String targetHost;
            if (ipBytes[0] == 0 && ipBytes[1] == 0 && ipBytes[2] == 0 && ipBytes[3] != 0) {
                // SOCKS4a: 读取域名
                StringBuilder hostnameBuilder = new StringBuilder();
                while ((ch = in.read()) != -1 && ch != 0) {
                    hostnameBuilder.append((char) ch);
                }
                targetHost = hostnameBuilder.toString();
                
                // 尝试解析域名
                try {
                    InetAddress resolvedAddr = InetAddress.getByName(targetHost);
                    ipBytes = resolvedAddr.getAddress();
                } catch (java.net.UnknownHostException e) {
                    System.err.println("[SOCKS4] Cannot resolve hostname: " + targetHost);
                    sendReply(out, REP_REQUEST_REJECTED, 0, new byte[]{0, 0, 0, 0});
                    return;
                }
            } else {
                // 标准 SOCKS4: 使用 IP 地址
                targetHost = InetAddress.getByAddress(ipBytes).getHostAddress();
            }

            System.out.println("[SOCKS4] CMD=" + cmdString(cmd) + " " + targetHost + ":" + port + " (userid: " + userId + ")");

            // 处理命令
            if (cmd == CMD_CONNECT) {
                handleConnect(clientSocket, out, targetHost, port, ipBytes);
            } else if (cmd == CMD_BIND) {
                // BIND 暂不支持
                sendReply(out, REP_REQUEST_REJECTED, 0, new byte[]{0, 0, 0, 0});
            } else {
                sendReply(out, REP_REQUEST_REJECTED, 0, new byte[]{0, 0, 0, 0});
            }

        } catch (IOException e) {
            System.err.println("[SOCKS4] Client handler error: " + e.getMessage());
            try {
                sendReply(clientSocket.getOutputStream(), REP_REQUEST_REJECTED, 0, new byte[]{0, 0, 0, 0});
            } catch (IOException ignored) {
            }
        } finally {
            LightProxy.closeQuietly(clientSocket);
        }
    }

    /**
     * 处理 CONNECT 命令 — 建立 TCP 隧道
     */
    private void handleConnect(Socket clientSocket, OutputStream out, String host, int port, byte[] ipBytes) {
        Socket targetSocket = null;
        try {
            targetSocket = new Socket();
            targetSocket.connect(new InetSocketAddress(host, port), config.getConnectTimeout());
            targetSocket.setSoTimeout(config.getSocketTimeout());

            // 回复成功 (使用目标服务器的实际连接地址和端口)
            InetSocketAddress boundAddr = (InetSocketAddress) targetSocket.getRemoteSocketAddress();
            byte[] boundIp = boundAddr.getAddress().getAddress();
            int boundPort = boundAddr.getPort();

            sendReply(out, REP_REQUEST_GRANTED, boundPort, boundIp);

            System.out.println("[SOCKS4] Tunnel established: " + host + ":" + port);

            // 双向透传
            pipeBidirectional(clientSocket, targetSocket, host + ":" + port);

        } catch (IOException e) {
            System.err.println("[SOCKS4] Connect to " + host + ":" + port + " failed: " + e.getMessage());
            try {
                sendReply(out, REP_REQUEST_REJECTED, 0, new byte[]{0, 0, 0, 0});
            } catch (IOException ignored) {
            }
        } finally {
            try {
                if (targetSocket != null) targetSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 发送 SOCKS4 响应
     */
    private void sendReply(OutputStream out, byte rep, int port, byte[] ip) throws IOException {
        byte[] response = new byte[8];
        response[0] = 0x00;  // NULL byte
        response[1] = rep;    // Response code
        response[2] = (byte) ((port >> 8) & 0xFF);  // Port high
        response[3] = (byte) (port & 0xFF);         // Port low
        System.arraycopy(ip, 0, response, 4, 4);    // IP address

        out.write(response);
        out.flush();
    }

    /**
     * 精确读取指定字节数
     */
    private byte[] readExact(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read == -1) return null; // 连接关闭
            offset += read;
        }
        return buffer;
    }

    /**
     * 双向管道透传
     */
    private void pipeBidirectional(Socket clientSocket, Socket targetSocket, String label) {
        Thread c2t = new Thread(() -> {
            try {
                LightProxy.pipe(clientSocket.getInputStream(), targetSocket.getOutputStream());
            } catch (IOException e) {
                // 连接断开是正常情况
            } finally {
                LightProxy.closeQuietly(targetSocket);
                LightProxy.closeQuietly(clientSocket);
            }
        }, "Socks4-C2T-" + label);
        c2t.setDaemon(true);

        Thread t2c = new Thread(() -> {
            try {
                LightProxy.pipe(targetSocket.getInputStream(), clientSocket.getOutputStream());
            } catch (IOException e) {
            } finally {
                LightProxy.closeQuietly(targetSocket);
                LightProxy.closeQuietly(clientSocket);
            }
        }, "Socks4-T2C-" + label);
        t2c.setDaemon(true);

        c2t.start();
        t2c.start();

        try {
            c2t.join();
            t2c.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("[SOCKS4] Tunnel closed: " + label);
    }

    private String cmdString(byte cmd) {
        switch (cmd) {
            case CMD_CONNECT: return "CONNECT";
            case CMD_BIND: return "BIND";
            default: return "UNKNOWN(0x" + Integer.toHexString(cmd & 0xFF) + ")";
        }
    }
}
