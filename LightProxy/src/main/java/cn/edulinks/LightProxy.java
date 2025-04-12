package cn.edulinks;

import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.Map;

public class LightProxy implements Runnable {
    private ServerSocket serverSocket;
    static ArrayList<Thread> servicingThreads;

    /**
     * 后续用作信号量
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
                Thread thread = new Thread( () ->handleRequest(socket_client) );

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
     * @param socket_client
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
