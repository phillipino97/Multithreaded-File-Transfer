package SocketThreads;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.UUID;

import SocketThreads.Client;

public class Server extends Thread {
    private ServerSocket serverSocket;
    private final UUID serverId = UUID.randomUUID();
    private ClientHandler handler;

    public Server(int port) {
        //Server constructor for opening a client that does not initally connect to any other
        try {
            serverSocket = new ServerSocket(port);
            handler = new ClientHandler(serverId, serverSocket, "127.0.0.1", port);
            handler.start();
        } catch (IOException e) {
            System.out.println("Could not open the server socket for incoming requests!");
        }
    }

    public Server(int port, String connect_ip, int connect_port) {
        //Server constructor that opens the client socket and initially connects to another client immediately
        try {
            serverSocket = new ServerSocket(port);
            handler = new ClientHandler(serverId, serverSocket, "127.0.0.1", port);
            handler.start();
            handler.ConnectToClient(new Socket(connect_ip, connect_port), true, true);
        } catch (IOException e) {
            System.out.println("Could not open the server socket for incoming requests!");
        }
    }

    public ClientHandler GetClientHandler() {
        return handler;
    }

    public void CloseServer() throws IOException {
        handler.RemoveAllClients();
        serverSocket.close();
    }

    //The event loop for accepting incoming connections to the server
    public void run() {
        while(!serverSocket.isClosed()) {
            try {
                handler.AddClient(serverSocket.accept());
            } catch (IOException e) {
                System.out.println("Could not accept incoming connection!");
            }
        }
    }
}
