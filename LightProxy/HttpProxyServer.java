import java.io.*;
import java.net.*;
import java.util.*;

public class HttpProxyServer {
    public static void main(String[] args) {
        int port = 8080; // HTTP 代理端口

        //@TODO 解析用户自定义的端口参数

        try {

            ServerSocket serverSocket = new ServerSocket(port);
            System.out.println("Http Proxy Server listen at : " + port);

            while (true) {
                    Socket socket = serverSocket.accept();
                    //设置服务端与客户端连接未活动超时时间
                    serverSocket.setSoTimeout(1000 * 60);

                    // TODO: 后续增加新开启线程处理功能 
                    InputStream inputStream = socket.getInputStream();

                    OutputStream outputStream = socket.getOutputStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(inputStream));

                    StringBuilder strBuilder = new StringBuilder();
                    String line = "";
                    int firstLine = 1;
                    String requestMethod = null;    //用于记录
                    String requestHost = "";
                    int requestPort = 80;

                    // 按行读取客户端发送的数据
                    while((line = br.readLine()) != null){
                        System.out.println("Client Send : " + line);

                        //判断请求的首行
                        if( firstLine == 1){
                            requestMethod = line.split(" ")[0];

                            System.out.println("Request method : " + requestMethod);
                            if( requestMethod == null)  continue;
                        }
                        firstLine++;

                        // 提取出 Host 地址
                        String[] strHost = line.split(": "); 
                        for(int i = 0; i < strHost.length; i++){
                            if(strHost[i].equalsIgnoreCase("host")){
                                requestHost = strHost[i+1];
                                System.out.println("Request host : " + requestHost);
                            }
                        }

                        if( line.isEmpty() ){
                            strBuilder.append("\r\n");
                            break;
                        }

                        // 将收到的请求加上换行保存起来，留作后面使用
                        strBuilder.append(line + "\r\n");
                        line = null;
                    }

                    System.out.println("Receive client request end . ");

                    if (requestHost.split(":").length > 1){
                        requestPort = Integer.valueOf(requestHost.split(":")[1]);
                    }

                    System.out.println("Proxy Request send and receive.");
                    System.out.println( "Request Host : " + requestHost);
                    System.out.println( "Request Port : " + requestPort);
                    Socket proxySocket = new Socket(requestHost, requestPort);
                    proxySocket.getOutputStream().write(line.toString().getBytes());

                    InputStream proxyInputStream = proxySocket.getInputStream();
                    byte[] socketBt = new byte[1024];
                    int socketlen = -1;
                    while( (socketlen = proxyInputStream.read(socketBt)) != -1 ){
                        System.out.println("Proxy received : " + (new String(socketBt, 0, socketlen)));
                        socket.getOutputStream().write(socketBt, 0, socketlen);
                    }
                    proxyInputStream.close();
            }

        }catch(Exception e){
            e.printStackTrace();
        }
    }
}

