package edu.wisc.cs.sdn.simpledns;

import java.io.IOException;
import java.net.SocketException;
import java.net.UnknownHostException;

public class SimpleDNS {
	public static void main(String[] args) throws IOException, UnknownHostException, SocketException {
        String rootServerName = args[1];
		String csvPath = args[3];

		DNSServer server = new DNSServer(rootServerName, csvPath);
		server.start();
	}
}
