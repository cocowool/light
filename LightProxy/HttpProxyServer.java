import java.io.*;
import java.net.*;
import java.util.*;

/**
 * 
 * 2024-11-14 对于telnet发起的简单http请求能够正常返回，不支持 postman 请求
 * 
 * 问题：
 * 1. 不能持续响应一个客户端的连续请求
 */
public class HttpProxyServer {
    public static void main(String[] args) {
        int port = 8080; // HTTP 代理端口

        //@TODO 解析用户自定义的端口参数

        try ( ServerSocket serverSocket = new ServerSocket(port) ) {
            //设置服务端与客户端连接未活动超时时间
            serverSocket.setSoTimeout(1000 * 60);
            System.out.println("Http Proxy Server listen at : " + port);

            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    // 新开启线程处理用户端发来的请求
                    new Thread( () -> handleClient(socket) ).start();
                }catch(IOException e){
                    e.printStackTrace();
                }
            }

        }catch(Exception e){
            e.printStackTrace();
        }
    }

    private static void handleClient(Socket clientSocket){
        try(
            InputStream inputStream = clientSocket.getInputStream();
            OutputStream outputStream = clientSocket.getOutputStream();
            BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));
            PrintWriter writer = new PrintWriter(outputStream, true)
        ){
            StringBuilder request = new StringBuilder();
            String line = "";
            String requestMethod = null;    //用于记录
            String requestPath = "/";
            String requestProtocol = "HTTP/1.1";
            String requestHost = "";
            int requestPort = 80;
            boolean headersEnd = false;
            boolean hasBody = false;
            long contentLength = -1;

            // 按行读取客户端发送的数据
            while(( line = br.readLine()) != null ){
                System.out.println("Client Send : " + line);

                System.out.println("headersEnd = " + headersEnd);
                if(!headersEnd){
                    //判断请求的首行
                    if( request.length() == 0){
                        System.out.println(line);
                        String[] parts = line.split(" ");                        
                        if(parts.length >= 2){
                            requestMethod = parts[0];
                            if(parts.length > 2){
                                requestPath = parts[1];
                            }else{
                                // No full URL, we'll rely on Host header
                            }
                            if (parts.length > 2 && parts[2].startsWith("HTTP/")) {
                                requestProtocol = parts[2];
                            }
                        }
                    }else if(line.isEmpty()){
                        System.out.println("Header is end .");
                        headersEnd = true;
                        request.append(line).append("\r\n");
                        break;
                    }else{
                        //解析其他头部
                        String[] headerParts = line.split(": ");
                        if (headerParts.length == 2) {
                            switch (headerParts[0].toLowerCase()) {
                                case "host":
                                    String[] hostParts = headerParts[1].split(":");
                                    requestHost = hostParts[0];
                                    if (hostParts.length > 1) {
                                        requestPort = Integer.parseInt(hostParts[1]);
                                    }
                                    break;
                                case "content-length":
                                    contentLength = Long.parseLong(headerParts[1]);
                                    hasBody = contentLength > 0;
                                    break;
                            }
                        }
                    }
                }else{
                    System.out.println("Break while .");
                    break;
                }

                System.out.println("Next Line ...");
                request.append(line).append("\r\n");
                System.out.println("Current request : " + request);
            }
            

            try(
                Socket proxySocket = new Socket(requestHost, requestPort);
                InputStream proxyInput = proxySocket.getInputStream();
                OutputStream proxyOutput = proxySocket.getOutputStream();
            ){
                String proxyRequestLine = requestMethod + " " + requestPath + " " + requestProtocol + "\r\n";
                System.out.println("Send Request .... ");
                System.out.println(proxyRequestLine);

                proxyOutput.write(proxyRequestLine.getBytes());

                // Forward headers
                proxyOutput.write(request.toString().getBytes());

                // Forward request body if present
                if (hasBody) {
                    byte[] bodyBuffer = new byte[(int) contentLength];
                    int bytesRead = inputStream.read(bodyBuffer);
                    if (bytesRead == contentLength) {
                        proxyOutput.write(bodyBuffer);
                    } else {
                        // Handle error or incomplete read
                    }
                }

                // Forward response from proxy to client
                byte[] buffer = new byte[4096];
                int responseBytesRead;
                while ((responseBytesRead = proxyInput.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, responseBytesRead);
                }

                //处理后关闭相关的流
                proxySocket.close();
            }

            clientSocket.close();
        }catch(IOException e){
            e.printStackTrace();
        }
    }
}

