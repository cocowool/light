package cn.edulinks;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
//import java.net.http.HttpClient;

public class LightProxy implements Runnable {
    private ServerSocket serverSocket;
    static ArrayList<Thread> servicingThreads;

    /**
     * #TODO 后续用作信号量
     */
    private volatile boolean running = true;

    public static void main(String[] args){
        LightProxy hps = new LightProxy();
        hps.listen();
    }

    public LightProxy(){
        int port = 8080;

        servicingThreads = new ArrayList<>();

        new Thread(this).start();	// Starts overriden run() method at bottom

        try {
            serverSocket = new ServerSocket(port);
            //设置服务端与客户端连接未活动超时时间
            // serverSocket.setSoTimeout(1000 * 60);
            System.out.println("Http Proxy Server listen at : " + port);
            running = true;
        }catch(Exception e){
            e.printStackTrace();
            running = false;
        }
    }

    public void listen(){

        while (running) {
            try {
                Socket socket_client = serverSocket.accept();

                // Thread thread = new Thread( () ->handleClientRequest(socket_client) );
                // Thread thread = new Thread( () ->handleRequest(socket_client) );

                Thread thread = new Thread( () ->parseRequest(socket_client) );
                servicingThreads.add(thread);
                // Thread thread = new Thread( ()->handleClientRequest(socket_client) );

                thread.start();
            }catch(SocketException e){
                System.out.println("Socket Error!");
            }catch(Exception e){
                System.out.println("Accept Error!");
                e.printStackTrace();
            }

        }
    }

    /**
     * 2025-04-12 根据DeepSeek建议，解析用户请求细节，判断长度
     * @param socket_client 处理用户请求的Socket连接符
     */
    private static void parseRequest(Socket socket_client) {
        try{
            InputStream clientInput = socket_client.getInputStream();
            BufferedReader proxyToClientBr = new BufferedReader(new InputStreamReader(clientInput));
            BufferedWriter proxyToClientBw = new BufferedWriter(new OutputStreamWriter(socket_client.getOutputStream()));

            //解析请求头
            String requestLine = proxyToClientBr.readLine();
            if( requestLine == null) return;

            //提取请求方法、路径、协议、主机、端口
            String[] requestParts = requestLine.split(" ");
            if( requestParts.length < 3 ) return;
            String method = requestParts[0].trim();
            String path = requestParts[1].trim();
            String protocol = requestParts[2].trim();

            //读取请求头
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            int contentLength = 0;
            while( (headerLine = proxyToClientBr.readLine()) != null && !headerLine.isEmpty() ){
                String[] headerParts = headerLine.split(":",2);
                if(headerParts.length == 2){
                    headers.put(headerParts[0], headerParts[1]);
                    //System.out.println(headerLine);
                    if( "Content-Length".equalsIgnoreCase(headerParts[0])){
                        contentLength = Integer.parseInt(headerParts[1].trim());
                    }
                }
            }

            //读取请求体（如果有）
            byte[] bodyBytes = new byte[contentLength];
            int bytesRead = 0;
            while(bytesRead < contentLength){
                int read = clientInput.read(bodyBytes, bytesRead, contentLength - bytesRead);
                if ( read == -1)    break;
                bytesRead += read;
            }
            String body = new String(bodyBytes, StandardCharsets.UTF_8);    //@按实际编码解析

//            StringBuilder body = new StringBuilder();
//            if( contentLength > 0){
//                char[] buffer = new char[contentLength];
//                int bytesRead = proxyToClientBr.read(buffer, 0, contentLength);
//                body.append(buffer,0, bytesRead);
//            }

            //解析目标主机和端口
            String host = headers.getOrDefault("Host", "www.edulinks.cn").split(":")[0].trim();
            int port = 80;
            if (headers.containsKey("Host") && headers.get("Host").contains(":")) {
                try{
                    port = Integer.parseInt(headers.get("Host").split(":")[1].trim());
                }catch (NumberFormatException e){
                    System.out.println("Parse Port Error, use default port 80.");
                }
            }

            // 转发请求到目标服务器
            forwardRequest(method, protocol, host, port, path, headers, body, socket_client);
        }catch (Exception e){
            System.out.println("Parse Request Error!");
            e.printStackTrace();
        }finally {
            try{
                socket_client.close();
            }catch (IOException e){
                e.printStackTrace();
            }
        }
    }

    /**
     *
     * @param method
     * @param protocol
     * @param host
     * @param port
     * @param path
     * @param headers
     * @param body
     * @param clientWriter
     * @param clientSocket
     *
     * # 测试问题：Firefox配置代理后，不能正常返回 http://www.edulinks.cn 的GET请求
     */
    private static void forwardRequest(String method, String protocol, String host, int port, String path,
                                       Map<String, String> headers, String body, Socket clientSocket) {
        host = host.trim();
        System.out.println("Try to open Host:[" + host + "] , Port:[" + port + "]");

        try (Socket targetSocket = new Socket(host, port);
             OutputStream targetOutput = targetSocket.getOutputStream();
             InputStream targetInput = targetSocket.getInputStream();
             OutputStream clientOutput = clientSocket.getOutputStream() ) {

            // 以字节流方式处理请求头
//            byte[] buffer = new byte[8192];

            // 构建请求头
            StringBuilder requestBuilder = new StringBuilder();
            requestBuilder.append(method).append(" ").append(path).append(" ").append(protocol).append("\r\n");
            // #todo 强制添加 Host 头

            // 检查客户端请求是否包含长链接
            boolean clientKeepAlive = headers.getOrDefault("Connection", "").equalsIgnoreCase("keep-alive");
            // 根据请求头情况设置转发头
            headers.put("Connection", clientKeepAlive?"keep-alive":"close");

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                requestBuilder.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
            }
            requestBuilder.append("\r\n");


            //System.out.println("Request body: " + requestBuilder);

            // 发送请求头和请求体
            targetOutput.write(requestBuilder.toString().getBytes(StandardCharsets.UTF_8));
            if (!body.isEmpty()) {
                targetOutput.write(body.getBytes(StandardCharsets.UTF_8));
            }
            targetOutput.flush();

            System.out.println("Send request to remote server finished!");
            System.out.println("Try to send message back to client.");

            //使用字节流方式读取状态行和头信息
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int b;
            boolean headerEnd = false;
            while( (b = targetInput.read()) != -1 ){
//                System.out.println("Read response from remote server via byte stream.");
                headerBuffer.write(b);
                if(headerBuffer.size() >= 4){
                    byte[] lastFour = new byte[4];
                    System.arraycopy(headerBuffer.toByteArray(), headerBuffer.size() - 4, lastFour, 0, 4);
                    if (lastFour[0] == '\r' && lastFour[1] == '\n' && lastFour[2] == '\r' && lastFour[3] == '\n') {
                        headerEnd = true;
                        break;
                    }
                }
            }

            if (!headerEnd) {
                sendErrorResponse(clientOutput, 502, "Incomplete headers");
                return;
            }

            String responseHeaders = new String(headerBuffer.toByteArray(), StandardCharsets.UTF_8);
            // DEBUG 打印获取到的响应头信息
            System.out.println("Response Header start:");
            System.out.println(responseHeaders);
            System.out.println("Response Header End!");
            clientOutput.write(responseHeaders.getBytes(StandardCharsets.UTF_8));

            /**
             * 2025-05-14 调试注释
            // 读取目标服务器响应并转发给客户端
            BufferedReader headerReader = new BufferedReader(new InputStreamReader(targetInput));
            String statusLine = headerReader.readLine();
            if(statusLine == null){
                sendErrorResponse(clientOutput, 502, "Empty response from upstream.");
                return;
            }

            // 转发状态行
            clientOutput.write( (statusLine + "\r\n").getBytes(StandardCharsets.UTF_8) );

            //读取并转发响应头
            String headerLine;
            while( (headerLine = headerReader.readLine()) != null ){
                if(headerLine.isEmpty()) break;
                clientOutput.write((headerLine + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            clientOutput.write("\r\n".getBytes());
            System.out.println("Send response header to client.");
             **/

            // 使用二进制方式传输响应体
            byte[] buffer = new byte[8192];
            int bytesRead;
            while( (bytesRead = targetInput.read(buffer)) != -1 ){
                System.out.println("Read remote response and send back to client.");
                clientOutput.write(buffer, 0 , bytesRead);
            }
            clientOutput.flush();

            System.out.println("Send to client end!");
        } catch(ConnectException e){
            // 从Socket重新获取输出流 (关键修复)
            try {
                OutputStream clientOutput = clientSocket.getOutputStream();
                sendErrorResponse(clientOutput, 504, "Gateway Timeout");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        } catch(IOException e) {
            try {
                OutputStream clientOutput = clientSocket.getOutputStream();
                sendErrorResponse(clientOutput, 502, "Bad Gateway");
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }

    private static void sendErrorResponse(OutputStream clientOutput, int code, String message){
        try{
            String response = "HTTP/1.1 " + code + " " + message + "\r\n\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Connection: close\r\n\r\n" +
                    "Proxy Error: " + message;

            clientOutput.write(response.getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    private static void handleRequest(Socket socket_client) {
        BufferedReader proxyToClientBr = null;
        BufferedWriter proxyToClientBw = null;

        try {
            // 2025-02-28 如果设置超时时间，则通过 telnet 发送请求会失败，不太清楚具体原因，先注释掉
            // socket_client.setSoTimeout(4000);
            proxyToClientBr = new BufferedReader(new InputStreamReader(socket_client.getInputStream()));
            proxyToClientBw = new BufferedWriter(new OutputStreamWriter(socket_client.getOutputStream()));
        } catch (Exception e) {
            System.out.println("Set timeout Error!");
            e.printStackTrace();
        }

        try{
            // 解析请求方法和请求地址
            String requestString;
            String requestHeader = "";
            // requestString = proxyToClientBr.readLine();

            // System.out.println("Reuest header: " + requestString);

            // Get the Request type
            // String request = requestString.substring(0,requestString.indexOf(' '));
            // System.out.println("Request url : " + request);

            while( (requestString = proxyToClientBr.readLine()) != null && !requestString.isEmpty()){
                requestHeader += requestString + "\r\n";
            }

            System.out.println("Request header : ");
            System.out.println( requestHeader );
            // proxyToClientBr.close();

            Map<String, String> result = HttpRequestParser.parseRequest(requestHeader);
            System.out.println(result.get("host"));
            System.out.println("Test HttpRequestParser!");

            // 判断是否有 Cache

            // 判断是否 Block

            sendResponseToClient(result, proxyToClientBw);


        }catch(Exception e){
            System.out.println("Handle Request Error!");
            e.printStackTrace();
        }

    }

    /**
     * 返回普通的HTTP请求
     * @param urlResult
     */
    private static void sendResponseToClient(Map<String, String> urlResult, BufferedWriter proxyToClientBw){

        try{
            // Create the URL
            URL remoteURL = new URL("http://" + urlResult.get("host") + ":" + urlResult.get("port") + urlResult.get("path") );
            // Create a connection to remote server
            HttpURLConnection proxyToServerCon = (HttpURLConnection)remoteURL.openConnection();
            proxyToServerCon.setRequestProperty("Content-Type",
                    "application/x-www-form-urlencoded");
            proxyToServerCon.setRequestProperty("Content-Language", "en-US");
            proxyToServerCon.setUseCaches(false);
            proxyToServerCon.setDoOutput(true);

            // Create Buffered Reader from remote Server
            BufferedReader proxyToServerBR = new BufferedReader(new InputStreamReader(proxyToServerCon.getInputStream()));


            // Send success code to client
            String line = "HTTP/1.0 200 OK\n" +
                    "Proxy-agent: ProxyServer/1.0\n" +
                    "\r\n";
            proxyToClientBw.write(line);


            // Read from input stream between proxy and remote server
            while((line = proxyToServerBR.readLine()) != null){
                // Send on data to client
                proxyToClientBw.write(line);
            }

            // Ensure all data is sent by this point
            proxyToClientBw.flush();

            // Close Down Resources
            if(proxyToServerBR != null){
                proxyToServerBR.close();
            }

            if(proxyToClientBw != null){
                proxyToClientBw.close();
            }
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    private static void handleClientRequest(Socket socket_client){
        final byte[] Request = new byte[1024];
        byte[] Reply = new byte[4096];

        Socket proxySocket = null;

        try {

            final InputStream InputStreamClient = socket_client.getInputStream();
            final OutputStream OutputStreamClient = socket_client.getOutputStream();

            String requestHost = null;
            int requestPort = 80;
            try {
                //@ 在这里要判断用户发送的请求地址和端口，建立 Socket 链接
                BufferedReader client_reader = new BufferedReader(new InputStreamReader(InputStreamClient));
                String remoteRequest = "";
                String requestLine = client_reader.readLine();
                // remoteRequest = "";
                if( requestLine != null){
                    String[] parts = requestLine.split(" ");
                    if (parts.length >= 3) {
                        String requestMethod = parts[0];
                        String requestPath = parts[1];
                        String requestProtocol = parts[2];

                        if (requestPath.startsWith("http://")){
                            requestPath = requestPath.substring(7);
                        }else if( requestPath.startsWith("https://")){
                            requestPath = requestPath.substring(8);
                        }
                        int pathIndex = requestPath.indexOf('/');
                        if(pathIndex != -1){
                            requestPath = requestPath.substring(pathIndex);
                        }
                        System.out.println("Request path : " + requestPath);
                        remoteRequest = requestMethod + " " + requestPath + " " + requestProtocol + "\r\n";

                        String line;
                        while((line = client_reader.readLine()) != null && !line.isEmpty()){
                            if(line.startsWith("Host: ")){
                                String[] hostParts = line.substring(6).split(":");
                                requestHost = hostParts[0];
                                if(hostParts.length > 1){
                                    requestPort = Integer.parseInt(hostParts[1]);
                                }
                            }
                            remoteRequest += line + "\r\n";
                        }

                        // 输出提取的信息
                        System.out.println("Method: " + requestMethod);
                        System.out.println("Path: " + requestPath);
                        System.out.println("Protocol: " + requestProtocol);
                        System.out.println("Host: " + requestHost);
                        System.out.println("Port: " + requestPort);
                    }
                }

                remoteRequest += "\r\n";
                System.out.println("Send Request to Remote Server:");
                System.out.println(remoteRequest);

                // 先手工写死请求的远端地址，实际需要从用户请求中解析出来
                if( requestHost == null){
                    requestHost = "www.edulinks.cn";
                    requestPort = 80;
                }

                proxySocket = new Socket(requestHost, requestPort);
                final InputStream prxoyInputStream = proxySocket.getInputStream();
                final OutputStream proxyOutputStream = proxySocket.getOutputStream();

                proxyOutputStream.write(remoteRequest.getBytes());
                proxyOutputStream.flush();

                // proxySocket.shutdownOutput();

                int Bytes_Read;
                try {
                    while ( (Bytes_Read = prxoyInputStream.read(Reply))!= -1){
                        System.out.println(Reply);
                        OutputStreamClient.write(Reply, 0, Bytes_Read);
                        OutputStreamClient.flush();
                    }

                }catch(IOException e){
                    System.out.println("Get response Error !");
                    System.out.println(e);
                    e.printStackTrace();
                }
            }catch(IOException e){
                System.out.println("Send request Error !");
                System.out.println(e);
            }

            OutputStreamClient.close();
            proxySocket.close();
            socket_client.close();
        } catch (IOException e) {
            System.out.println("Proxy can not get response. ");
            e.printStackTrace();
        }

    }

    @Override
    public void run() {
        System.out.println("New thread is running ... ");
    }
}
