package cn.edulinks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;

/**
 * SOCKS5 代理处理器
 *
 * RFC 1928 实现，支持:
 *   - 无认证 (METHOD 0x00)
 *   - CONNECT 命令 (0x01)
 *   - IPv4 / 域名 / IPv6 地址类型
 *
 * 暂不支持:
 *   - BIND (0x02) — 反向连接场景，极少使用
 *   - UDP ASSOCIATE (0x03) — 需要额外 UDP 端口
 *   - 用户名密码认证 (METHOD 0x02)
 *
 * SOCKS5 与 HTTP 代理的区别:
 *   HTTP 代理: 客户端发 HTTP 请求 (GET/POST/CONNECT)，代理解析并转发
 *   SOCKS5:     客户端发 SOCKS5 协商→请求，代理建立 TCP 隧道后完全透传
 *               代理不知道也不关心上层协议（HTTP/HTTPS/FTP 均可）
 */
public class Socks5Proxy implements Runnable {

    private final int port;
    private final ProxyConfig config;
    private java.net.ServerSocket serverSocket;
    private volatile boolean running = true;

    // SOCKS5 常量
    private static final byte SOCKS_VERSION = 0x05;
    private static final byte METHOD_NO_AUTH = 0x00;
    private static final byte METHOD_NO_ACCEPTABLE = (byte) 0xFF;

    private static final byte CMD_CONNECT = 0x01;
    private static final byte CMD_BIND = 0x02;
    private static final byte CMD_UDP_ASSOCIATE = 0x03;

    private static final byte ATYP_IPV4 = 0x01;
    private static final byte ATYP_DOMAIN = 0x03;
    private static final byte ATYP_IPV6 = 0x04;

    private static final byte REP_SUCCEEDED = 0x00;
    private static final byte REP_GENERAL_FAILURE = 0x01;
    private static final byte REP_CONN_NOT_ALLOWED = 0x02;
    private static final byte REP_NETWORK_UNREACHABLE = 0x03;
    private static final byte REP_HOST_UNREACHABLE = 0x04;
    private static final byte REP_CONN_REFUSED = 0x05;
    private static final byte REP_CMD_NOT_SUPPORTED = 0x07;
    private static final byte REP_ATYP_NOT_SUPPORTED = 0x08;

    public Socks5Proxy(int port, ProxyConfig config) {
        this.port = port;
        this.config = config;
    }

    @Override
    public void run() {
        try {
            serverSocket = new java.net.ServerSocket(port);
            System.out.println("[SOCKS5] Listening on port " + port);
            running = true;
        } catch (IOException e) {
            System.err.println("[SOCKS5] Failed to listen on port " + port + ": " + e.getMessage());
            running = false;
            return;
        }

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                LightProxy.getThreadPool().submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (running) {
                    System.err.println("[SOCKS5] Accept error: " + e.getMessage());
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
     * 处理单个 SOCKS5 客户端连接
     *
     * 协议流程:
     *   1. 版本协商 (Greeting)
     *   2. 请求处理 (Request)
     *   3. 数据透传 (Relay)
     */
    private void handleClient(final Socket clientSocket) {
        try {
            clientSocket.setSoTimeout(config.getSocketTimeout());
            InputStream in = clientSocket.getInputStream();
            OutputStream out = clientSocket.getOutputStream();

            // --- 步骤 1: 版本协商 ---
            byte[] greeting = readExact(in, 2);
            if (greeting == null) return;

            byte ver = greeting[0];
            byte nMethods = greeting[1];

            if (ver != SOCKS_VERSION) {
                System.err.println("[SOCKS5] Unsupported version: " + ver);
                clientSocket.close();
                return;
            }

            // 读取客户端支持的方法
            readExact(in, nMethods); // 读取但不关心具体方法，我们只支持无认证

            // 回复: VER=0x05, METHOD=0x00 (无认证)
            out.write(new byte[]{SOCKS_VERSION, METHOD_NO_AUTH});
            out.flush();

            // --- 步骤 2: 读取请求 ---
            byte[] request = readExact(in, 4); // VER, CMD, RSV, ATYP
            if (request == null) return;

            byte cmd = request[1];
            byte atyp = request[3];

            // 解析目标地址
            String targetHost;
            int targetPort;
            try {
                InetSocketAddress target = parseAddress(in, atyp);
                if (target == null) {
                    sendReply(out, REP_ATYP_NOT_SUPPORTED, "0.0.0.0", 0);
                    return;
                }
                targetHost = target.getHostString();
                targetPort = target.getPort();
            } catch (Exception e) {
                System.err.println("[SOCKS5] Parse address error: " + e.getMessage());
                sendReply(out, REP_GENERAL_FAILURE, "0.0.0.0", 0);
                return;
            }

            System.out.println("[SOCKS5] CMD=" + cmdString(cmd) + " " + targetHost + ":" + targetPort);

            // 处理命令
            if (cmd == CMD_CONNECT) {
                handleConnect(clientSocket, out, targetHost, targetPort);
            } else if (cmd == CMD_BIND) {
                // BIND 暂不支持
                sendReply(out, REP_CMD_NOT_SUPPORTED, "0.0.0.0", 0);
            } else if (cmd == CMD_UDP_ASSOCIATE) {
                // UDP ASSOCIATE 暂不支持
                sendReply(out, REP_CMD_NOT_SUPPORTED, "0.0.0.0", 0);
            } else {
                sendReply(out, REP_CMD_NOT_SUPPORTED, "0.0.0.0", 0);
            }

        } catch (IOException e) {
            System.err.println("[SOCKS5] Client handler error: " + e.getMessage());
        } finally {
            closeQuietly(clientSocket);
        }
    }

    /**
     * 处理 CONNECT 命令 — 建立 TCP 隧道
     */
    private void handleConnect(Socket clientSocket, OutputStream out, String host, int port) {
        Socket targetSocket = null;
        try {
            targetSocket = new Socket();
            targetSocket.connect(new InetSocketAddress(host, port), config.getConnectTimeout());
            targetSocket.setSoTimeout(config.getSocketTimeout());

            // 回复成功
            InetSocketAddress bound = (InetSocketAddress) targetSocket.getLocalSocketAddress();
            sendReply(out, REP_SUCCEEDED, bound.getAddress().getHostAddress(), bound.getPort());

            System.out.println("[SOCKS5] Tunnel established: " + host + ":" + port);

            // 双向透传
            pipeBidirectional(clientSocket, targetSocket, host + ":" + port);

        } catch (IOException e) {
            System.err.println("[SOCKS5] Connect to " + host + ":" + port + " failed: " + e.getMessage());
            try {
                sendReply(out, REP_CONN_REFUSED, "0.0.0.0", 0);
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
     * 解析 SOCKS5 目标地址
     */
    private InetSocketAddress parseAddress(InputStream in, byte atyp) throws IOException {
        if (atyp == ATYP_IPV4) {
            byte[] addr = readExact(in, 4);
            if (addr == null) return null;
            int port = readPort(in);
            InetAddress inet = InetAddress.getByAddress(addr);
            return new InetSocketAddress(inet, port);

        } else if (atyp == ATYP_DOMAIN) {
            int len = in.read() & 0xFF; // 域名长度
            if (len < 0) return null;
            byte[] domainBytes = readExact(in, len);
            if (domainBytes == null) return null;
            String domain = new String(domainBytes, java.nio.charset.StandardCharsets.UTF_8);
            int port = readPort(in);
            return InetSocketAddress.createUnresolved(domain, port);

        } else if (atyp == ATYP_IPV6) {
            byte[] addr = readExact(in, 16);
            if (addr == null) return null;
            int port = readPort(in);
            InetAddress inet = InetAddress.getByAddress(addr);
            return new InetSocketAddress(inet, port);

        } else {
            return null; // 不支持的地址类型
        }
    }

    /**
     * 读取端口号 (2 bytes, big-endian)
     */
    private int readPort(InputStream in) throws IOException {
        int high = in.read() & 0xFF;
        int low = in.read() & 0xFF;
        return (high << 8) | low;
    }

    /**
     * 发送 SOCKS5 响应
     */
    private void sendReply(OutputStream out, byte rep, String bindAddr, int bindPort) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        buf.write(SOCKS_VERSION); // VER
        buf.write(rep);           // REP
        buf.write(0x00);          // RSV

        // 这里我们用 IPv4 地址返回
        buf.write(ATYP_IPV4);
        byte[] addrBytes = InetAddress.getByName(bindAddr).getAddress();
        buf.write(addrBytes);
        buf.write((bindPort >> 8) & 0xFF);
        buf.write(bindPort & 0xFF);

        out.write(buf.toByteArray());
        out.flush();
    }

    /**
     * 精确读取指定字节数
     */
    private byte[] readExact(InputStream in, int length) throws IOException {
        byte[] buf = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(buf, offset, length - offset);
            if (read == -1) return null; // 连接关闭
            offset += read;
        }
        return buf;
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
                closeQuietly(targetSocket);
                closeQuietly(clientSocket);
            }
        }, "Socks5-C2T-" + label);
        c2t.setDaemon(true);

        Thread t2c = new Thread(() -> {
            try {
                LightProxy.pipe(targetSocket.getInputStream(), clientSocket.getOutputStream());
            } catch (IOException e) {
            } finally {
                closeQuietly(targetSocket);
                closeQuietly(clientSocket);
            }
        }, "Socks5-T2C-" + label);
        t2c.setDaemon(true);

        c2t.start();
        t2c.start();

        try {
            c2t.join();
            t2c.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("[SOCKS5] Tunnel closed: " + label);
    }

    private void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private String cmdString(byte cmd) {
        switch (cmd) {
            case CMD_CONNECT: return "CONNECT";
            case CMD_BIND: return "BIND";
            case CMD_UDP_ASSOCIATE: return "UDP-ASSOCIATE";
            default: return "UNKNOWN(0x" + Integer.toHexString(cmd & 0xFF) + ")";
        }
    }
}
