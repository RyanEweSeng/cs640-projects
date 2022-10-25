package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.ARP;

import java.nio.ByteBuffer;
/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device {	
	/**
	 * Routing table for the router
	 */
	private RouteTable routeTable;
	
	/**
	 * ARP cache for the router
	 */
	private ArpCache arpCache;

	/**
	 * Enumeration for the 5 types of ICMP messages
	 */
	private enum ICMPMessageType {
		TIME_EXCEEDED,
		DEST_NET_UNREACHABLE,
		DEST_HOST_UNREACHABLE,
		DEST_PORT_UNREACHABLE,
		ECHO_REPLY
	}

	/**
	 * Enumeration for ARP reply or request
	 */
	private enum ARPType {
		ARP_REPLY,
		ARP_REQUEST
	}

	/**
	 * Flag to trigger debug statements (remember to set to false on submission).
	 */
	private boolean dbg = true;
	
	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Router(String host, DumpFile logfile) {
		super(host,logfile);
		this.routeTable = new RouteTable();
		this.arpCache = new ArpCache();
	}
	
	/**
	 * @return routing table for the router
	 */
	public RouteTable getRouteTable() { return this.routeTable; }
	
	/**
	 * Load a new routing table from a file.
	 * @param routeTableFile the name of the file containing the routing table
	 */
	public void loadRouteTable(String routeTableFile) {
		if (!routeTable.load(routeTableFile, this)) {
			System.err.println("Error setting up routing table from file "
					+ routeTableFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static route table");
		System.out.println("-------------------------------------------------");
		System.out.print(this.routeTable.toString());
		System.out.println("-------------------------------------------------");
	}
	
	/**
	 * Load a new ARP cache from a file.
	 * @param arpCacheFile the name of the file containing the ARP cache
	 */
	public void loadArpCache(String arpCacheFile) {
		if (!arpCache.load(arpCacheFile)) {
			System.err.println("Error setting up ARP cache from file "
					+ arpCacheFile);
			System.exit(1);
		}
		
		System.out.println("Loaded static ARP cache");
		System.out.println("----------------------------------");
		System.out.print(this.arpCache.toString());
		System.out.println("----------------------------------");
	}

	/**
	 * Handle an Ethernet packet received on a specific interface.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the port on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface) {
		// we only handle IPv4 and ARP packets
		short etherType = etherPacket.getEtherType();
		switch(etherType) {
			case Ethernet.TYPE_IPv4:
				handleIpPacket(etherPacket, inIface);	
				break;
			case Ethernet.TYPE_ARP:
				handleArpPacket(etherPacket, inIface);
				break;
			default:
				return;

		}
	}

	/**
	 * Handles the incoming ARP packet.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the port on which the packet was received
	 */
	private void handleArpPacket(Ethernet etherPacket, Iface inIface) {
		// TODO
		// if ARP packet is an ARP request
		ARP arpPacket = (ARP)etherPacket.getPayload();
		int targetIp = ByteBuffer.wrap(arpPacket.getTargetProtocolAddress()).getInt();
		// packet is a request and has reached destination router
		if (arpPacket.getOpCode() == ARP.OP_REQUEST && targetIp == inIface.getIpAddress()){
			sendArpPacket(ARPType.ARP_REPLY, etherPacket, inIface);
		}

		//only respond to ARP requests whose target IP protocol address equals the IP address of the
		// interface on which the ARP request was received.
		// if OP_REQUEST, then send an ARP reply
		// if OP_REPLY, then add entry to the ARP cache
	}

	/**
	 * Constructs and sends ARP replies and requests.
	 */
	private void sendArpPacket(ARPType type, Ethernet etherPacket, Iface inIface) {
		ARP arpPacket = (ARP) etherPacket.getPayload();
		Ethernet ether = new Ethernet();
		ARP arp = new ARP();
		// populate ethernet header fields
		ether.setEtherType(Ethernet.TYPE_ARP);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		ether.setDestinationMACAddress(etherPacket.getSourceMACAddress());
		// populate ARP header
		arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
		arp.setProtocolType(ARP.PROTO_TYPE_IP);
		arp.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
		arp.setProtocolAddressLength((byte) 4);
		arp.setOpCode(ARP.OP_REPLY);
		arp.setSenderHardwareAddress(inIface.getMacAddress().toBytes());
		arp.setSenderProtocolAddress(inIface.getIpAddress());
		arp.setTargetHardwareAddress(arpPacket.getSenderHardwareAddress());
		arp.setTargetProtocolAddress(arpPacket.getSenderProtocolAddress());

		ether.setPayload(arp);
		
		sendPacket(ether, inIface);
	}

	/**
	 * Enqueue incoming packet and generate ARP request on ARP cache miss.
	 */
	private void handleArpMiss() {
		// TODO
	}
	
	/**
	 * Handles the incoming IP packet.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the port on which the packet was received
	 */
	private void handleIpPacket(Ethernet etherPacket, Iface inIface) {
		// get the payload
		IPv4 ipPacket = (IPv4) etherPacket.getPayload();

		// store the checksum and zero it
		short checksum = ipPacket.getChecksum();
		ipPacket = ipPacket.setChecksum((short) 0);

		// compute the checksum
		byte[] serializedData = ipPacket.serialize();
		ipPacket = (IPv4) ipPacket.deserialize(serializedData, 0, serializedData.length);
		short computedChecksum = ipPacket.getChecksum();

		// verify checksum
		if (checksum != computedChecksum) return;

		// decrement the IPv4 packet's TTL by 1 and verify
		byte origTtl = ipPacket.getTtl();
		ipPacket = ipPacket.setTtl((byte)(origTtl - 1));
		if (ipPacket.getTtl() == 0) {
			if (dbg) System.out.println("ICMP MESSAGE - TIME EXCEEDED");

			sendICMP(ICMPMessageType.TIME_EXCEEDED, etherPacket, inIface);
			return;
		}

		// check if destination IP exactly matches one of the interface's IP
		int ipPacketDestIP = ipPacket.getDestinationAddress();
		for (Iface iface : interfaces.values()) {
			if (iface.getIpAddress() == ipPacketDestIP) {
				byte protocol = ipPacket.getProtocol();
				
				if (protocol == IPv4.PROTOCOL_UDP || protocol == IPv4.PROTOCOL_TCP) {
					if (dbg) System.out.println("ICMP MESSAGE - DEST PORT UNREACHABLE");

					sendICMP(ICMPMessageType.DEST_PORT_UNREACHABLE, etherPacket, inIface);
				} else if (protocol == IPv4.PROTOCOL_ICMP) {
					// get icmp packet from the ip packet
					ICMP icmpPacket = (ICMP) ipPacket.getPayload();
					if (icmpPacket.getIcmpType() == ICMP.TYPE_ECHO_REQUEST) {
						if (dbg) System.out.println("ICMP MESSAGE - ECHO REPLY");

						sendICMP(ICMPMessageType.ECHO_REPLY, etherPacket, inIface);
					}
				}

				return;
			}	
		}

		// update ipPacket (wtf does serialize + deserialize do???) and create new packet
		ipPacket = ipPacket.setChecksum((short) 0);
		serializedData = ipPacket.serialize();
		ipPacket = (IPv4) ipPacket.deserialize(serializedData, 0, serializedData.length);
		Ethernet newPacket = (Ethernet) etherPacket.setPayload(ipPacket);
		
		// search for new src/dst MACs and forward new packet
		RouteEntry re = routeTable.lookup(ipPacketDestIP);
		if (re != null) {
			ArpEntry ae = null;
			if (re.getGatewayAddress() != 0) { // destination IP is in another network and we need to move across routers to reach it
				ae = arpCache.lookup(re.getGatewayAddress());
			} else { // destination IP is on the local network
				ae = arpCache.lookup(ipPacketDestIP);
			}

			if (ae == null)	{
				if (dbg) System.out.println("ICMP MESSAGE - DEST HOST UNREACHABLE");

				sendICMP(ICMPMessageType.DEST_HOST_UNREACHABLE, etherPacket, inIface);
				return;
			}

			// updated the Ethernet header with the new source and destination MACs
			newPacket = newPacket.setSourceMACAddress(re.getInterface().getMacAddress().toBytes());
			newPacket = newPacket.setDestinationMACAddress(ae.getMac().toBytes());

			// send packet
			sendPacket(newPacket, re.getInterface());
		} else {
			if (dbg) System.out.println("ICMP MESSAGE - DEST NET UNREACHABLE");

			sendICMP(ICMPMessageType.DEST_NET_UNREACHABLE, etherPacket, inIface);
			return;
		}

		return;
	}

	/**
	 * Handles the type of ICMP message to be sent.
	 * @param type the type of message to send
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the port on which the packet was received
	 */
	private void sendICMP(ICMPMessageType type, Ethernet etherPacket, Iface inIface) {
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		ICMP icmp = new ICMP();
		Data data = new Data();
		
		// populate ether header
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());

		IPv4 ipPacket = (IPv4) etherPacket.getPayload();
		RouteEntry re = routeTable.lookup(ipPacket.getSourceAddress());
		if (re != null) {
			ArpEntry ae = null;
			if (re.getGatewayAddress() != 0) {
				ae = arpCache.lookup(re.getGatewayAddress());
			} else {
				ae = arpCache.lookup(ipPacket.getSourceAddress());
			}

			if (ae == null) return;
			
			ether.setDestinationMACAddress(ae.getMac().toBytes());
		} else {
			return;
		}

		// populate ip header
		ip.setTtl((byte) 64);
		ip.setProtocol(IPv4.PROTOCOL_ICMP);
		switch(type) {
			case TIME_EXCEEDED:
			case DEST_NET_UNREACHABLE:
			case DEST_HOST_UNREACHABLE:
			case DEST_PORT_UNREACHABLE:
				ip.setSourceAddress(inIface.getIpAddress());
				break;
			case ECHO_REPLY:
				ip.setSourceAddress(ipPacket.getDestinationAddress());
				break;
			default:
				return;
		}
		ip.setDestinationAddress(ipPacket.getSourceAddress());

		// populate icmp header
		switch(type) {
			case TIME_EXCEEDED:
				icmp.setIcmpType((byte) 11);
				icmp.setIcmpCode((byte) 0);
				break;
			case DEST_NET_UNREACHABLE:
				icmp.setIcmpType((byte) 3);
				icmp.setIcmpCode((byte) 0);
				break;
			case DEST_HOST_UNREACHABLE:
				icmp.setIcmpType((byte) 3);
				icmp.setIcmpCode((byte) 1);
				break;
			case DEST_PORT_UNREACHABLE:
				icmp.setIcmpType((byte) 3);
				icmp.setIcmpCode((byte) 3);
				break;
			case ECHO_REPLY:
				icmp.setIcmpType((byte) 0);
				icmp.setIcmpCode((byte) 0);
				break;
			default:
				return;
		}
	
		// populate payload
		byte[] icmpData;
		switch(type) {
			case TIME_EXCEEDED:
			case DEST_NET_UNREACHABLE:
			case DEST_HOST_UNREACHABLE:
			case DEST_PORT_UNREACHABLE:
				// get original IP header
				byte[] origIpHeader = ipPacket.serialize();

				// find the length needed
				int ipHeaderLength = ipPacket.getHeaderLength() * 4;
				icmpData = new byte[4 + ipHeaderLength + 8];

				// 4 bytes of padding
				icmpData[0] = (byte) 0;
				icmpData[1] = (byte) 0;
				icmpData[2] = (byte) 0;
				icmpData[3] = (byte) 0;

				// copy original ip header
				// also copy 8 bytes after the IP header (the first 8 bytes of the IP payload)
				for (int i = 0; i < ipHeaderLength + 8; i++) icmpData[i + 4] = origIpHeader[i];
				
				data.setData(icmpData);
				break;
			case ECHO_REPLY:
				ICMP icmpPacket = (ICMP) ipPacket.getPayload();
				icmpData = icmpPacket.getPayload().serialize();	

				data.setData(icmpData);
				break;
			default:
				return;
		}

		ether.setPayload(ip);
		ip.setPayload(icmp);
		icmp.setPayload(data);
	
		sendPacket(ether, inIface);
	}
}
