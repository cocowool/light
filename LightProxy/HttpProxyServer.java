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

                    InputStream inputStream = socket.getInputStream();

            }

        }catch(Exception e){
            e.printStackTrace();
        }
    }
}

