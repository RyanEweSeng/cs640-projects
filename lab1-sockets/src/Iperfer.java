public class Iperfer {
    public static void main(String[] args) {
        System.out.println("Hello, World!");

        /**
         * Client Functionality
         * - establish a TCP connection with server and send data asap (within <time>)
         * - data read in 1000 byte chunks
         * - data is a byte array of all 0s
         * - keep running total of bytes sent
         * - after <time> ends, stop sending data and close connection
         * - print summary containing <total bytes sent> and <rate traffic could be sent>
         * 
         * Client Flags
         * - invoke format: java Iperfer -c -h <server hostname> -p <server port> -t <time>
         * - 1024 <= server port <= 65535
         * 
         * Client Exceptions
         * - missing/additional arguments
         * - invalid <server port> number
         * - time and hostname are up to us to decide what is reasonable
         */

         /**
          * Server Functionality
          * - listen for TCP connections from a client
          * - receive data asap until client closes connection
          * - data read in 1000 byte chunks
          * - keep running total of bytes received
          * - print summary containing <total bytes received> and <rate traffic could be read>
          * - server should shutdown after handling 1 connection
          *
          * Server Flags
          * - java Iperfer -s -p <listen port>
          * - 1024 <= listen port <= 65535
          *
          * Server Exceptions
          * - missing/additional arguments
          * - invalid <listen port> number
          */
    }
}
