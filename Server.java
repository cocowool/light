// package cn.codelinks.light;

import java.io.*;
import java.net.*;

public class Server extends ServerSocket {
    public Server(int ServerPort) throws IOException {
        super(ServerPort);
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Server is running ...... ");
    }
}