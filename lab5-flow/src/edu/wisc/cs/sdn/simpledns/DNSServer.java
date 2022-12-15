package edu.wisc.cs.sdn.simpledns;

import edu.wisc.cs.sdn.simpledns.packet.DNS;
import edu.wisc.cs.sdn.simpledns.packet.DNSQuestion;
import edu.wisc.cs.sdn.simpledns.packet.DNSResourceRecord;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdata;
import edu.wisc.cs.sdn.simpledns.packet.DNSRdataString;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.List;
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

					processAndSendDatagramPacket(question, resolvedPacket);
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

	private void processAndSendDatagramPacket(DNSQuestion initialQuestion, DatagramPacket datagramPacket) throws IOException, UnknownHostException {
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

			// for answers of type A, we check if the address(es) are associated with an EC2 region and add TXT record(s) to newAnswers
			// TXT record is in the format: www.code.org TXT Virginia-50.17.209.250
			if (ans.getType() == DNS.TYPE_A) checkEC2(ans, newAnswers);
		}
		resolvedDnsPacket.setAnswers(newAnswers);

		byte[] buffer = resolvedDnsPacket.serialize();
		DatagramPacket resolvedDatagramPacket = new DatagramPacket(buffer, buffer.length);
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
		DatagramPacket ansPkt = resolve(server, packet);
		DNS ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
		String name = "";
		InetAddress nextDst;
		List<DNSResourceRecord> cnameAnswers = new ArrayList<DNSResourceRecord>();	
		byte[] buffer = null;	
		while (ans.getAnswers().size() == 0 || ans.getAnswers().get(0).getType() == DNS.TYPE_CNAME) {
			//System.out.println(ans+"@@@@@@@@@\n");
			if (ans.getAdditional().size() > 0) {
				for (DNSResourceRecord rec : ans.getAdditional()) {
					if (rec.getType() != DNS.TYPE_AAAA && rec.getName().length() > 0) {
						name = rec.getData().toString();
						break;
					}
				}
			} else if (ans.getAuthorities().size() > 0) {
				name = ans.getAuthorities().get(0).getData().toString();
			} else if (ans.getAnswers().size() == 0) {
				break;
			}

			if (ans.getAdditional().size() == 1 && ans.getAdditional().get(0).getName().length() == 0 && ans.getAuthorities().size() == 0 && ans.getAnswers().size() == 0) break;
			
			try {
                nextDst = InetAddress.getByName(name);
			} catch (Exception e) {
                System.err.println(e);
                return null;
            }

			if (ans.getAnswers().size() > 0 && ans.getAnswers().get(0).getType() == DNS.TYPE_CNAME) {
				for (DNSResourceRecord cnameAns : ans.getAnswers()) {
					cnameAnswers.add(cnameAns);
				}
				String newQ = ans.getAnswers().get(0).getData().toString();
				DNS dnsPkt = DNS.deserialize(packet.getData(), packet.getLength());
				DNSQuestion newQuestion = new DNSQuestion(newQ, question.getType());
				List<DNSQuestion> DNSQuestions = new ArrayList<DNSQuestion>();
				DNSQuestions.add(newQuestion);
				dnsPkt.setQuestions(DNSQuestions);
				buffer = dnsPkt.serialize();
				packet = new DatagramPacket(buffer, buffer.length);
			}

			ansPkt = resolve(nextDst, packet);
			ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
		}

		if (cnameAnswers.size() > 0) {
			ans = DNS.deserialize(ansPkt.getData(), ansPkt.getLength());
            List<DNSResourceRecord> DNSAnswers = ans.getAnswers();
            for (DNSResourceRecord cnameAns : cnameAnswers) {
				DNSAnswers.add(cnameAns);
			}

			ans.setAnswers(DNSAnswers);
            buffer = ans.serialize();
            ansPkt = new DatagramPacket(buffer, buffer.length);
		}

		return ansPkt;
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
