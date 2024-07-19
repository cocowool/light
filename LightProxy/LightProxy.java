import java.io.*;
import java.net.*;

public class LightProxy {
    private  static final int PORT = 8989;

    public static void main(String[] args) throws IOException{
        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("Server is listening on port " + PORT);

        while( true) {
            Socket clientSocket = serverSocket.accept();
            System.out.println("received connection from " + clientSocket.getInetAddress().getHostAddress());

            // new Thread( () -> handleRequest(clientSocket)).start();
            new Thread( () -> {
                try(InputStream inputStream = clientSocket.getInputStream() ){
                    BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
                    StringBuilder sb = new StringBuilder();
                    byte[] bt = new byte[1024];
                    int len = -1;
                    boolean isFirstLine = true;
                    int contentLength = -1;

                    while ((len = inputStream.read(bt)) != -1){
                        if(isFirstLine){
                            isFirstLine = false;
                            String replace = replaceDomain(bt, len);

                            if(replace.startsWith("CONNECT")){
                                len = -1;
                                break;
                            }
                            sb.append(replace);
                        }else {
                            sb.append(new String(bt, 0, len));
                        }
                    }

                    while( reader.ready()){
                        // sb = reader.readLine();
                        // System.out.println(reader.readLine());
                        sb.append(reader.readLine());
                        sb.append("\r\n");
                    }


                    // System.out.println(sb.toString());
                    String address = (String)getHeader(sb.toString())[0];
                    String[] split = address.split(":");
                    String host = split[0];
                    int port = split.length > 1 ? Integer.parseInt(split[1]) : 80;

                    System.out.println(sb.toString());

                    Socket socket = new Socket(host,port);
                    socket.getOutputStream().write(sb.toString().getBytes());

                    Socket finalSocket = socket;
                    new Thread(()->{
                        try {
                            InputStream socketInputStream = finalSocket.getInputStream();

                            byte[] socketBt = new byte[1024];
                            int socketlen = -1;


                            while((socketlen = socketInputStream.read(socketBt)) != -1){


                                System.out.println(new String(socketBt, 0, socketlen));
                                clientSocket.getOutputStream().write(socketBt, 0, socketlen);
                            }
                            socketInputStream.close();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }finally{
                            try {
                                finalSocket.close();
                            }catch(IOException e){
                                e.printStackTrace();
                            }
                        }
                    }).start();
                }catch(IOException e){
                    e.printStackTrace();
                }
            }).start();
        }

    }

    private  static String replaceDomain(String line) {
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
