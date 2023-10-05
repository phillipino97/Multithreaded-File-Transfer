import SocketThreads.ClientHandler;
import SocketThreads.SearchResults;
import SocketThreads.Server;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Scanner;

/**
 * Generates a basic P2P system which allows peers to upload and download
 * files from other peers.
 * This system can theoretically accept and infinite number of connections.
 * A peer connects to another peer initially and other peers can then connect to that peer, these are referred to as
 * "Permanent Connections" in this system. When a node requests data from a peer that is not its permanent peer it
 * then connects to that peer with a "Data Transfer Connection" which is destroyed upon file transfer completion
 * or failure.
 * When running this program please have two folders in the working directory. One labeled as "downloads" and one labeled
 * as "uploads". The "uploads" folder contains data that you would like to be searchable by other peers. The "downloads"
 * folder will contain files downloaded from other peers.
 * This program runs a Thread for user input, a Thread for accepting socket connections, a Thread for managing client
 * request processing and writing, and a Thread for managing client requests (one Thread per client).
 *
 * @param  args The commandline input to the program
 * @param args[0] The port that the peer would like to open for others to connect to
 * @param args[1] If args.length() == 3: The address of a peer on the network that you would like to initially
 *                connect to. This is shown as something like 192.168.1.34 which is host 192.168.1.34
 *                If args.length() == 1: This peer is the initial peer in the system and is therefore awaiting connections
 *                to itself.
 * @param args[2] If args.length() == 3: The port of a peer on the network that you would like to initially
 *                connect to. This is shown as something like 8556
 *                If args.length() == 1: This peer is the initial peer in the system and is therefore awaiting connections
 *                to itself.
 */
public class MultithreadedFileTransfer {
    public static void main(String[] args) {
        Server mainServer = null;
        ClientHandler clientHandler = null;
        //Checks whether the client is initially connecting to others or only accepting incoming connections
        if(args.length == 1) {
            //Only accepting incoming connections
            mainServer = new Server(Integer.parseInt(args[0]));
            mainServer.start();
            clientHandler = mainServer.GetClientHandler();
        } else if(args.length == 3) {
            //Initally connecting to another client
            mainServer = new Server(Integer.parseInt(args[0]), args[1], Integer.parseInt(args[2]));
            mainServer.start();
            clientHandler = mainServer.GetClientHandler();
        } else {
            System.out.println("Not enough arguments! try again and provide {port, connection_address, connection_port}");
            System.exit(1);
        }

        //If the clientHandler exists
        if(clientHandler != null) {
            String userInput = null;
            Scanner takeInput = new Scanner(System.in);
            //The user input event loop that searches for four keywords: exit, search, list, and download
            System.out.println("Enter \"search: {keyword}\" to search for a file");
            while(!Objects.equals(userInput, "exit")) {
                System.out.print("> ");
                userInput = takeInput.nextLine();
                if(userInput.toLowerCase().startsWith("search: ")) {
                    //Searching for a file
                    String searchTerm = userInput.toLowerCase().replace("search: ", "");
                    clientHandler.AddServerMessageToQueue(Integer.valueOf(-4).byteValue(), searchTerm);
                    System.out.println("Searching for " + searchTerm + " across the network...");
                    System.out.println("Enter \"list\" when you want to view the results of your search");
                } else if(userInput.equalsIgnoreCase("list")) {
                    //Listing the last search results
                    ArrayList<SearchResults> searchResults = clientHandler.GetSearchResults();
                    System.out.println("Files list currently obtained from last search:\n");
                    for(int i = 0; i < searchResults.size(); i++) {
                        System.out.printf("Peer Download ID: %3d - %15s:%-5d - %100s | File ID: %3d\n", i, searchResults.get(i).address, searchResults.get(i).port, searchResults.get(i).files[0], 0);
                        for(int j = 1; j < searchResults.get(i).files.length; j++) {
                            System.out.printf("%148s | File ID: %3d\n", searchResults.get(i).files[j], j);
                        }
                        System.out.println();
                    }
                    System.out.println("Download a file by typing download: PeerDownloadID:FileID");
                    System.out.println("e.g. \"download: 0:1\" downloads file with ID 1 from peer with download ID 0");
                } else if(userInput.toLowerCase().startsWith("download: ")) {
                    //Downloads a file using a peer download id and a file id from that peer
                    String[] downloadInfo = userInput.toLowerCase().replace("download: ", "").split(":");
                    try {
                        clientHandler.StartFileDownload(Integer.parseInt(downloadInfo[0]), Integer.parseInt(downloadInfo[1]));
                    } catch(IOException ignored) {

                    }
                }
            }
            try {
                //Closing the server
                mainServer.CloseServer();
            } catch(IOException ignored) {

            }
        } else {
            System.out.println("Couldn't start the server!");
            System.exit(2);
        }
    }
}
