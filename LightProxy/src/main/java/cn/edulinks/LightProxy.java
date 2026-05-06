package cn.edulinks;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public class LightProxy implements Runnable {
    private ServerSocket serverSocket;

    // 用线程池替代 ArrayList<Thread>，避免线程泄漏
    private static final ExecutorService threadPool = Executors.newCachedThreadPool(
            r -> {
                Thread t = new Thread(r, "LightProxy-Worker");
                t.setDaemon(true);
                return t;
            }
    );

    /**
     * 信号量
     */
    private volatile boolean running = true;

    public static void main(String[] args) {
        LightProxy hps = new LightProxy();
        hps.listen();
    }

    public LightProxy() {
        int port = 8080;

        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Http Proxy Server listen at : " + port);
            running = true;
        } catch (Exception e) {
            e.printStackTrace();
            running = false;
        }

        new Thread(this).start();
    }

    public void listen() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // 提交到线程池处理，不再手动管理线程列表
                threadPool.submit(() -> parseRequest(clientSocket));
            } catch (SocketException e) {
                if (running) {
                    System.out.println("Socket Error: " + e.getMessage());
                }
            } catch (Exception e) {
                System.out.println("Accept Error!");
                e.printStackTrace();
            }
        }
    }

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
                // 普通 HTTP 请求（GET / POST / PUT / DELETE ...）
                // 读取请求体
                byte[] bodyBytes = new byte[contentLength];
                int bytesRead = 0;
                while (bytesRead < contentLength) {
                    int read = clientInput.read(bodyBytes, bytesRead, contentLength - bytesRead);
                    if (read == -1) break;
                    bytesRead += read;
                }
                String body = new String(bodyBytes, StandardCharsets.UTF_8);

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
            System.out.println("Parse Request Error!");
            e.printStackTrace();
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 处理 CONNECT 方法 — 建立 HTTPS 隧道
     *
     * CONNECT www.example.com:443 HTTP/1.1
     *
     * 代理不解析 TLS 数据，只做 TCP 透传。
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

        System.out.println("[CONNECT] Tunnel to " + host + ":" + port);

        try {
            // 连接目标服务器
            Socket targetSocket = new Socket(host, port);
            targetSocket.setSoTimeout(0); // 隧道模式不设超时，保持长连接
            clientSocket.setSoTimeout(0);

            // 向客户端返回 200 Connection Established
            String connectResponse = "HTTP/1.1 200 Connection Established\r\n" +
                    "Proxy-Agent: LightProxy/1.0\r\n" +
                    "Connection: keep-alive\r\n" +
                    "\r\n";
            clientSocket.getOutputStream().write(connectResponse.getBytes(StandardCharsets.UTF_8));
            clientSocket.getOutputStream().flush();

            System.out.println("[CONNECT] Tunnel established: " + host + ":" + port);

            // 双向透传：创建两个线程分别处理两个方向的数据流
            Thread clientToTarget = new Thread(() -> {
                try {
                    pipe(clientSocket.getInputStream(), targetSocket.getOutputStream());
                } catch (IOException e) {
                    System.out.println("[CONNECT] client→target pipe closed: " + e.getMessage());
                } finally {
                    closeQuietly(targetSocket);
                    closeQuietly(clientSocket);
                }
            }, "Pipe-C2T-" + hostPort);
            clientToTarget.setDaemon(true);

            Thread targetToClient = new Thread(() -> {
                try {
                    pipe(targetSocket.getInputStream(), clientSocket.getOutputStream());
                } catch (IOException e) {
                    System.out.println("[CONNECT] target→client pipe closed: " + e.getMessage());
                } finally {
                    closeQuietly(targetSocket);
                    closeQuietly(clientSocket);
                }
            }, "Pipe-T2C-" + hostPort);
            targetToClient.setDaemon(true);

            clientToTarget.start();
            targetToClient.start();

            // 等待任一方向断开
            clientToTarget.join();
            targetToClient.join();

            System.out.println("[CONNECT] Tunnel closed: " + host + ":" + port);

        } catch (ConnectException e) {
            System.out.println("[CONNECT] Cannot connect to " + host + ":" + port);
            try {
                sendErrorResponse(clientSocket.getOutputStream(), 504, "Gateway Timeout - Cannot reach target");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            closeQuietly(clientSocket);
        } catch (IOException e) {
            System.out.println("[CONNECT] IO Error: " + e.getMessage());
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
     * 管道传输：从 src 读到 dst，直到 EOF
     * 使用 8KB 缓冲区，比单字节 readLine 高效得多
     */
    private static void pipe(InputStream src, OutputStream dst) throws IOException {
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = src.read(buffer)) != -1) {
            dst.write(buffer, 0, bytesRead);
            dst.flush();
        }
        // 读到 EOF 时关闭输出端，通知对端
        try {
            dst.flush();
        } catch (IOException ignored) {
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

            // 发送请求头 + body（用字节数组，避免编码问题）
            targetOutput.write(requestBuilder.toString().getBytes(StandardCharsets.UTF_8));
            if (bodyBytes.length > 0) {
                targetOutput.write(bodyBytes);
            }
            targetOutput.flush();

            // 读取目标服务器响应
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
            clientOutput.write("\r\n".getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();

            // 转发响应体（二进制透传）
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = targetInput.read(buffer)) != -1) {
                clientOutput.write(buffer, 0, bytesRead);
                clientOutput.flush();
            }

            System.out.println("[HTTP] Response sent to client (status " + statusCode + ")");

        } catch (ConnectException e) {
            try {
                sendErrorResponse(clientSocket.getOutputStream(), 504, "Gateway Timeout");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        } catch (IOException e) {
            try {
                sendErrorResponse(clientSocket.getOutputStream(), 502, "Bad Gateway");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        } finally {
            closeQuietly(clientSocket);
        }
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

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }

    private static void sendErrorResponse(OutputStream clientOutput, int code, String message) {
        try {
            // 修复：正确的 HTTP 响应格式（头在前，空行后是 body）
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

    // ===== 以下为旧版方法，保留但不再主动调用 =====

    /**
     * @deprecated 已被 parseRequest + forwardRequest 替代
     */
    @Deprecated
    private static void handleRequest(Socket socket_client) {
        // ... old code kept for reference
        System.out.println("handleRequest (deprecated) called");
    }

    /**
     * @deprecated 已被 parseRequest + forwardRequest 替代
     */
    @Deprecated
    private static void handleClientRequest(Socket socket_client) {
        System.out.println("handleClientRequest (deprecated) called");
    }

    /**
     * @deprecated
     */
    @Deprecated
    private static void sendResponseToClient(Map<String, String> urlResult, BufferedWriter proxyToClientBw) {
        System.out.println("sendResponseToClient (deprecated) called");
    }

    private static void handleErrorResponse(InputStream targetInput, OutputStream clientOutput, int statusCode, Map<String, String> headers) throws IOException {
        System.out.println("Handle error response!");
    }

    @Override
    public void run() {
        System.out.println("LightProxy main thread started.");
    }
}
