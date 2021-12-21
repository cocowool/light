// package cn.codelinks.light;

import java.io.*;
import java.net.*;

public class Server extends ServerSocket {
    public Server(int ServerPort) throws IOException {
        super(ServerPort);
        try {
            while(true){
                Socket socket = accept();
                new ServerThread(socket);
            }
        }catch( IOException e){
            e.printStackTrace();
        }finally {
            close();
        }

    }

    class ServerThread extends Thread {
        private Socket socket;
        private BufferedReader in;
        private PrintWriter out;

        public ServerThread(Socket s) throws IOException {
            this.socket = s;
            in = new BufferedReader(new InputStreamReader( socket.getInputStream(), "GB2312"));
            out = new PrintWriter(socket.getOutputStream(), true);
            start();
        }

        public void run(){
            try{
                while(true){
                    String line = in.readLine();
                    if("finish".equals(line)){
                        System.out.println("The server has stopped.");
                        break;
                    }
                    System.out.println("Data Received :" + line);
                    String msg = "'" + line + " ' sent to server.";
                    out.println(msg);
                    out.flush();
                }
                out.close();
                in.close();
                socket.close();
            }catch(IOException e){
                e.printStackTrace();
            }
        }

    }

    public static void main(String[] args) throws IOException {
        System.out.println("Server is running ...... ");
    }
}