package SocketThreads;

import java.util.UUID;

//A simple class that holds the SearchResults information including address of fulfiller, port of fulfiller, ID of
//fulfiller, and the files that match the search criteria that the fulfiller is in possession of
public class SearchResults {
    public String address;
    public int port;
    public UUID clientId;
    public String[] files;

    //Initialized with all the required information, which is all of it
    public SearchResults(String address, int port, UUID clientId, String[] files) {
        this.address = address;
        this.port = port;
        this.clientId =  clientId;
        this.files = files;
    }
}
