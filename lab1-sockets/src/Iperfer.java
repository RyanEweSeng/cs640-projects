import java.net.*;
import java.io.*;

public class Iperfer {
    private static final int CHUNK_SIZE = 1000;

    public static void main(String[] args) {
        // parse arguments
        int mode = parseArgs(args);

        if (mode == 1) {
            String hostname = args[2];
            int port = Integer.parseInt(args[4]);
            int time = Integer.parseInt(args[6]);
            client(hostname, port, time);
        } else {
            int port = Integer.parseInt(args[2]);
            server(port);
        } 
         
    }

    public static int parseArgs(String[] args) {
        if (args.length > 0 && args[0].equals("-c") && args.length == 7 && args[1].equals("-h") && args[3].equals("-p") && args[5].equals("-t")) {
            int portNumber = Integer.parseInt(args[4]); 
            if (portNumber < 1024 || portNumber > 65535) {
                System.out.println("Error: port number must be in the range 1024 to 65535");
                System.exit(0);
            }

            return 1;
        } else if (args.length > 0 && args[0].equals("-s") && args.length == 3 && args[1].equals("-p")) {
            int portNumber = Integer.parseInt(args[2]); 
            if (portNumber < 1024 || portNumber > 65535) {
                System.out.println("Error: port number must be in the range 1024 to 65535");
                System.exit(0);
            }

            return 0;
        } else {
            System.out.println("Error: invalid arguments");
            System.exit(0);
        }

        return -1;
    }
        
    /**
     * Client Functionality
     * - establish a TCP connection with server and send data asap (within time)
     * - data read in 1000 byte chunks
     * - data is a byte array of all 0s
     * - keep running total of bytes sent
     * - after time ends, stop sending data and close connection
     * - print summary containing total bytes sent and rate traffic could be sent
     */ 
    public static void client(String hostname, int port, int time) {
        byte[] data = new byte[CHUNK_SIZE];
        Socket socket = null;
        try {
        	socket = new Socket(hostname, port);
            DataOutputStream out = null;
			out = new DataOutputStream(socket.getOutputStream());
			long endTime = System.currentTimeMillis() + (time * 1000);
	        while (System.currentTimeMillis() < endTime) {
	        	out.write(data);
	        }
	        out.close();
	        socket.close();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} 
    }

    /**
     * Server Functionality
     * - listen for TCP connections from a client
     * - receive data asap until client closes connection
     * - data read in 1000 byte chunks
     * - keep running total of bytes received
     * - print summary containing total bytes received and rate traffic could be read
     * - server should shutdown after handling 1 connection
     */
    public static void server(int port) {
    	
    	ServerSocket socket;
		try {
			socket = new ServerSocket(port);
	    	Socket clientSocket = socket.accept(); // Listen for a connection
	    	DataInputStream in = new DataInputStream(clientSocket.getInputStream());
	    	while (!clientSocket.isClosed()) {
	    		System.out.println(in.readInt());
	    	}
	    	in.close(); clientSocket.close(); socket.close(); // Close connection
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} 

    }
}
