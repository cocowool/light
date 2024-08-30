import java.io.*;
import java.net.*;

public class HttpProxyServer {
    public static void main(String[] args) throws IOException {
        int port = 8080; // HTTP 代理端口
        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("HTTP proxy server listening on port " + port);

        while (true) {
            Socket clientSocket = serverSocket.accept();
            new Thread(new HttpHandler(clientSocket)).start();
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