Generates a basic P2P system which allows peers to upload and download 
files from other peers.\
This system can theoretically accept and infinite number of connections.
A peer connects to another peer initially and other peers can then connect to that peer, these are referred to as
"Permanent Connections" in this system. When a node requests data from a peer that is not its permanent peer it
then connects to that peer with a "Data Transfer Connection" which is destroyed upon file transfer completion
or failure.\
When running this program please have two folders in the working directory. One labeled as "downloads" and one labeled
as "uploads". The "uploads" folder contains data that you would like to be searchable by other peers. The "downloads"
folder will contain files downloaded from other peers.
This program runs a Thread for user input, a Thread for accepting socket connections, a Thread for managing client
request processing and writing, and a Thread for managing client requests (one Thread per client).