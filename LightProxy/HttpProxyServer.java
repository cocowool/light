// import java.io.*;
// import java.net.*;
// import java.util.concurrent.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;


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
public class HttpProxyServer implements Runnable {
    // private static final int THREAD_POOL_SIZE = 10;
    // private static final ExecutorService executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    private ServerSocket serverSocket;

    static ArrayList<Thread> servicingThreads;

    /**
     * 后续用作信号量
     */
    private volatile boolean running = true;

    public static void main(String[] args) {
        HttpProxyServer hps = new HttpProxyServer();
        hps.listen();
    }

    public void HttpProxyServer(){
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

                Thread thread = new Thread(() ->handleClientRequest(socket_client) );

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

    private static void handleRequest(Socket socket, String request) {
        // 处理请求并返回响应
        // ...
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
    public void run(){
        System.out.println("New thread is running ... ");
    }
}

