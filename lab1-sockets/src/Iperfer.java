public class Iperfer {
    public static void main(String[] args) {
        // parse arguments
        int mode = parseArgs(args);

        if (mode == 1) {
            // client functionality
        } else {
            // server functionality
        } 

        /**
         * Client Functionality
         * - establish a TCP connection with server and send data asap (within <time>)
         * - data read in 1000 byte chunks
         * - data is a byte array of all 0s
         * - keep running total of bytes sent
         * - after <time> ends, stop sending data and close connection
         * - print summary containing <total bytes sent> and <rate traffic could be sent>
         */ 

         /**
          * Server Functionality
          * - listen for TCP connections from a client
          * - receive data asap until client closes connection
          * - data read in 1000 byte chunks
          * - keep running total of bytes received
          * - print summary containing <total bytes received> and <rate traffic could be read>
          * - server should shutdown after handling 1 connection
          */
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
}
