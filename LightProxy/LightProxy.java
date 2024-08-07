import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class LightProxy {
    private  static final int PORT = 8989;

    public static void main(String[] args) throws IOException{
        //启动代理端的监听端口，接收用户的请求
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Server is listening on port " + PORT);

        while( true) {
            Socket accept = serverSocket.accept();
            System.out.println("received connection from " + accept.getInetAddress().getHostAddress());

            // new Thread( () -> handleRequest(clientSocket)).start();
            new Thread( () -> {

                
                // 接收客户端请求
                try(InputStream inputStream = accept.getInputStream() ){

                    byte[] bt = new byte[1024];
                    int len = inputStream.read(bt);
                    // 判断是否是 SOCKS5 请求
                    if( bt[0] != 0x05 ){
                        throw new IOException("Invalid socks5 handshake request!");
                    }

                    OutputStream outputStream = accept.getOutputStream();
                    // 向客户端请求返回确认为 SOCKS 协议，返回认证方式，0x00 表示不需要认证
                    // 认证方式: 0x00 不需要认证，0x01 GSSAPI 认证，0x02 用户名和密码方式认证，0x03 IANA认证，0x80-0xfe 保留的认证方式，0xff 不支持任何认证方式，客户端收到后需关闭链接
                    outputStream.write(new byte[] { 0x05,0x00 });

                    //验证认证方式，目前仅支持不需要认证的类型
                    len = inputStream.read(bt);
                    if( bt[0] != 0x05 || bt[1] != 0x01 || bt[2] != 0x00){
                        throw new IOException("Invalid socks5 request!");
                    }

                    String host = "baidu.com";
                    int port = 80;

                    System.out.println("Connection to " + host + " : " + port);

                    Socket sendServerSocket = new Socket(host, port);
                    InputStream serverIn = sendServerSocket.getInputStream();
                    OutputStream serverOut = sendServerSocket.getOutputStream();

                    byte[] response = new byte[len];
                    System.arraycopy(bt, 0, response, 0, len);
                    response[1] = 0x00;
                    outputStream.write(response);

                    //开始转发客户端发来的请求
                    new Thread(() -> {
                        try {
                            byte[] bt2 = new byte[1024];
                            int len2;
                            while((len2 = serverIn.read(bt2)) != -1){
                                outputStream.write(bt2,0,len2);
                            }                                
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }).start();

                    byte[] bt3 = new byte[1024];
                    int len3;
                    while( (len3 = inputStream.read(bt3)) != -1){
                        serverOut.write(bt3,0,len3);
                    }

                    // BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));

                    // StringBuilder sb = new StringBuilder();
                    // while( reader.ready()){
                    //     // sb = reader.readLine();
                    //     // System.out.println(reader.readLine());
                    //     sb.append(reader.readLine());
                    //     sb.append("\r\n");
                    // }


                    // System.out.println(sb.toString());
                    // String address = (String)getHeader(sb.toString())[0];
                    // String[] split = address.split(":");
                    // String host = split[0];
                    // int port = split.length > 1 ? Integer.parseInt(split[1]) : 80;

                    // System.out.println(sb.toString());

                    // //代理用户请求发往真正的服务端
                    // Socket socket = new Socket(host,port);


                    // boolean isFirstLine = true;
                    // int contentLength = -1;

                    // while ((len = inputStream.read(bt)) != -1){

                    //     if(socket == null){

                    //         if(isFirstLine){
                    //             isFirstLine = false;
                    //             String replace = replaceDomain(bt, len);
    
                    //             if(replace.startsWith("CONNECT")){
                    //                 len = -1;
                    //                 break;
                    //             }
                    //             sb.append(replace);
                    //         }else {
                    //             sb.append(new String(bt, 0, len));
                    //         }

                    //         // if(contentLength == -1){
                    //         //     Integer length = (Integer) getHeader(new String(bt,0,len));
                    //         //     if (length != null){
                    //         //         contentLength = length;
                    //         //     }
                    //         //     int crlfcrlf = findCRLFCRLF(bt,len) + 4;
                    //         //     contentLength -= ( len - crlfcrlf);
                    //         // }

                    //         if( contentLength == 0){
                    //             break;
                    //         }

                    //         if( sb.toString().endsWith("\r\n\r\n")){
                    //             break;
                    //         }
    
                    //         System.out.println("Ready to get data");
                    //     }else{
                    //         if(isFirstLine){
                    //             String replace = replaceDomain(bt, len);
                    //             socket.getOutputStream().write(replace.getBytes());
                    //         }else{
                    //             socket.getOutputStream().write(bt, 0, len);
                    //         }
                    //     }

                    // }

                    // // socket.getOutputStream().write(sb.toString().getBytes());

                    // Socket finalSocket = socket;
                    // new Thread(()->{
                    //     try {
                    //         InputStream socketInputStream = finalSocket.getInputStream();

                    //         byte[] socketBt = new byte[1024];
                    //         int socketlen = -1;


                    //         while((socketlen = socketInputStream.read(socketBt)) != -1){

                    //             System.out.println(new String(socketBt, 0, socketlen));
                    //             clientSocket.getOutputStream().write(socketBt, 0, socketlen);

                    //         }
                    //         socketInputStream.close();
                    //     } catch (Exception e) {
                    //         e.printStackTrace();
                    //     }finally{
                    //         try {
                    //             finalSocket.close();
                    //         }catch(IOException e){
                    //             e.printStackTrace();
                    //         }
                    //     }
                    // }).start();

                }catch(IOException e){
                    e.printStackTrace();
                }
            }).start();
        }

    }

    private  static String replaceDomain(byte[] bt, int len) {
        String line = new String(bt, 0, len, StandardCharsets.UTF_8);
        String reqUrl = line.split("\\s+")[1];

        int endIndex = reqUrl.indexOf("/", 8);
        String domain = endIndex != -1 ? reqUrl.substring(0, endIndex+1) : reqUrl;

        return line.replaceFirst(domain, "/");
    }

    private static String repalceConnection(String line) {
        String prefix = "Proxy-Connection: ";

        return line.replaceFirst(prefix, "Connection: ");
    }

    private static Object[] getHeader(String str) {
        String hostPrefix = "Host: ";
        String contentLengthPrefix = "Content-Length: ";

        String host = null;
        Integer contentLength = null;
        String[] line = str.split("\r\n");
        
        for (String l : line ){
            // System.out.println(l + "\n");
            if(l.startsWith(hostPrefix)){
                host = l.substring(hostPrefix.length());
            }else if ( l.startsWith(contentLengthPrefix)) {
                contentLength = Integer.parseInt(l.substring(contentLengthPrefix.length()));
            }

        }

        return new Object[]{host, contentLength};
        
    }

    // private static void handleRequest(Socket clientSocket) {
    //     try( BufferedReader in = new BufferedReader( new InputStreamReader(clientSocket.getInputStream()));
    //         PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true) ){
            
    //         String requestLine = in.readLine();
    //         System.out.println("Received request: " + requestLine );

    //         String targetUrl = "http://www.edulinks.cn";

    //         URL url = new URL(targetUrl);
    //         HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    //         connection.setRequestMethod("GET");

    //         BufferedReader responseReader = new BufferedReader( new InputStreamReader(connection.getInputStream()));
    //         String line;
    //         StringBuider response = new StringBuilder();

    //         while((line = responseReader.readLine()) != null){
    //             response.append(line).append("\n");
    //         }

    //         out.println(reponse.toString());

    //         responseReader.close();
    //         connection.disconnect();

    //     }catch(IOException e){
    //         e.printStackTrace();
    //     }finally{
    //         try {
    //             clientSocket.close();
    //         }catch(IOException e){
    //             e.printStackTrace();
    //         }
    //     }
    // }
}
