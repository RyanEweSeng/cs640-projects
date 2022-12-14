package edu.wisc.cs.sdn.simpledns;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class DNSServer {
	private static final int LISTEN_PORT = 8053;
	private static final int SEND_PORT = 53;
	private static final int BUFFER_SIZE = 4096;

	private static InetAddress rootServer;
	private static List<Subnet> ec2Regions;

	private DatagramSocket clientSocket;
	private DatagramPacket clientDatagramPacket;

	DNSServer(String rootServerName, String csvPath) throws UnknownHostException, SocketException {
		rootServer = Inet4Address.getByName(rootServerName);
		ec2Regions = parseCSV(csvPath);
		this.clientSocket = new DatagramSocket(LISTEN_PORT);
	}

	public void start() throws IOException {
		while (true) {
			DNS packet = receiveDNS(this.clientSocket);
			if (packet.getOpcode() == DNS.OPCODE_STANDARD_QUERY) handleDNS(packet);
		}
	}

	private void handleDNS(DNS inPacket) throws IOException {
		List<DNSQuestion> packetQuestions = inPacket.getQuestions();

		if (packetQuestions.size() == 0) return;

		for (DNSQuestion question : packetQuestions) {
			switch(question.getType()) {
				case DNS.TYPE_A:
				case DNS.TYPE_AAAA:
				case DNS.TYPE_CNAME:
				case DNS.TYPE_NS:
					DNS finalPacket = null;

					if (inPacket.isRecursionDesired()) {
						DNSQuestion q = inPacket.getQuestions().get(0);
						finalPacket = recursiveResolve(inPacket, rootServer, q);
						finalPacket.setQuery(false);
					} else {
						finalPacket = resolve(rootServer, inPacket);
					}

					sendDNS(this.clientSocket, finalPacket);
					break;
				default:
					continue;
			}
		}
		
		return;
	}

	private DNS receiveDNS(DatagramSocket socket) throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		DatagramPacket inPacket = new DatagramPacket(buffer, BUFFER_SIZE);
		socket.receive(inPacket);

		this.clientDatagramPacket = inPacket; // we need the datagram packet to clone the clientPacket in recursiveResolve

		return DNS.deserialize(inPacket.getData(), BUFFER_SIZE);
	}

	private void sendDNS(DatagramSocket socket, DNS packet) throws IOException {
		byte[] buffer = packet.serialize();
		socket.send(new DatagramPacket(buffer, buffer.length));

        return;
    }

	private DNS resolve(InetAddress server, DNS packet) throws IOException {
		DatagramSocket socket = new DatagramSocket();
		socket.connect(server, SEND_PORT);

		sendDNS(socket, packet);
		DNS replyPacket = receiveDNS(socket);

		socket.close();

		return replyPacket;
	}

	private DNS recursiveResolve(InetAddress server, DNSQuestion question) throws IOException {
		// create the final packet (the packet that should contain resolved information)
		DNS finalPacket = DNS.deserialize(this.clientDatagramPacket.getData(), this.clientDatagramPacket.getLength());
		finalPacket.setQuestions(Collections.singletonList(question));
		finalPacket.setRecursionDesired(false);

		// ask the server
		DNS replyPacket = resolve(server, finalPacket);




		return null; // TODO
	}

	// reference: https://stackoverflow.com/questions/18033750/read-one-line-of-a-csv-file-in-java
	private static List<Subnet> parseCSV(String path) {
		List<Subnet> entries = new ArrayList<>();
		try {
			BufferedReader reader = new BufferedReader(new FileReader(path));
			String line = null;
			while ((line = reader.readLine()) != null) {
				Subnet subnet = new Subnet(line);
				entries.add(subnet);
			}
			reader.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return entries;
	}
}
