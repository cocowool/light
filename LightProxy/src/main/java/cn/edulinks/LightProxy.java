package cn.edulinks;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

/**
 * LightProxy — 轻量级 HTTP / SOCKS5 代理服务器
 *
 * 支持:
 *   - HTTP 代理 (GET/POST/CONNECT 隧道)
 *   - SOCKS5 代理 (CONNECT 命令)
 *   - 双端口独立配置，通过 light.properties 控制
 *
 * @author OpenClaw AI Assistant (shiqiang)
 * @since  2024-04-12
 */
public class LightProxy implements Runnable {

    // --- 共享线程池 (SOCKS5 和 HTTP 共用) ---
    private static ExecutorService threadPool = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r, "LightProxy-Worker");
                t.setDaemon(true);
                return t;
            }
    );

    /**
     * 获取共享线程池（供 Socks5Proxy 使用）
     */
    public static ExecutorService getThreadPool() {
        return threadPool;
    }

    // --- HTTP 代理 ---
    private ServerSocket httpServerSocket;
    private volatile boolean httpRunning = true;

    // --- SOCKS5 代理 ---
    private Socks5Proxy socks5Proxy;

    // --- SOCKS4 代理 ---
    private Socks4Proxy socks4Proxy;

    // --- 配置 ---
    private final ProxyConfig config;

    /**
     * 优雅关闭钩子
     */
    public static void setupShutdownHook(LightProxy instance) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Shutdown] LightProxy stopping...");
            instance.stop();
        }));
    }

    public static void main(String[] args) {
        ProxyConfig config = new ProxyConfig();
        config.printSummary();

        LightProxy proxy = new LightProxy(config);
        setupShutdownHook(proxy);
        proxy.start();
    }

    public LightProxy(ProxyConfig config) {
        this.config = config;
    }

    /**
     * 启动所有启用的代理服务
     */
    public void start() {
        // 启动 HTTP 代理
        if (config.isHttpEnabled()) {
            new Thread(this, "HTTP-Listener").start();
        } else {
            System.out.println("[HTTP] Disabled by config.");
        }

        // 启动 SOCKS5 代理
        if (config.isSocks5Enabled()) {
            socks5Proxy = new Socks5Proxy(config.getSocks5Port(), config);
            new Thread(socks5Proxy, "SOCKS5-Listener").start();
        } else {
            System.out.println("[SOCKS5] Disabled by config.");
        }

        // 启动 SOCKS4 代理
        if (config.isSocks4Enabled()) {
            socks4Proxy = new Socks4Proxy(config.getSocks4Port(), config);
            new Thread(socks4Proxy, "SOCKS4-Listener").start();
        } else {
            System.out.println("[SOCKS4] Disabled by config.");
        }

        // 如果三个都没启用，打印提示并退出
        if (!config.isHttpEnabled() && !config.isSocks5Enabled() && !config.isSocks4Enabled()) {
            System.err.println("No proxy service enabled. Check light.properties.");
        }
    }

    /**
     * 停止所有代理服务
     */
    public void stop() {
        httpRunning = false;
        try {
            if (httpServerSocket != null && !httpServerSocket.isClosed()) {
                httpServerSocket.close();
            }
        } catch (IOException ignored) {
        }
        if (socks5Proxy != null) {
            socks5Proxy.stop();
        }
        threadPool.shutdown();
        if (socks4Proxy != null) {
            socks4Proxy.stop();
        }
    }

    /**
     * HTTP 代理监听线程
     */
    @Override
    public void run() {
        int port = config.getHttpPort();
        try {
            httpServerSocket = new ServerSocket(port);
            System.out.println("[HTTP] Listening on port " + port);
            httpRunning = true;
        } catch (Exception e) {
            System.err.println("[HTTP] Failed to listen on port " + port + ": " + e.getMessage());
            httpRunning = false;
            return;
        }

        while (httpRunning) {
            try {
                Socket clientSocket = httpServerSocket.accept();
                threadPool.submit(() -> parseRequest(clientSocket));
            } catch (SocketException e) {
                if (httpRunning) {
                    System.err.println("[HTTP] Socket error: " + e.getMessage());
                }
            } catch (Exception e) {
                System.err.println("[HTTP] Accept error: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    // ========================================================================
    //  HTTP 代理核心逻辑
    // ========================================================================

    /**
     * 解析用户请求，区分 HTTP 和 CONNECT 方法
     */
    private static void parseRequest(Socket clientSocket) {
        try {
            InputStream clientInput = clientSocket.getInputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8));

            String requestLine = br.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                clientSocket.close();
                return;
            }

            String[] requestParts = requestLine.split(" ");
            if (requestParts.length < 3) {
                clientSocket.close();
                return;
            }

            String method = requestParts[0].trim();
            String pathOrHost = requestParts[1].trim();
            String protocol = requestParts[2].trim();

            // 读取请求头
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            int contentLength = 0;
            while ((headerLine = br.readLine()) != null && !headerLine.isEmpty()) {
                String[] headerParts = headerLine.split(":", 2);
                if (headerParts.length == 2) {
                    headers.put(headerParts[0].trim(), headerParts[1].trim());
                    if ("Content-Length".equalsIgnoreCase(headerParts[0].trim())) {
                        try {
                            contentLength = Integer.parseInt(headerParts[1].trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }

            // --- 区分 CONNECT 和普通 HTTP 请求 ---
            if ("CONNECT".equalsIgnoreCase(method)) {
                handleConnect(pathOrHost, clientSocket);
            } else {
                // 普通 HTTP 请求
                byte[] bodyBytes = new byte[contentLength];
                int bytesRead = 0;
                while (bytesRead < contentLength) {
                    int read = clientInput.read(bodyBytes, bytesRead, contentLength - bytesRead);
                    if (read == -1) break;
                    bytesRead += read;
                }

                // 解析目标主机和端口
                String host = headers.getOrDefault("Host", "").split(":")[0].trim();
                int port = 80;
                if (headers.containsKey("Host") && headers.get("Host").contains(":")) {
                    try {
                        port = Integer.parseInt(headers.get("Host").split(":")[1].trim());
                    } catch (NumberFormatException e) {
                        port = 80;
                    }
                }

                if (host.isEmpty()) {
                    sendErrorResponse(clientSocket.getOutputStream(), 400, "Bad Request - Missing Host header");
                    clientSocket.close();
                    return;
                }

                forwardRequest(method, protocol, host, port, pathOrHost, headers, bodyBytes, clientSocket);
            }
        } catch (Exception e) {
            System.err.println("[HTTP] Parse Request Error: " + e.getMessage());
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 处理 CONNECT 方法 — 建立 HTTPS 隧道
     */
    private static void handleConnect(String hostPort, Socket clientSocket) {
        String[] parts = hostPort.split(":");
        String host = parts[0];
        int port = 443;
        if (parts.length > 1) {
            try {
                port = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                port = 443;
            }
        }

        System.out.println("[HTTP CONNECT] Tunnel to " + host + ":" + port);

        try {
            Socket targetSocket = new Socket(host, port);
            targetSocket.setSoTimeout(0);
            clientSocket.setSoTimeout(0);

            // 返回 200 Connection Established
            String connectResponse = "HTTP/1.1 200 Connection Established\r\n" +
                    "Proxy-Agent: LightProxy/1.0\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n";
            clientSocket.getOutputStream().write(connectResponse.getBytes(StandardCharsets.UTF_8));
            clientSocket.getOutputStream().flush();

            System.out.println("[HTTP CONNECT] Tunnel established: " + host + ":" + port);

            Thread c2t = new Thread(() -> {
                try {
                    pipe(clientSocket.getInputStream(), targetSocket.getOutputStream());
                } catch (IOException e) {
                    // 正常断开
                } finally {
                    closeQuietly(targetSocket);
                    closeQuietly(clientSocket);
                }
            }, "Pipe-C2T-" + hostPort);
            c2t.setDaemon(true);

            Thread t2c = new Thread(() -> {
                try {
                    pipe(targetSocket.getInputStream(), clientSocket.getOutputStream());
                } catch (IOException e) {
                    // 正常断开
                } finally {
                    closeQuietly(targetSocket);
                    closeQuietly(clientSocket);
                }
            }, "Pipe-T2C-" + hostPort);
            t2c.setDaemon(true);

            c2t.start();
            t2c.start();
            c2t.join();
            t2c.join();

            System.out.println("[HTTP CONNECT] Tunnel closed: " + host + ":" + port);

        } catch (ConnectException e) {
            System.err.println("[HTTP CONNECT] Cannot connect to " + host + ":" + port);
            try {
                sendErrorResponse(clientSocket.getOutputStream(), 504, "Gateway Timeout");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            closeQuietly(clientSocket);
        } catch (IOException e) {
            System.err.println("[HTTP CONNECT] IO Error: " + e.getMessage());
            try {
                sendErrorResponse(clientSocket.getOutputStream(), 502, "Bad Gateway");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            closeQuietly(clientSocket);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 转发普通 HTTP 请求到目标服务器
     */
    private static void forwardRequest(String method, String protocol, String host, int port, String path,
                                       Map<String, String> headers, byte[] bodyBytes, Socket clientSocket) {
        host = host.trim();
        System.out.println("[HTTP] Forwarding " + method + " " + host + ":" + port + path);

        try (Socket targetSocket = new Socket(host, port);
             OutputStream targetOutput = targetSocket.getOutputStream();
             InputStream targetInput = targetSocket.getInputStream()) {

            targetSocket.setSoTimeout(10000);
            clientSocket.setSoTimeout(15000);

            OutputStream clientOutput = clientSocket.getOutputStream();

            // 构建请求头
            StringBuilder requestBuilder = new StringBuilder();
            requestBuilder.append(method).append(" ").append(path).append(" ").append(protocol).append("\r\n");

            if (!headers.containsKey("Host")) {
                headers.put("Host", host + ":" + port);
            }

            boolean clientKeepAlive = "keep-alive".equalsIgnoreCase(headers.getOrDefault("Connection", ""));
            headers.put("Connection", clientKeepAlive ? "keep-alive" : "close");

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            requestBuilder.append("\r\n");

            // 发送请求头 + body
            targetOutput.write(requestBuilder.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                targetOutput.write(bodyBytes);
            }
            targetOutput.flush();

            // 读取响应
            String statusLine = readLine(targetInput);
            if (statusLine == null) {
                sendErrorResponse(clientOutput, 502, "Empty response from upstream");
                return;
            }

            int statusCode = parseStatusCode(statusLine);
            clientOutput.write((statusLine + "\r\n").getBytes(StandardCharsets.UTF_8));

            // 转发响应头
            String headerLine;
            while ((headerLine = readLine(targetInput)) != null && !headerLine.isEmpty()) {
                clientOutput.write((headerLine + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            clientOutput.write("\r\n".getBytes());
            clientOutput.flush();

            // 转发响应体
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = targetInput.read(buffer)) != -1) {
                clientOutput.write(buffer, 0, bytesRead);
                clientOutput.flush();
            }

            System.out.println("[HTTP] Response sent (status " + statusCode + ")");

        } catch (ConnectException e) {
            try { sendErrorResponse(clientSocket.getOutputStream(), 504, "Gateway Timeout"); } catch (IOException ex) { ex.printStackTrace(); }
        } catch (IOException e) {
            try { sendErrorResponse(clientSocket.getOutputStream(), 502, "Bad Gateway"); } catch (IOException ex) { ex.printStackTrace(); }
        } finally {
            closeQuietly(clientSocket);
        }
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    /**
     * 管道传输：从 src 读到 dst，直到 EOF
     */
    public static void pipe(InputStream src, OutputStream dst) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = src.read(buffer)) != -1) {
            dst.write(buffer, 0, bytesRead);
            dst.flush();
        }
        try { dst.flush(); } catch (IOException ignored) {}
    }

    /**
     * 逐行读取（用于 HTTP 头解析）
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.mark(1);
                int next = in.read();
                if (next != '\n') in.reset();
                break;
            } else if (b == '\n') {
                break;
            }
            baos.write(b);
        }
        return (baos.size() > 0) ? new String(baos.toByteArray(), StandardCharsets.UTF_8) : null;
    }

    private static int parseStatusCode(String statusLine) {
        String[] parts = statusLine.split(" ");
        if (parts.length >= 2) {
            try {
                return Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return 500;
            }
        }
        return 500;
    }

    public static void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    public static void sendErrorResponse(OutputStream clientOutput, int code, String message) {
        try {
            String response = "HTTP/1.1 " + code + " " + message + "\r\n" +
                    "Content-Type: text/plain; charset=utf-8\r\n" +
                    "Connection: close\r\n" +
                    "Proxy-Agent: LightProxy/1.0\r\n" +
                    "\r\n" +
                    "Proxy Error: " + message;
            clientOutput.write(response.getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // ===== 以下为旧版方法 =====

    @Deprecated
    private static void handleRequest(Socket socket_client) {
        System.out.println("handleRequest (deprecated) called");
    }

    @Deprecated
    private static void handleClientRequest(Socket socket_client) {
        System.out.println("handleClientRequest (deprecated) called");
    }

    @Deprecated
    private static void sendResponseToClient(Map<String, String> urlResult, BufferedWriter proxyToClientBw) {
        System.out.println("sendResponseToClient (deprecated) called");
    }

    private static void handleErrorResponse(InputStream targetInput, OutputStream clientOutput, int statusCode, Map<String, String> headers) throws IOException {
        System.out.println("Handle error response!");
    }
}
