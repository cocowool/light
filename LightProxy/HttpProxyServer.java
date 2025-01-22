import java.io.*;
import java.net.*;
import java.util.concurrent.*;

/**
 * 
 * 2024-11-14 对于telnet发起的简单http请求能够正常返回，不支持 postman 请求
 * 2024-11-22 已支持 Postman 发 http 请求，不能正常响应重复请求
 * 
 * @TODO 后续考虑使用 HttpClient 库 / 或者使用 Netty 
 * 
 * 问题：
 * 1. 不能持续响应一个客户端的连续请求
 * 
 */
public class HttpProxyServer {
    private static final int THREAD_POOL_SIZE = 10;
    private static final ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    public static void main(String[] args) {
        int port = 8080; // HTTP代理端口

        // @TODO 解析用户自定义的端口参数
        try ( ServerSocket serverSocket = new ServerSocket(port) ) {
            //设置服务端与客户端连接未活动超时时间
            serverSocket.setSoTimeout(1000 * 60);
            System.out.println("Http Proxy Server listen at: " + port);

            final byte[] Request = new byte[1024];
            byte[] Reply = new byte[4096];

            while (true) {
                Socket proxySocket = null;
                Socket socket_client = null;

                try {
                    socket_client = serverSocket.accept();

                    final InputStream InputStreamClient = socket_client.getInputStream();
                    final OutputStream OutputStreamClient = socket_client.getOutputStream();

                    // 先手工写死请求的远端地址，实际需要从用户请求中解析出来
                    String requestHost = "www.edulinks.cn";
                    int requestPort = 80;

                    try {
                        proxySocket = new Socket(requestHost, requestPort);
                    }catch(IOException e){
                        System.out.println("Remote Host can not be reached.");
                        continue;
                    }

                    final InputStream prxoyInputStream = proxySocket.getInputStream();
                    final OutputStream proxyOutputStream = proxySocket.getOutputStream();

                    Thread New_Thread = new Thread(){
                        public void run(){
                            int Bytes_Read;
                            try {
                                //@ 在这里要判断用户发送的请求地址和端口，建立 Socket 链接
                                BufferedReader client_reader = new BufferedReader(new InputStreamReader(InputStreamClient));
                                String requestLine = client_reader.readLine();
                                if( requestLine != null){
                                    String[] parts = requestLine.split(" ");
                                    if (parts.length >= 3) {
                                        String requestMethod = parts[0];
                                        String requestPath = parts[1];
                                        String requestProtocol = parts[2];

                                        String requestHost = null;
                                        int requestPort = 80;
                                        String line;
                                        while((line = client_reader.readLine()) != null && !line.isEmpty()){
                                            if(line.startsWith("Host: ")){
                                                String[] hostParts = line.substring(6).split(":");
                                                requestHost = hostParts[0];
                                                if(hostParts.length > 1){
                                                    requestPort = Integer.parseInt(hostParts[1]);
                                                }
                                            }
                                        }

                                        // 输出提取的信息
                                        System.out.println("Method: " + requestMethod);
                                        System.out.println("Path: " + requestPath);
                                        System.out.println("Protocol: " + requestProtocol);
                                        System.out.println("Host: " + requestHost);
                                        System.out.println("Port: " + requestPort);
                                    }
                                }

                                while ( (Bytes_Read = InputStreamClient.read(Request)) != -1){
                                    proxyOutputStream.write(Request, 0, Bytes_Read);
                                    proxyOutputStream.flush();
                                }
                            }catch(IOException e){
                                System.out.println(e);
                            }

                            try {
                                proxyOutputStream.close();
                            }catch(IOException e){
                                System.out.println(e);
                            }
                        }
                    };

                    New_Thread.start();
                    int Bytes_Read;
                    try {
                        while ( (Bytes_Read = prxoyInputStream.read(Reply))!= -1){
                            OutputStreamClient.write(Reply, 0, Bytes_Read);
                            OutputStreamClient.flush();
                        }
                    }catch(IOException e){
                        System.out.println(e);
                    }

                    OutputStreamClient.close();
                    // System.out.println("New Thread !");
                    // executorService.submit(() -> handleClient(socket));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } 

    }



    private static void handleRequest(Socket socket, String request) {
        // 处理请求并返回响应
        // ...
    }

    // private static void handleClient(Socket clientSocket) {
    //     try (
    //         InputStream inputStream = new BufferedInputStream(clientSocket.getInputStream());
    //         OutputStream outputStream = new BufferedOutputStream(clientSocket.getOutputStream());
    //         BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
    //         PrintWriter writer = new PrintWriter(outputStream, true)
    //     ) {
    //         StringBuilder request = new StringBuilder();
    //         String line;
    //         String requestMethod = null;
    //         String requestPath = "/";
    //         String requestProtocol = "HTTP/1.1";
    //         String requestHost = "";
    //         int requestPort = 80;
    //         boolean headersEnd = false;
    //         boolean hasBody = false;
    //         long contentLength = -1;

    //         while ((line = br.readLine()) != null) {
    //             System.out.println("Client Send: " + line);

    //             // System.out.println("headersEnd = " + headersEnd);
    //             if(!headersEnd){
    //                 //判断请求的首行
    //                 if( request.length() == 0){
    //                     System.out.println(line);
    //                     String[] parts = line.split(" ");                        
    //                     if(parts.length >= 2){
    //                         requestMethod = parts[0];
    //                         if (parts.length > 2) {
    //                             requestPath = parts[1];
    //                         } else {
    //                             // No full URL, we'll rely on Host header
    //                         }
    //                         if (parts.length > 2 && parts[2].startsWith("HTTP/")) {
    //                             requestProtocol = parts[2];
    //                         }
    //                     }
    //                 }else if(line.isEmpty()){
    //                     System.out.println("Headers End!");
    //                     headersEnd = true;
    //                     request.append(line).append("\r\n");
    //                     break;
    //                 }else{
    //                     //解析其他头部
    //                     String[] headerParts = line.split(": ");
    //                     if (headerParts.length == 2) {
    //                         switch (headerParts[0].toLowerCase()) {
    //                             case "host":
    //                                 String[] hostParts = headerParts[1].split(":");
    //                                 requestHost = hostParts[0];
    //                                 if (hostParts.length > 1) {
    //                                     requestPort = Integer.parseInt(hostParts[1]);
    //                                 }
    //                                 break;
    //                             case "content-length":
    //                                 contentLength = Long.parseLong(headerParts[1]);
    //                                 hasBody = contentLength > 0;
    //                                 break;
    //                         }
    //                     }
    //                 }
    //             }

    //             request.append(line).append("\r\n");
    //             // System.out.println("Next Line ...");
    //             // System.out.println("Current request : " + request);
    //         }

    //         Run_Server(clientSocket, requestHost, 80);

    //         // try (
    //         //     Socket proxySocket = new Socket(requestHost, requestPort);
    //         //     InputStream proxyInput = new BufferedInputStream(proxySocket.getInputStream());
    //         //     OutputStream proxyOutput = new BufferedOutputStream(proxySocket.getOutputStream());
    //         // ) {
    //         //     String proxyRequestLine = requestMethod + " " + requestPath + " " + requestProtocol + "\r\n";
    //         //     // 补充请求的主机
    //         //     if( requestPort != 80){
    //         //         proxyRequestLine += "Host: " + requestHost + ":" + requestPort + "\r\n";
    //         //     }else{
    //         //         proxyRequestLine += "Host: " + requestHost + "\r\n";
    //         //     }
    //         //     proxyRequestLine += "\r\n";

    //         //     System.out.println("Send Request .... ");
    //         //     System.out.println(proxyRequestLine);

    //         //     proxyOutput.write(proxyRequestLine.getBytes());

    //         //     // Forward headers
    //         //     // proxyOutput.write(request.toString().getBytes());

    //         //     // Forward request body if present
    //         //     if (hasBody) {
    //         //         System.out.println("Body needs to send.");
    //         //         byte[] bodyBuffer = new byte[(int) contentLength];
    //         //         int totalBytesRead = 0;
    //         //         while (totalBytesRead < contentLength) {
    //         //             int bytesRead = inputStream.read(bodyBuffer, totalBytesRead, (int) contentLength - totalBytesRead);
    //         //             if (bytesRead == -1) {
    //         //                 // Handle error or incomplete read
    //         //                 break;
    //         //             }
    //         //             totalBytesRead += bytesRead;
    //         //         }
                    
    //         //         if (totalBytesRead == contentLength) {
    //         //             proxyOutput.write(bodyBuffer);
    //         //         } else {
    //         //             // Handle error or incomplete read
    //         //         }
    //         //     }

    //         //     // Forward response from proxy to client
    //         //     byte[] buffer = new byte[4096];
    //         //     int responseBytesRead;
    //         //     while ((responseBytesRead = proxyInput.read(buffer)) != -1) {
    //         //         System.out.println("Wait response....");
    //         //         outputStream.write(buffer, 0, responseBytesRead);
    //         //         outputStream.flush();
    //         //         System.out.println("output to client.");
    //         //     }

    //         //     proxyInput.close();
    //         //     proxyOutput.close();
    //         //     //处理后关闭相关的流
    //         //     proxySocket.close();
    //         //     System.out.println("proxySocket Closed.");
    //         // }catch(IOException e){
    //         //     System.out.println("The destination host can not reached! Please check host and port.");
    //         // }

    //         // inputStream.close();
    //         // outputStream.close();
    //         // clientSocket.close();
    //         // System.out.println("clientSocket Closed .");
    //         // return;
    //     }catch(IOException e){
    //         e.printStackTrace();
    //     } finally {
    //         try {
    //             clientSocket.close();
    //             System.out.println("clientSocket Closed .");
    //         } catch (IOException e) {
    //             e.printStackTrace();
    //         }
    //     }
    // }
}

