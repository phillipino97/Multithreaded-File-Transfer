package SocketThreads;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

//A class containing all the information about a peer (permanent or data transfer)
public class Client {
    public Socket clientSocket;
    //Is this peer the primary (or forwarding) peer of this host
    public boolean isPrimarySocket;
    //The address of this peer and the forwarding address of this peer's primary peer in case of disconnect
    public InetSocketAddress thisClientAddress = null;
    public InetSocketAddress nextClientAddress = null;
    public UUID clientId;
    //Information on the threads of this peer
    private final ClientRequest clientRequest;
    public final ClientHandler clientHandler;
    //The output stream for this peer
    private final DataOutputStream dataOut;

    //Constructor accepts primary socket info and the clientHandler info of this peer and initializes the
    //Request management thread and the output stream
    public Client(Socket accepted, ClientHandler clientHandler, boolean isPrimarySocket) throws IOException {
        clientSocket = accepted;
        this.isPrimarySocket = isPrimarySocket;
        this.clientHandler = clientHandler;
        clientRequest = new ClientRequest(this);
        dataOut = new DataOutputStream(clientSocket.getOutputStream());
    }

    //Adding a message to the clientHandler's message queue
    public void AddToReceivedQueue(byte indicator, byte[] message) {
        clientHandler.AddMessageToQueue(clientId, indicator, message);
    }

    //Sending a message to this peer from the host
    public void Send(byte indicator, byte[] message) {
        try {
            //Write the indicator byte
            dataOut.writeByte(indicator);
            //Write length of the message information
            dataOut.writeInt(message.length);
            //Write the actual message
            dataOut.write(message);
        } catch (IOException e) {
        }
    }

    //A method for sending a file chunk from this peer
    public void SendFileChunk(byte[] requestId, byte[] data, int count) throws IOException {
        //Write the indicator byte (since it is a file chunk, always 11)
        dataOut.writeByte(11);
        //Write the size of the data that is being sent
        dataOut.writeInt(16 + count);
        //Copy the requestId and data into a byte array together
        byte[] fullData = new byte[16 + count];
        System.arraycopy(requestId, 0, fullData, 0, 16);
        System.arraycopy(data, 0, fullData, 16, count);
        //Write the data
        dataOut.write(fullData);
    }

    //Start the request thread
    public void StartThreads() {
        clientRequest.start();
    }

    //Close the data output stream
    public void Close() throws IOException {
        dataOut.close();
    }
}

//The data input stream thread that reads client requests to this host
class ClientRequest extends Thread {
    private final Client parent;
    private final DataInputStream dataIn;

    //Set the Client as the parent and initialize the data input stream from the parent's socket
    public ClientRequest(Client parent) throws IOException {
        this.parent = parent;
        dataIn = new DataInputStream(parent.clientSocket.getInputStream());
    }

    //The input management thread
    public void run() {
        boolean receivedTerminationByte = false;
        //Checks that the peer is still connected
        while(parent.clientSocket.isConnected() && !parent.clientSocket.isClosed()) {
            try {
                //Wait for an indicator byte
                byte indicator = dataIn.readByte();
                //When indicator is received read the message length
                int dataLength = dataIn.readInt();
                //Create byte array of that length and read the data up until that length
                byte[] message = new byte[dataLength];
                dataIn.readFully(message, 0, dataLength);
                if(indicator == Integer.valueOf(7).byteValue()) {
                    //If the indicator is a 7 then label that the termination byte was sent, so we do not need to
                    //invoke it manually
                    receivedTerminationByte = true;
                }
                if(indicator != (byte) -6 && indicator != (byte) 11) {
                    //If the indicator is not -6 or 11 which indicate requesting a file or receiving file data chunks
                    //then add them to the received queue.
                    //We cannot submit file requests or file data chunks to the ClientHandler thread since both of these
                    //are BLOCKING calls which require the host to spend a long time processing them.
                    //The solution is to launch the SendFile and ReceiveFileChunk methods from inside of this thread
                    //since it is only handling file transmission at the moment anyway.
                    parent.AddToReceivedQueue(indicator, message);
                } else if(indicator == (byte) -6) {
                    //Get the request ID and the file name requested and pass them to the SendFile method in the ClientHandler
                    byte[] requestIdAsBytes = new byte[16];
                    System.arraycopy(message, 0, requestIdAsBytes, 0, 16);
                    byte[] fileNameAsBytes = new byte[dataLength - 16];
                    System.arraycopy(message, 16, fileNameAsBytes, 0, fileNameAsBytes.length);

                    parent.clientHandler.SendFile(parent, requestIdAsBytes, fileNameAsBytes);
                } else if(indicator == (byte) 11) {
                    //Get the request ID and the file data chunks from the request and submit that information
                    //to the ReceiveFileChunk method in the ClientHandler
                    byte[] requestIdAsBytes = new byte[16];
                    System.arraycopy(message, 0, requestIdAsBytes, 0, 16);
                    byte[] fileData = new byte[message.length - 16];
                    System.arraycopy(message, 16, fileData, 0, fileData.length);

                    parent.clientHandler.ReceiveFileChunk(requestIdAsBytes, fileData);
                }
            } catch (IOException e) {
                try {
                    //If there is an IOException and dataIn.read() == -1 then it means that the client has disconnected
                    //silently and we can terminate the loop
                    if(dataIn.read() == -1) {
                        break;
                    }
                } catch (IOException ignored) {

                }
            }
        }
        //If the termination byte was not sent by the client then send it manually now
        //If it was sent then close the dataInputStream
        if(!receivedTerminationByte) {
            parent.AddToReceivedQueue(Integer.valueOf(7).byteValue(), "Client exiting".getBytes());
            try {
                dataIn.close();
            } catch (IOException ignored) {

            }
        }
        try {
            dataIn.close();
        } catch (IOException ignored) {

        }
    }
}
