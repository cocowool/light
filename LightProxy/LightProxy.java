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
                    while( reader.ready()){
                        System.out.println(reader.readLine());
                    }
                }catch(IOException e){
                    e.printStackTrace();
                }
            }).start();
        }
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
