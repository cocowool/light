import java.io.*;
import java.net.*;
import java.util.*;

public class HttpProxyServer {
    private final ServerSocket server;
    private final int port;

    public HttpProxyServer(int port) throws IOException{
        this.port = port;
        server = new ServerSocket(port);
        System.out.println("HTTP代理端口" + port);
    }

    public static void main(String[] args) throws IOException {
        int port = 8080; // HTTP 代理端口

        //解析用户自定义的端口参数
        //@TODO

        new HttpProxyServer(port).start();

    }

    @Override
    public void run(){
        try{
            // ServerSocket sst = new ServerSocket(port);
            // System.out.println("HTTP proxy server listening on port " + port);

            while (true) {
                Socket clientSocket = server.accept();
                // 设置客户端与代理服务器的未活动超时时间
                clientSocket.setSoTimeout(1000*60);

                String line = "";
                InputStream inputStr = clientSocket.getInputStream();

                String tmpHost = "", host;
                int port = 80;
                String type = null;

                OutputStream outStr = clientSocket.getOutputStream();

                //使用线程处理收到的请求
                new Thread(new HttpHandler(clientSocket)).start();
            }    
    
        }catch(IOException e){
            e.printStackTrace();
        }

    }

}

class HttpHandler implements Runnable {
    private Socket clientSocket;

    public HttpHandler(Socket clientSocket) {
        this.clientSocket = clientSocket;
    }

    @Override
    public void run() {
        try (InputStream input = clientSocket.getInputStream();
             OutputStream output = clientSocket.getOutputStream()) {
            // 实现 HTTP 代理协议
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));
            String requestLine = reader.readLine();
            if (requestLine != null) {
                System.out.println("Received request: " + requestLine);
                // 实现 HTTP 代理逻辑
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}