package edu.wisc.cs.sdn.simpledns;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdata;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataName;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataString;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataAddress;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;

import javax.xml.crypto.Data;

import java.util.ArrayList;
import java.util.Collections;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class DNSServer {
	private static final int BINDED_PORT = 8053;
	private static final int SEND_PORT = 53;
	private static final int BUFFER_SIZE = 4096;

	private static InetAddress rootServer;
	private static List<EC2RegionInfo> ec2Regions;

	private DatagramSocket dnsSocket;

	DNSServer(String rootServerName, String csvPath) throws UnknownHostException, SocketException {
		rootServer = Inet4Address.getByName(rootServerName);
		ec2Regions = parseCSV(csvPath);
		this.dnsSocket = new DatagramSocket(BINDED_PORT);
	}

	public void start() throws IOException {
		while (true) {
			DatagramPacket datagramPacket = receiveDatagramPacket();
			DNS dnsPacket = DNS.deserialize(datagramPacket.getData(), datagramPacket.getLength());

			if (dnsPacket.getOpcode() == DNS.OPCODE_STANDARD_QUERY) this.handleDNS(datagramPacket);
		}
	}

	private void handleDNS(DatagramPacket inPacket) throws IOException {
		DNS dnsPacket = DNS.deserialize(inPacket.getData(), inPacket.getLength());
		List<DNSQuestion> packetQuestions = dnsPacket.getQuestions();

		for (DNSQuestion question : packetQuestions) {
			switch(question.getType()) {
				case DNS.TYPE_A:
				case DNS.TYPE_AAAA:
				case DNS.TYPE_CNAME:
				case DNS.TYPE_NS:
					DatagramPacket resolvedPacket = null;

					if (dnsPacket.isRecursionDesired()) {
						resolvedPacket = recursiveResolve(rootServer, inPacket, question);
					} else {
						resolvedPacket = resolve(rootServer, inPacket);
					}

					processAndSendDatagramPacket(question, inPacket, resolvedPacket);
					break;
				default:
					continue;
			}
		}
		
		return;
	}

	private DatagramPacket receiveDatagramPacket() throws IOException {
		byte[] buffer = new byte[BUFFER_SIZE];
		DatagramPacket inPacket= new DatagramPacket(buffer, BUFFER_SIZE);
		this.dnsSocket.receive(inPacket);

		return inPacket;
	}

	private void processAndSendDatagramPacket(DNSQuestion initialQuestion, DatagramPacket initialPacket, DatagramPacket datagramPacket) throws IOException, UnknownHostException {
		// convert resolved packet into a DNS packet to extract the answers to our query/question
		DNS resolvedDnsPacket = DNS.deserialize(datagramPacket.getData(), datagramPacket.getLength());

		// set the initial question (asked by the client)
		// we only have to deal with 1 question but setting questions takes in a list
		List<DNSQuestion> newQuestions = new ArrayList<DNSQuestion>();
		newQuestions.add(initialQuestion);
		resolvedDnsPacket.setQuestions(newQuestions);

		// set the answers from the resolvedPacket
		List<DNSResourceRecord> newAnswers = new ArrayList<DNSResourceRecord>();
		for (DNSResourceRecord ans : resolvedDnsPacket.getAnswers()) {
			newAnswers.add(ans);

			// TODO: clean up any duplicate answers

			// for answers of type A, we check if the address(es) are associated with an EC2 region and add TXT record(s) to newAnswers
			// TXT record is in the format: www.code.org TXT Virginia-50.17.209.250
			if (ans.getType() == DNS.TYPE_A) checkEC2(ans, newAnswers);
		}
		resolvedDnsPacket.setAnswers(newAnswers);

		// convert it back to a datagram packet
		byte[] buffer = resolvedDnsPacket.serialize();
		DatagramPacket resolvedDatagramPacket = new DatagramPacket(buffer, buffer.length);

		// set send port and address (client)
		resolvedDatagramPacket.setPort(initialPacket.getPort());
		resolvedDatagramPacket.setAddress(InetAddress.getByName("localhost"));
		this.dnsSocket.send(resolvedDatagramPacket);

        return;
    }

	private DatagramPacket resolve(InetAddress server, DatagramPacket packet) throws IOException {
		DatagramPacket outPacket = new DatagramPacket(packet.getData(), packet.getLength(), server, SEND_PORT);
		this.dnsSocket.send(outPacket);

		byte[] buffer = new byte[BUFFER_SIZE];
		DatagramPacket replyPacket = new DatagramPacket(buffer, BUFFER_SIZE);
		this.dnsSocket.receive(replyPacket);

		return replyPacket;
	}

	private DatagramPacket recursiveResolve(InetAddress server, DatagramPacket packet, DNSQuestion question) throws IOException {
		DatagramPacket resolvedDatagramPacket = null;

		DatagramPacket responseDatagramPacket = resolve(server, packet);
		DNS resolvedDnsPacket = DNS.deserialize(responseDatagramPacket.getData(), responseDatagramPacket.getLength());

		List<DNSResourceRecord> finalAnswers = new ArrayList<>();
		List<DNSResourceRecord> finalAuths = new ArrayList<>();
		List<DNSResourceRecord> finalAdds = new ArrayList<>();

		while (resolvedDnsPacket.getRcode() == DNS.RCODE_NO_ERROR) {
			if (resolvedDnsPacket.getAnswers().size() == 0) { // no answers found
				finalAuths = resolvedDnsPacket.getAuthorities();
				finalAdds = resolvedDnsPacket.getAdditional();

				if (finalAuths.size() == 0) break;

				for(DNSResourceRecord auth : resolvedDnsPacket.getAuthorities()) { // check authorities
					if (auth.getType() != DNS.TYPE_NS) continue;

					String authDataName = ((DNSRdataName) auth.getData()).getName();
					DatagramPacket nextQuery = new DatagramPacket(packet.getData(), packet.getLength());
					if (resolvedDnsPacket.getAdditional().size() == 0) { // no additionals means we ask the name server
						nextQuery.setAddress(InetAddress.getByName(authDataName));
						this.dnsSocket.send(nextQuery);
						this.dnsSocket.receive(responseDatagramPacket);
						resolvedDnsPacket = DNS.deserialize(responseDatagramPacket.getData(), responseDatagramPacket.getLength());
					} else { // additionals means we have to ask the IP associated with that additional
						for (DNSResourceRecord add : resolvedDnsPacket.getAdditional()) {
							InetAddress addDataAddress = ((DNSRdataAddress) add.getData()).getAddress();
							if (authDataName.contentEquals(add.getName()) && add.getType() == DNS.TYPE_A) {
								nextQuery.setAddress(addDataAddress);
								this.dnsSocket.send(nextQuery);
								this.dnsSocket.receive(responseDatagramPacket);
								resolvedDnsPacket = DNS.deserialize(responseDatagramPacket.getData(), responseDatagramPacket.getLength());
							}
						}
					}
				}
			} else { // answers found
				// add answers
				for (DNSResourceRecord ans : resolvedDnsPacket.getAnswers()) {
					finalAnswers.add(ans);

					String dataName = ((DNSRdataName) ans.getData()).getName();

					if (ans.getType() == DNS.TYPE_CNAME) {
						boolean match = false; // check if CNAME record data name is one of the answers
						for (DNSResourceRecord ans2 : resolvedDnsPacket.getAnswers()) { 
							String name = ans2.getName();
							if (name.equals(dataName)) {
								match = true;
								break;
							}
						}

						if (match) continue;

						if (question.getType() == DNS.TYPE_A || question.getType() == DNS.TYPE_AAAA) { // resolve CNAME records recursively
							DNSQuestion cnameQuestion = new DNSQuestion(dataName, question.getType());
							DatagramPacket nextPacket = createNewPacket(cnameQuestion);
							DatagramPacket resolvedCnameDatagram = recursiveResolve(server, nextPacket, cnameQuestion);
							DNS resolvedCnameDNS = DNS.deserialize(resolvedCnameDatagram.getData(), resolvedCnameDatagram.getLength());

							finalAnswers.addAll(resolvedCnameDNS.getAnswers());

							finalAuths = resolvedCnameDNS.getAuthorities();
							finalAdds = resolvedCnameDNS.getAdditional();
						}
					}
				}

				break;
			}
		}

		// we have fully resolved the question
		resolvedDnsPacket.setAuthorities(finalAuths);
		resolvedDnsPacket.setAdditional(finalAdds);
		resolvedDnsPacket.setAnswers(finalAnswers);

		byte[] buffer = new byte[BUFFER_SIZE];
		buffer = resolvedDnsPacket.serialize();
		resolvedDatagramPacket = new DatagramPacket(buffer, buffer.length);

		return resolvedDatagramPacket;
	}

	private DatagramPacket createNewPacket(DNSQuestion question) {
		DNS newDNS = new DNS();
		newDNS.setOpcode(DNS.OPCODE_STANDARD_QUERY);
		newDNS.addQuestion(question);
		newDNS.setId((short) 0x00aa);
		newDNS.setRecursionDesired(true);
		newDNS.setRecursionAvailable(false);
		newDNS.setQuery(true);

		return new DatagramPacket(newDNS.serialize(), newDNS.serialize().length);
	}

	private void checkEC2(DNSResourceRecord ansRecord, List<DNSResourceRecord> newAnswers) throws UnknownHostException {
		for (EC2RegionInfo info : ec2Regions) {
			long ip = info.getIp();
			long mask = info.getMask();
			String location = info.getLoc();

			// convert ansIp from String to long
			InetAddress ansAddress = InetAddress.getByName(ansRecord.getData().toString());
			long ansIp = ByteBuffer.wrap(ansAddress.getAddress()).getInt();

			// check if subnet IPs are the same
			if ((ip & mask) == (ansIp & mask)) {
				// create rdata as a formatted string
				DNSRdata rdataString = new DNSRdataString(String.format("%s-%s", location, ansRecord.getData().toString()));
				DNSResourceRecord newAns = new DNSResourceRecord(ansRecord.getName(), DNS.TYPE_TXT, rdataString);

				newAnswers.add(newAns);
				break;
			}
		}

		return;
	}

	// reference: https://stackoverflow.com/questions/18033750/read-one-line-of-a-csv-file-in-java
	private static List<EC2RegionInfo> parseCSV(String path) {
		List<EC2RegionInfo> entries = new ArrayList<>();
		try {
			BufferedReader reader = new BufferedReader(new FileReader(path));
			String line = null;
			while ((line = reader.readLine()) != null) {
				EC2RegionInfo subnet = new EC2RegionInfo(line);
				entries.add(subnet);
			}
			reader.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return entries;
	}
}
