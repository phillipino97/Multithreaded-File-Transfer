package SocketThreads;

import IOThreads.FileListUpdater;

import java.io.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/*
 * Request and response codes
 * if indicator >= 0 then it is a response to a request or a notice
 * if indicator < 0 then it is a request
 *
 * if indicator = (-4, -5) then it is a file search request
 *   -4 -> propagate the search to your peers and append your ID to the request so that it can be sent back to the
 *         original searcher
 *       This request has another indicator value of i8 size denoting the length of the propagation history this value
 *       should be secondIndicator minimum = 16, and maximum = 112. secondIndicator = 96 then do not propagate the
 *       request anymore, search your files for the search term and return the results as an
 *       indicator -5 to the requester, removing 16 from the secondIndicator and the last 16 bytes from the list
 *   -5 -> propagation is returning with search data, send to the peer ID that is next in the list
 *       This request has another indicator value of i8 size denoting the length of the propagation history this value
 *       should be secondIndicator = minimum 16, and maximum = 112. Get the last 16 bytes from the propagation history
 *       and search your clientId's to find one that matches. If you do then send the results to them removing the last
 *       16 bytes from the propagation history and delimiting secondIndicator by 16
 * if indicator = -6 then it is a file download request
 *   The following 16 bytes denote a requestId and all other bytes are the filename
 * if indicator = -128 then it is the end of initial transaction containing node information and intentions
 *
 * if indicator = 0 then it is a response containing the node ID
 * if indicator = (1, 2) then the node is notifying intentions
 *   1 -> the node is requesting permanent entry
 *   2 -> the node is requesting to download data
 * if indicator = 3 then the node is accepting the request for connection
 * if indicator = 4 then the node is denying the request for connection
 * if indicator = (5, 6) it is a response for an inheritance node if this node were to go offline
 *   5 -> this servers primary connected socket
 *   6 -> the requester is the primary connected socket and there are no other connected clients
 * if indicator = 7 then it is a notice that the current node is leaving the system and should be disconnected
 *   if the sender of this message is the primary node then the node must create a new primary node and send notice
 *   to all of its peers of the change
 * if indicator = (9, 10) then this response is in relation to a file download request
 *   9 -> File request can be completed and is accompanied by 16 bytes denoting the requestId and also sends the file
 *        size, followed by a number of responses labeled with an 11 indicator until the file is streamed
 *   10 -> The file request could not be completed due to a number of reasons this is followed by 16 bytes denoting
 *         the requestId so that the host can terminate the request
 * if indicator = 11 then this response contains parts of a file requested
 *   The following 16 bytes denote the requestId and all other bytes are file data
 * if indicator = 12 then it is a notice that all data has been sent, this is followed by 16 bytes denoting
 *         the requestId
 * if indicator = 13 then it is a notice that all data has been received
 * if indicator = 14 then it is a notice of the address and port number
 */

//A structure containing messages to be sent to peers, containing the peerID, the indicator byte, and the actual message
class ClientMessage {
    public UUID clientId;
    public byte indicatorByte;
    public byte[] message;
    public ClientMessage(UUID clientId, byte indicatorByte, String message) {
        this.clientId = clientId;
        this.indicatorByte = indicatorByte;
        this.message = message.getBytes();
    }

    public ClientMessage(UUID clientId, byte indicatorByte, byte[] message) {
        this.clientId = clientId;
        this.indicatorByte = indicatorByte;
        this.message = message;
    }
}

//A server message structure intended to be sent from this peer to others, no need for a peerID
class ServerMessage {
    public byte indicatorByte;
    public byte[] message;
    public ServerMessage(byte indicatorByte, String message) {
        this.message = message.getBytes();
        this.indicatorByte = indicatorByte;
    }
}

//File Request information structure containing the file name, the file size, how much data has been received so far, and a
//OutputStream meant to write data to a file
class FileRequest {
    public String fileName;
    public long fileSize;
    public long receivedData = 0;
    public OutputStream fileOut = null;
    public FileRequest(String fileName) {
        this.fileName = fileName;
    }
}

//A Structure containing information on the client and whether it is permanent or not
class ClientInfo {
    Client client;
    boolean isPermanent;
    public ClientInfo(Client client, boolean isPermanent) {
        this.client = client;
        this.isPermanent = isPermanent;
    }
}

//A small utilities class that converts peerIDs to bytes and back to UUIDs
class UUIDUtils {
    public static UUID AsUUID(byte[] bytes) {
        ByteBuffer bb = ByteBuffer.wrap(bytes);
        long firstLong = bb.getLong();
        long secondLong = bb.getLong();
        return new UUID(firstLong, secondLong);
    }

    public static byte[] AsBytes(UUID uuid) {
        ByteBuffer bb = ByteBuffer.wrap(new byte[16]);
        bb.putLong(uuid.getMostSignificantBits());
        bb.putLong(uuid.getLeastSignificantBits());
        return bb.array();
    }
}

public class ClientHandler extends Thread {
    //This contains the permanent clients list or clients which are constantly connected to this host
    private final ArrayList<Client> permanentClients = new ArrayList<>();
    //This contains the data transfer clients list or clients which are temporary and are transferring from or to
    //this host
    private final ArrayList<Client> dataTransferClients = new ArrayList<>();
    //This is a list containing the messages that are queued and getting ready to be sent to other peers
    private final ArrayList<ClientMessage> queuedMessages = new ArrayList<>();
    //This is a list containing the information relating to the previous search request from this host
    private final ArrayList<SearchResults> searchResults = new ArrayList<>();
    //This is a HashMap containing the current File Requests originating from this host
    private final HashMap<UUID, FileRequest> fileRequests = new HashMap<>();
    //This is the peerID of the current host
    private final UUID serverId;
    //This host's server socket information
    private final ServerSocket serverSocket;
    //This host's address
    private final String address;
    //This host's port
    private final int port;

    //constructor which initializes some immediately necessary information like ID, server socket, address, and port
    public ClientHandler(UUID serverId, ServerSocket serverSocket, String address, int port) throws IOException {
        this.serverId = serverId;
        this.serverSocket = serverSocket;
        this.address = address;
        this.port = port;
    }

    /*
     * This method creates a connection to another host and takes that host's socket, whether the host is this host's
     * primary socket (or the socket that this host will forward other peers to if this host suddenly disconnects),
     * and whether this is a permanent connection or a data transfer connection (this is determined by the peer that
     * is doing the connecting).
     */
    private ClientInfo InitializeConnectToClients(Socket clientSocket, boolean isPrimarySocket, boolean isPermanentConnection) throws IOException {
        Client tempClient = new Client(clientSocket, this, isPrimarySocket);
        boolean accepted = false;

        DataOutputStream dataOut = new DataOutputStream(clientSocket.getOutputStream());
        DataInputStream dataIn = new DataInputStream(clientSocket.getInputStream());

        //Writes the ID of this host to the new peer
        dataOut.writeByte(0);
        dataOut.writeUTF(serverId.toString());
        dataOut.flush();

        //If it is a permanent connection then it sends a 1 indicator, if it is a data transfer connection then it sends
        //a 2 indicator
        if(isPermanentConnection) {
            dataOut.writeByte(1);
        } else {
            dataOut.writeByte(2);
        }
        dataOut.flush();

        //Sends the address and port of this host to the connection
        dataOut.writeByte(14);
        dataOut.writeUTF(this.address + ":" + this.port);
        dataOut.flush();

        //Sends the termination byte since it has sent all the necessary info
        dataOut.writeByte(-127);
        dataOut.flush();

        boolean done = false;
        while(!done) {
            //Reads the bytes coming from the peer
            byte messageType = dataIn.readByte();

            switch(messageType)
            {
                //If it is a ID indicator then it saves the ID of the peer
                case 0:
                    tempClient.clientId = UUID.fromString(dataIn.readUTF());
                    break;
                //If the peer was accepted
                case 3:
                    accepted = true;
                    break;
                //If the peer was rejected (currently not setup)
                case 4:
                    break;
                //This is the indicator of the next address in case this peer disconnects suddenly
                case 5:
                    String addressAndPort = dataIn.readUTF();
                    String address = addressAndPort.replace("/", "").split(":")[0];
                    int port = Integer.parseInt(addressAndPort.split(":")[1]);
                    tempClient.nextClientAddress = new InetSocketAddress(address, port);
                    break;
                //This is the response if this host is the next address in which case it does not matter
                case 6:
                    dataIn.readUTF();
                    break;
                //This sets the client address for the host that it is connecting to in order to send it to other peers
                //In case of disconnect events
                case 14:
                    String addressAndPort1 = dataIn.readUTF();
                    String address1 = addressAndPort1.replace("/", "").split(":")[0];
                    int port1 = Integer.parseInt(addressAndPort1.split(":")[1]);
                    tempClient.thisClientAddress = new InetSocketAddress(address1, port1);
                    break;
                //The termination byte
                case -127:
                    done = true;
            }
        }

        //Returns the new client information or informs the host that it could not create the connection
        //or it was not accepted
        if(accepted) {
            return new ClientInfo(tempClient, isPermanentConnection);
        } else {
            System.out.println("Connection was not accepted by host peer");
        }
        return null;
    }

    //This is a method that handles peers attempting to connect to this peer
    private ClientInfo InitializeAddedClients(Socket clientSocket) throws IOException {
        //Checks if this host currently has an assigned primary node (failover node) and if not, assigns this node
        //as its failover (if it is not a data transfer client)
        boolean hasPrimarySocket = false;
        for(Client permanentClient : permanentClients) {
            if(permanentClient.isPrimarySocket) {
                if(!permanentClient.clientSocket.isClosed() && permanentClient.clientSocket.isConnected()) {
                    hasPrimarySocket = true;
                }
                break;
            }
        }
        Client tempClient = new Client(clientSocket, this, false);
        DataOutputStream dataOut = new DataOutputStream(clientSocket.getOutputStream());
        DataInputStream dataIn = new DataInputStream(clientSocket.getInputStream());

        //Begins transmission with this host ID
        dataOut.writeByte(0);
        dataOut.writeUTF(serverId.toString());
        dataOut.flush();

        boolean isPermanent = false;
        boolean done = false;
        while(!done) {
            byte messageType = dataIn.readByte();

            switch(messageType)
            {
                //Retrieves the connecting clients ID
                case 0:
                    tempClient.clientId = UUID.fromString(dataIn.readUTF());
                    break;
                //This peer is looking to be a permanent client, so we accept it and send all the forwarding information
                //that we currently have then send the termination byte
                case 1:
                    dataOut.writeByte(3);
                    dataOut.flush();
                    ServerMessage serverMessage = GeneratePrimaryConnectedSocketMessage(tempClient);
                    dataOut.writeByte(serverMessage.indicatorByte);
                    dataOut.writeUTF(new String(serverMessage.message));
                    dataOut.flush();
                    dataOut.writeByte(14);
                    dataOut.writeUTF(address + ":" + port);
                    dataOut.flush();
                    dataOut.writeByte(-127);
                    dataOut.flush();
                    tempClient.isPrimarySocket = !hasPrimarySocket;
                    isPermanent = true;
                    break;
                //This is a data transfer peer so no need to send it forwarding information, simply accept it and send
                //the termination byte
                case 2:
                    dataOut.writeByte(3);
                    dataOut.flush();
                    dataOut.writeByte(-127);
                    dataOut.flush();
                    break;
                //The connecting peer is sending us their address information so we save it
                case 14:
                    String addressAndPort = dataIn.readUTF();
                    String address = addressAndPort.replace("/", "").split(":")[0];
                    int port = Integer.parseInt(addressAndPort.split(":")[1]);
                    tempClient.thisClientAddress = new InetSocketAddress(address, port);
                    break;
                //Termination byte received
                case -127:
                    done = true;
            }
        }

        return new ClientInfo(tempClient, isPermanent);
    }

    //Handler function that manages connecting to other peers and adds the peer to the permanent client or data transfer
    //client list so that it can send appropriate data and requests
    public Client ConnectToClient(Socket clientSocket, boolean isPrimarySocket, boolean isPermanentConnection) throws IOException {
        ClientInfo clientInfo = InitializeConnectToClients(clientSocket, isPrimarySocket, isPermanentConnection);
        if(clientInfo != null) {
            if(clientInfo.isPermanent) {
                permanentClients.add(clientInfo.client);
                permanentClients.get(permanentClients.size() - 1).StartThreads();
            } else {
                dataTransferClients.add(clientInfo.client);
                dataTransferClients.get(dataTransferClients.size() - 1).StartThreads();
            }
            return clientInfo.client;
        }
       return null;
    }

    //Handler function that manages other peers connecting to this host and adds them to the permanent client or data
    //transfer client lists so that it can send appropriate data and requests
    public void AddClient(Socket clientSocket) throws IOException {
        ClientInfo clientInfo = InitializeAddedClients(clientSocket);
        if(clientInfo != null) {
            if(clientInfo.isPermanent) {
                for(Client permanentClient : permanentClients) {
                    if(permanentClient.clientId.compareTo(clientInfo.client.clientId) == 0) {
                        SendOne(clientInfo.client.clientId, new ClientMessage(serverId, (byte) 7, "Client exiting".getBytes()));
                        if(!clientInfo.client.clientSocket.isClosed()) {
                            clientInfo.client.clientSocket.close();
                            clientInfo.client.Close();
                        }
                        return;
                    }
                }
                permanentClients.add(clientInfo.client);
                permanentClients.get(permanentClients.size() - 1).StartThreads();
            } else {
                dataTransferClients.add(clientInfo.client);
                dataTransferClients.get(dataTransferClients.size() - 1).StartThreads();
            }
            if(clientInfo.client.isPrimarySocket) {
                SendAllExcept(clientInfo.client.clientId, new ClientMessage(serverId, (byte) 5, (clientInfo.client.thisClientAddress.getAddress() + ":" + clientInfo.client.thisClientAddress.getPort()).getBytes()));
            }
        }
    }

    //The function used to remove a client that has disconnected from this host by their peerID
    public void RemoveClient(UUID clientId) throws IOException {
        for(int i = 0; i < permanentClients.size(); i++) {
            if(permanentClients.get(i).clientId == clientId) {
                //Closing all the connections
                if(!permanentClients.get(i).clientSocket.isClosed()) {
                    permanentClients.get(i).clientSocket.close();
                    permanentClients.get(i).Close();
                }
                //Checking whether this peer was previously the primary peer of this host. If it was, and it does NOT
                //have a next client address then that means that this host was its forwarding peer. This means that
                //any new peers will simply connect to us, and we don't need to worry. If it DOES have a next client address
                //that means that we need to connect to that new address and set it as our new forwarding address which
                //we send to all of our connected peers excluding the new one.
                Client newPrimaryInfo;
                if(permanentClients.get(i).isPrimarySocket && permanentClients.get(i).nextClientAddress != null) {
                    newPrimaryInfo = ConnectToClient(new Socket(permanentClients.get(i).nextClientAddress.getAddress(), permanentClients.get(i).nextClientAddress.getPort()), true, true);
                    SendAllExcept(newPrimaryInfo.clientId, new ClientMessage(serverId, (byte) 5, (newPrimaryInfo.thisClientAddress.getAddress() + ":" + newPrimaryInfo.thisClientAddress.getPort()).getBytes()));
                } else if(permanentClients.get(i).isPrimarySocket) {
                    //If the primary socket exits without a forwarding address it means that we need to simply choose one
                    //at random and notify all other peers
                    if(permanentClients.size() > 1) {
                        if(i == 0) {
                            newPrimaryInfo = permanentClients.get(1);
                        } else {
                            newPrimaryInfo = permanentClients.get(0);
                        }
                        SendAllExcept(newPrimaryInfo.clientId, new ClientMessage(serverId, (byte) 5, (newPrimaryInfo.thisClientAddress.getAddress() + ":" + newPrimaryInfo.thisClientAddress.getPort()).getBytes()));
                    }
                }
                //Finally removes the peer from the permanent clients list
                permanentClients.remove(i);
                break;
            }
        }
    }

    //Removing a client which is initiated by this host rather than a different peer
    public void RemoveClientOrigin(UUID clientId) throws IOException {
        for(int i = 0; i < permanentClients.size(); i++) {
            if(permanentClients.get(i).clientId == clientId) {
                if(!permanentClients.get(i).clientSocket.isClosed()) {
                    //Sends the disconnect notification to the peer in order to provide a cleaner exit
                    DataOutputStream dataOut = new DataOutputStream(permanentClients.get(i).clientSocket.getOutputStream());
                    dataOut.writeByte(7);
                    dataOut.flush();
                    //Closing all the connections
                    dataOut.close();
                    permanentClients.get(i).clientSocket.close();
                    permanentClients.get(i).Close();
                }
                //Removing the peer from the list
                permanentClients.remove(i);
                break;
            }
        }
    }

    //The peer requests removal from the host or has disconnected unexpectedly, so we remove them
    public void RemoveDataTransferClient(UUID clientId) throws IOException {
        for(int i = 0; i < dataTransferClients.size(); i++) {
            if(dataTransferClients.get(i).clientId == clientId) {
                //Closing all the connections
                if(!dataTransferClients.get(i).clientSocket.isClosed()) {
                    dataTransferClients.get(i).clientSocket.close();
                    permanentClients.get(i).Close();
                }
                //Removing the peer from the list
                dataTransferClients.remove(i);
                break;
            }
        }
    }

    //The host is disconnecting itself from the peer
    public void RemoveDataTransferClientOrigin(UUID clientId) throws IOException {
        for(int i = 0; i < dataTransferClients.size(); i++) {
            if(dataTransferClients.get(i).clientId == clientId) {
                if(!dataTransferClients.get(i).clientSocket.isClosed()) {
                    //Sends the disconnect notification to the peer in order to provide a cleaner exit
                    DataOutputStream dataOut = new DataOutputStream(dataTransferClients.get(i).clientSocket.getOutputStream());
                    dataOut.writeByte(7);
                    dataOut.flush();
                    //Closing all the connections
                    dataOut.close();
                    dataTransferClients.get(i).clientSocket.close();
                    permanentClients.get(i).Close();
                }
                //Removing from the data transfer list
                dataTransferClients.remove(i);
                break;
            }
        }
    }

    //Method for removing all permanent and data transfer clients, runs the origin methods since it is initiated
    //by this host
    public void RemoveAllClients() throws IOException {
        while(!permanentClients.isEmpty()) {
            RemoveClientOrigin(permanentClients.get(0).clientId);
        }
        while(!dataTransferClients.isEmpty()) {
            RemoveDataTransferClientOrigin(dataTransferClients.get(0).clientId);
        }
    }

    //Adding a server message to the queued messages list (mainly used from the user input)
    public void AddServerMessageToQueue(byte indicatorByte, String message) {
        queuedMessages.add(new ClientMessage(serverId, indicatorByte, message));
    }

    //Adding a client message to the queued messages list
    public void AddMessageToQueue(UUID clientId, byte indicatorByte, byte[] message) {
        queuedMessages.add(new ClientMessage(clientId, indicatorByte, message));
    }

    //Retrieves the search results as a public method so that the command line can display them
    public ArrayList<SearchResults> GetSearchResults() {
        return searchResults;
    }

    //Send to all peers excluding one peer
    private void SendAllExcept(UUID clientId, ClientMessage clientMessage) {
        for(Client permanentClient : permanentClients) {
            if(permanentClient.clientId.compareTo(clientId) != 0) {
                permanentClient.Send(clientMessage.indicatorByte, clientMessage.message);
            }
        }
    }

    //Send to all peers with no exclusions
    private void SendAll(ClientMessage clientMessage) {
        for(Client permanentClient : permanentClients) {
            permanentClient.Send(clientMessage.indicatorByte, clientMessage.message);
        }
    }

    //Send to a single peer
    private void SendOne(UUID clientId, ClientMessage clientMessage) {
        boolean sent = false;
        for(Client permanentClient : permanentClients) {
            if(permanentClient.clientId.compareTo(clientId) == 0) {
                permanentClient.Send(clientMessage.indicatorByte, clientMessage.message);
                sent = true;
                break;
            }
        }
        //Checks whether the message was already sent to a permanent client, meaning that the requested peer is not
        //a data transfer client
        if(!sent) {
            for(Client dataTransferClient : dataTransferClients) {
                if(dataTransferClient.clientId.compareTo(clientId) == 0) {
                    dataTransferClient.Send(clientMessage.indicatorByte, clientMessage.message);
                    break;
                }
            }
        }
    }

    //The function to initiate a file download
    public void StartFileDownload(int searchListID, int fileId) throws IOException {
        //Gets the client information and the file information from the search results
        UUID clientId = searchResults.get(searchListID).clientId;
        String file = searchResults.get(searchListID).files[fileId];
        //Generates a request ID
        UUID newFileRequestId = UUID.randomUUID();
        //Gets the address and port information from the search results structure
        String address = searchResults.get(searchListID).address;
        int port = searchResults.get(searchListID).port;

        //Creates a new file request
        fileRequests.put(newFileRequestId, new FileRequest(file));

        //Checks whether the peer which has the requested data is already a permanent client of this host in which
        //case no data transfer connection is needed and all data can flow over the permanent client connection
        boolean currentlyConnectedToPeer = false;
        for(Client permanentClient : permanentClients) {
            if(permanentClient.clientId.compareTo(clientId) == 0) {
                currentlyConnectedToPeer = true;
            }
        }

        //Generates the message information including the requestID and file name
        byte[] requestMessage = new byte[16 + file.length()];
        byte[] requestIdAsBytes = UUIDUtils.AsBytes(newFileRequestId);
        byte[] fileNameAsBytes = file.getBytes();
        for(int i = 0; i < requestIdAsBytes.length; i++) {
            requestMessage[i] = requestIdAsBytes[i];
        }
        for(int i = 0; i < fileNameAsBytes.length; i++) {
            requestMessage[i + requestIdAsBytes.length] = fileNameAsBytes[i];
        }

        if(currentlyConnectedToPeer) {
            //If it is a permanent connection then simply send the file download request indicator with the request
            SendOne(clientId, new ClientMessage(serverId, (byte) -6, requestMessage));
        } else {
            //If it is a data transfer connection then connect to the peer then send them the file download request
            ConnectToClient(new Socket(address, port), false, false);
            SendOne(clientId, new ClientMessage(serverId, (byte) -6, requestMessage));
        }
    }

    //The sender of a file receives this information from the above request
    public void SendFile(Client client, byte[] requestIdAsBytes, byte[] fileNameAsBytes) throws IOException {
        //Request and file information
        String fileName = new String(fileNameAsBytes);
        InputStream fileIn;
        File sendFile;

        try {
            //Attempts to open the file and check if it exists, if not then it sends a failure message to the peer
            String currentPath = new java.io.File(".").getCanonicalPath().replace("\\", "/");
            sendFile = new File(currentPath + "/uploads/" + fileName);
            if(!sendFile.exists()) {
                throw new IOException();
            }
            //If file exists then initiate the File Input Stream
            fileIn = new FileInputStream(sendFile);
        } catch (IOException ignored) {
            //File could not be opened or does not exist so tell the connected peer
            SendOne(client.clientId, new ClientMessage(serverId, (byte) 10, requestIdAsBytes));
            return;
        }

        //Retrieve and send the requestId and the file size so the peer knows how much data it is downloading
        byte[] fileSize = ConvertLongToBytes(sendFile.length());
        byte[] idAndSizeAsBytes = new byte[fileSize.length + 16];
        for(int i = 0; i < requestIdAsBytes.length; i++) {
            idAndSizeAsBytes[i] = requestIdAsBytes[i];
        }
        for(int i = 0; i < fileSize.length; i++) {
            idAndSizeAsBytes[i + requestIdAsBytes.length] = fileSize[i];
        }

        SendOne(client.clientId, new ClientMessage(serverId, (byte) 9, idAndSizeAsBytes));

        //Send the actual file data to the peer
        int count;
        byte[] buffer = new byte[8175];
        while ((count = fileIn.read(buffer)) > 0)
        {
            client.SendFileChunk(requestIdAsBytes, buffer, count);
        }

        //Send the indicator and requestId for a completed file request upload
        SendOne(client.clientId, new ClientMessage(serverId, (byte) 12, requestIdAsBytes));
    }

    //The method that accepts the requestId and file data chunk
    public void ReceiveFileChunk(byte[] requestIdAsBytes, byte[] data) throws IOException {
        //Sets the requestId and gets the file request information from it
        UUID requestId = UUIDUtils.AsUUID(requestIdAsBytes);
        FileRequest request = fileRequests.get(requestId);

        //If the file output stream has not yet been initialized
        if(request.fileOut == null) {
            //Set the file information
            String filePath = new java.io.File(".").getCanonicalPath().replace("\\", "/") + "/downloads/" + request.fileName;
            File file = new File(filePath);
            //Check if the file exists
            if(file.exists()) {
                //If the file exists then append the request ID to the end of it in order to create a unique file name
                if(request.fileName.contains(".")) {
                    //Checks if the file has an extension or not (.pdf, .jpg, etc.) if so then append the request ID
                    //before the extension
                    request.fileName = request.fileName.split("\\.")[0] + request.toString() + "." + request.fileName.split("\\.")[1];
                } else {
                    //If the file does not have an extension then simply append the request ID to the end
                    request.fileName = request.fileName + "-" + request.toString();
                }
                //Generate the new path and create the file
                filePath = new java.io.File(".").getCanonicalPath().replace("\\", "/") + "/downloads/" + request.fileName;
                file = new File(filePath);
                file.createNewFile();
            } else {
                //The file does not currently exist so create a new one
                file.createNewFile();
            }
            //Create the file output stream
            request.fileOut = new FileOutputStream(filePath);
        }

        //Write the data sent from the uploader to the new file
        request.fileOut.write(data);
        request.receivedData += data.length;
        System.out.printf("Received %.2f%% of the data\n", (double) ((double) request.receivedData / (double) request.fileSize) * 100.0);
    }

    //Generate the next peer server message which is meant to keep the structure in place in case of a disconnect from
    //this host
    private ServerMessage GeneratePrimaryConnectedSocketMessage(Client excludeClient) {
        for (Client permanentClient : permanentClients) {
            if (permanentClient.clientId != excludeClient.clientId && permanentClient.isPrimarySocket) {
                //The requesting peer is NOT this host's primary connection so send the primary connection information to
                //them
                return new ServerMessage(Integer.valueOf(5).byteValue(), permanentClient.thisClientAddress.getAddress() + ":" + permanentClient.thisClientAddress.getPort());
            }
        }
        //This peer has no other connections or the requester already is the primary peer of this
        return new ServerMessage(Integer.valueOf(6).byteValue(),"You are my primary and no other peers");
    }

    //A utility method that converts a long data type into bytes to be sent over a socket
    //This is used in order to send file size and the amount of data already sent
    private byte[] ConvertLongToBytes(long value) {
        byte[] bytes = new byte[Long.BYTES];
        int length = bytes.length;
        for (int i = 0; i < length; i++) {
            bytes[length - i - 1] = (byte) (value & 0xFF);
            value >>= 8;
        }
        return bytes;
    }

    public void run() {
        //The main event loop that handles all messages received by this host
        while(!serverSocket.isClosed()) {
            if(!queuedMessages.isEmpty()) {
                //Remove the pending message and grab the clientId and indicator information
                ClientMessage clientMessage = queuedMessages.remove(0);
                UUID clientId = clientMessage.clientId;
                byte indicator = clientMessage.indicatorByte;

                if(clientId != serverId) {
                    switch (indicator) {
                        //This host is receiving new forwarding information from a peer
                        case 5:
                            //Extract the address and port and set the forwarding address for this peer
                            String addressAndPort1 = new String(clientMessage.message);
                            String address1 = addressAndPort1.replace("/", "").split(":")[0];
                            int port = Integer.parseInt(addressAndPort1.split(":")[1]);
                            for(Client permanentClient : permanentClients) {
                                if(permanentClient.clientId.compareTo(clientId) == 0) {
                                    permanentClient.nextClientAddress = new InetSocketAddress(address1, port);
                                }
                            }
                            break;
                        case 7:
                            //The client is signaling a wish to disconnect from this host. Remove it from the permanent
                            //or data transfer clients depending on where it currently is
                            try {
                                boolean removed = false;
                                for(int i = 0; i < permanentClients.size(); i++) {
                                    if(permanentClients.get(i).clientId.compareTo(clientId) == 0) {
                                        RemoveClient(clientId);
                                        removed = true;
                                        break;
                                    }
                                }
                                if(!removed) {
                                    for(int i = 0; i < dataTransferClients.size(); i++) {
                                        if(dataTransferClients.get(i).clientId.compareTo(clientId) == 0) {
                                            RemoveDataTransferClient(clientId);
                                            break;
                                        }
                                    }
                                }
                            } catch (IOException ignored) {

                            }
                            break;
                        case 9:
                            //The notice that the peer has accepted your file download request and will shortly be sending
                            //file data, but this message contains the file size information and requestId
                            //Set request ID
                            byte[] requestIdAsBytes = new byte[16];
                            System.arraycopy(clientMessage.message, 0, requestIdAsBytes, 0, 16);
                            //Set file Size
                            byte[] fileSize = new byte[clientMessage.message.length - 16];
                            System.arraycopy(clientMessage.message, 16, fileSize, 0, fileSize.length);
                            //Get the UUID version of the request ID
                            UUID requestId = UUIDUtils.AsUUID(requestIdAsBytes);
                            //Set the file request file size
                            fileRequests.get(requestId).fileSize = new BigInteger(fileSize).longValue();
                            System.out.println("Connected to peer and preparing to download " + new BigInteger(fileSize).longValue() + " bytes of data...");
                            break;
                        case 10:
                            //Indication that the peer could not fulfill a file download request
                            System.out.println("Connected to peer but could not download file...");
                            //Get the Id of the request and generate the UUID value
                            byte[] requestIdAsBytesss = new byte[16];
                            System.arraycopy(clientMessage.message, 0, requestIdAsBytesss, 0, 16);
                            UUID requestIddd = UUIDUtils.AsUUID(requestIdAsBytesss);
                            //Remove the request from the file requests list
                            fileRequests.remove(requestIddd);
                            //If the client was a data transfer client then terminate the connection and remove them
                            for(int i = 0; i < dataTransferClients.size(); i++) {
                                if(dataTransferClients.get(i).clientId.compareTo(clientId) == 0) {
                                    try {
                                        RemoveDataTransferClientOrigin(clientId);
                                    } catch (IOException ignored) {
                                    }
                                }
                            }
                            break;
                        case 12:
                            //A notice from the client that all file data has been uploaded
                            //This is essentially the termination indicator for the file request
                            System.out.println("Finished downloading file");
                            //Get file request ID bytes and set the UUID from them
                            byte[] requestIdAsBytess = new byte[16];
                            System.arraycopy(clientMessage.message, 0, requestIdAsBytess, 0, 16);
                            UUID requestIdd = UUIDUtils.AsUUID(requestIdAsBytess);
                            //Get the file request and terminate the file output stream
                            FileRequest request = fileRequests.get(requestIdd);
                            try {
                                request.fileOut.close();
                            } catch (IOException ignored) {
                            }
                            //Remove the request from the list
                            fileRequests.remove(requestIdd);
                            SendOne(clientId, new ClientMessage(serverId, (byte) 13, "Finished downloading".getBytes()));
                            break;
                        case 13:
                            //Notice from the client that they have successfully received all the file data that
                            //This host has uploaded to them, if they are a permanent client then do nothing but if they
                            //are a data transfer client then remove them
                            boolean isPermanent = false;
                            for(Client permanentClient : permanentClients) {
                                if(permanentClient.clientId.compareTo(clientId) == 0) {
                                    isPermanent = true;
                                    break;
                                }
                            }
                            if(!isPermanent) {
                                try {
                                    RemoveDataTransferClientOrigin(clientId);
                                } catch (IOException ignored) {

                                }
                            }
                            break;
                        case -4:
                            //Someone is searching for a file and propagated a request.
                            //First get the size of the list of clients that this request has already propagated to
                            //If it is 7 clients then do not search
                            byte clientSize = clientMessage.message[0];
                            String searchTerm = null;
                            byte[] originalBytes = new byte[0];
                            if(clientSize < 96) {
                                //Retrieve the bytes of the already sent to clients
                                originalBytes = new byte[clientSize];
                                for(int i = 0; i < clientSize; i++) {
                                    originalBytes[i] = clientMessage.message[i + 1];
                                }

                                //Get the search term that the user was looking for
                                byte[] searchTermAsBytes = new byte[clientMessage.message.length - clientSize - 1];
                                for(int i = 0; i < searchTermAsBytes.length; i++) {
                                    searchTermAsBytes[i] = clientMessage.message[i + clientSize + 1];
                                }
                                searchTerm = new String(searchTermAsBytes, StandardCharsets.US_ASCII);

                                //Set the new size of the client history (the previous client information as well as your
                                //own client information)
                                byte newSize = (byte) (clientSize + 16);
                                byte[] firstBytes = new byte[1 + newSize + searchTermAsBytes.length];
                                //Get your own ID as bytes and insert the previous client IDs and your own ID to the list
                                byte[] idAsBytes = UUIDUtils.AsBytes(serverId);
                                firstBytes[0] = newSize;
                                for(int i = 0; i < originalBytes.length; i++) {
                                    firstBytes[i + 1] = originalBytes[i];
                                }
                                for(int i = 0; i < idAsBytes.length; i++) {
                                    firstBytes[1 + clientSize + i] = idAsBytes[i];
                                }
                                //Insert the search term back into the new message
                                for(int i = 0; i < searchTermAsBytes.length; i++) {
                                    firstBytes[1 + newSize + i] = searchTermAsBytes[i];
                                }
                                //Propagate the search to all peers except the peer that originally send the message to
                                //you
                                SendAllExcept(clientId, new ClientMessage(serverId, indicator,  firstBytes));
                            }

                            //Checking if you have a file that matches the search criteria
                            if(searchTerm != null) {
                                //Create a list of file matches and generate a string from them with the file names
                                //seperated by a "/" since it is an illegal character in a file name and can be split
                                //on that delimiter later on
                                ArrayList<String> fileMatches = FileListUpdater.SearchForFile(searchTerm);
                                if(!fileMatches.isEmpty() && originalBytes.length != 0) {
                                    StringBuilder files = new StringBuilder();
                                    for(String match : fileMatches) {
                                        files.append(match).append("/");
                                    }
                                    //Adds the address and port information of this host to the message
                                    files = new StringBuilder(files.substring(0, files.length() - 1));
                                    String address = "127.0.0.1:" + serverSocket.getLocalPort();
                                    //Generate a new history list excluding the previous peer that sent this message
                                    //to the host
                                    byte[] newBytes = new byte[originalBytes.length - 16];
                                    for(int i = 0; i < newBytes.length; i++) {
                                        newBytes[i] = originalBytes[i];
                                    }
                                    //Setting the size information of the message
                                    byte[] allBytes = new byte[1 + newBytes.length + 1 + address.length() + 16 + files.length()];
                                    //denotes the length of the history client section
                                    allBytes[0] = (byte) newBytes.length;
                                    //Adds the length of the address to the correct byte position
                                    allBytes[1 + newBytes.length] = (byte) address.length();
                                    //Adds on the new list of clients excluding the previous client and your own information
                                    for(int i = 0; i < newBytes.length; i++) {
                                        allBytes[i + 1] = newBytes[i];
                                    }
                                    //Adds the host address to the message so the requester knows who they can contact
                                    //to retrieve their file
                                    for(int i = 0; i < address.length(); i++) {
                                        allBytes[i + newBytes.length + 2] = (byte) address.charAt(i);
                                    }
                                    //Adds the host ID to the message so that the requester can check whether this
                                    //is from a currently connected peer or not
                                    byte[] thisIdAsBytes = UUIDUtils.AsBytes(serverId);
                                    for(int i = 0; i < 16; i++) {
                                        allBytes[i + address.length() + newBytes.length + 2] = thisIdAsBytes[i];
                                    }
                                    //Adds on the file search response information
                                    for(int i = 0; i < files.length(); i++) {
                                        allBytes[i + address.length() + newBytes.length + 18] = (byte) files.charAt(i);
                                    }
                                    //Sends back to the peer that sent the host the original search message
                                    //This is so that it can propagate back to the requester using the client history
                                    //information embedded in the message
                                    SendOne(clientId, new ClientMessage(serverId, (byte) -5, allBytes));
                                }
                            }
                            break;
                        case -5:
                            //This denotes a successful file search request that is getting sent back to the requester
                            //Checks to make sure that the first byte is not 00, which would mean that this host is in
                            //fact the original requester
                            if(clientMessage.message[0] != 0) {
                                //If the host is not the original requester then start processing the data
                                //Get the original length of the peer history and create the send byte array which will
                                //contain the response information excluding the latest history peer
                                int originalLength = clientMessage.message[0];
                                byte[] allBytes = new byte[clientMessage.message.length - 16];
                                //Sets the first byte to be 16 less (length of a peer ID)
                                allBytes[0] = (byte) (originalLength - 16);
                                //Set the new peer history excluding the peer that you are forwarding the request to
                                for(int i = 1; i < allBytes[0] + 1; i++) {
                                    allBytes[i] = clientMessage.message[i];
                                }
                                //Sets all the rest of the message including the information for the peer that is fulfilling
                                //the request and the file search information contained within
                                for(int i = 0; i < allBytes.length - (1 + allBytes[0]); i++) {
                                    allBytes[i + allBytes[0] + 1] = clientMessage.message[i + originalLength + 1];
                                }
                                //Retrieves the ID of the peer that you should be forwarding the message to as bytes
                                //and creates a UUID from those bytes
                                byte[] sendToUUIDAsBytes = new byte[16];
                                for(int i = 0; i < 16; i++) {
                                    sendToUUIDAsBytes[i] = clientMessage.message[1 + allBytes[0] + i];
                                }
                                UUID sendToUUID = UUIDUtils.AsUUID(sendToUUIDAsBytes);
                                //Forwards the message to the next peer on the history list
                                SendOne(sendToUUID, new ClientMessage(serverId, (byte) -5, allBytes));
                            } else {
                                //If this peer is the original requester of the file search then get the address
                                //and port information of the fulfiller from the bytes
                                byte[] addressAndPort = new byte[clientMessage.message[1]];
                                for(int i = 0; i < addressAndPort.length; i++) {
                                    addressAndPort[i] = clientMessage.message[i + 2];
                                }
                                //Get the peer ID of the client that is fulfilling this request
                                byte[] peerOriginatorAsBytes = new byte[16];
                                for(int i = 0; i < 16; i++) {
                                    peerOriginatorAsBytes[i] = clientMessage.message[i + 2 + addressAndPort.length];
                                }
                                //Get the file list that matches the search criteria
                                byte[] fileList = new byte[clientMessage.message.length - addressAndPort.length - 18];
                                for(int i = 0; i < fileList.length; i++) {
                                    fileList[i] = clientMessage.message[i + 18 + addressAndPort.length];
                                }
                                //Create a string from the file list and split on the "/" delimiter to get the actual list
                                String[] allFiles = new String(fileList).split("/");

                                //Add all of this information to the search results
                                searchResults.add(new SearchResults(new String(addressAndPort).split(":")[0], Integer.parseInt(new String(addressAndPort).split(":")[1]), UUIDUtils.AsUUID(peerOriginatorAsBytes), allFiles));
                            }
                            break;
                    }
                } else {
                    switch (indicator) {
                        //This means that the server is sending this message from itself, so it is the originator
                        case -4:
                            //Clear previous search results
                            searchResults.clear();
                            //Generate the initial peer propagation history with the host ID
                            byte[] firstBytes = new byte[17 + clientMessage.message.length];
                            firstBytes[0] = Integer.valueOf(16).byteValue();
                            byte[] idAsBytes = UUIDUtils.AsBytes(serverId);
                            System.arraycopy(idAsBytes, 0, firstBytes, 1, idAsBytes.length);
                            //Set the search criteria
                            for(int i = 17; i < firstBytes.length; i++) {
                                firstBytes[i] = clientMessage.message[i - 17];
                            }
                            //Propagate the search to your peers
                            SendAll(new ClientMessage(serverId, indicator, firstBytes));
                            break;
                    }
                }
            }
        }
    }
}
