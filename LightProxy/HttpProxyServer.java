import java.io.*;
import java.net.*;
import java.util.*;

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
            String requestHost = "";
            int requestPort = 80;
            boolean headersEnd = false;

            // 按行读取客户端发送的数据
            while((line = br.readLine()) != null){
                System.out.println("Client Send : " + line);

                if(!headersEnd){
                    request.append(line).append("\r\n");
                

                    //判断请求的首行
                    if( request.length() == 0){
                        String[] parts = line.split(" ");
                        if(parts.length > 2){
                            requestMethod = parts[0];
                            String url = parts[1];
                            if( url.startsWith("http://") || url.startsWith("https://") ){
                                URL parsedUrl = new URL(url);
                                requestHost = parsedUrl.getHost();
                                requestPort = parsedUrl.getPort() != -1 ? parsedUrl.getPort() : (parsedUrl.getProtocol().equals("http")?80:443);

                            }else{
                                //假设后续有 Host 头
                            }
                        }
                    }else if(line.isEmpty()){
                        headersEnd = true;
                    }else{
                        //解析其他头部
                        String[] headerParts = line.split(": ");
                        if( headerParts.length == 2 && headerParts[0].equalsIgnoreCase("Host") ){
                            String[] hostParts = headerParts[1].split(":");
                            requestHost = hostParts[0];
                            if( hostParts.length > 1){
                                requestPort = Integer.parseInt(hostParts[1]);
                            }
                        }
                    }
                }
            }
            

            try(
                Socket proxySocket = new Socket(requestHost, requestPort);
                InputStream proxyInput = proxySocket.getInputStream();
                OutputStream proxyOutput = proxySocket.getOutputStream();
            ){
                //请求转发到目标服务器
                proxyOutput.write(request.toString().getBytes());

                //转发响应回客户端
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = proxyInput.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
            }
        }catch(IOException e){
            e.printStackTrace();
        }
    }
}

