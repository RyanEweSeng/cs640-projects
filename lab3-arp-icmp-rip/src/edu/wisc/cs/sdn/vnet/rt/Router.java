package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.MACAddress;
import net.floodlightcontroller.packet.RIPv2;
import net.floodlightcontroller.packet.RIPv2Entry;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.packet.ICMP;
import net.floodlightcontroller.packet.Data;
import net.floodlightcontroller.packet.ARP;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
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
	 * Packet queues for ARP misses for each unique IP address
	 */
	private Map<Integer, List<Ethernet>> packetQueueMap;

	/**
	 * Map to store RIP entries with their corresponding destination network IP
	 */
	private Map<Integer, RipEntry> ripMap;

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

	/*
	 * Enumeration for RIP packet types
	 */
	private enum RIPType {
		RIP_SOL_RESPONSE,
		RIP_UNSOL_RESPONSE,
		RIP_REQUEST,
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
		this.packetQueueMap = new ConcurrentHashMap<>();
		this.ripMap = new ConcurrentHashMap<>();
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
	 * RipEntry will contain additional information such as:
	 * - nextHop: the ip of the next node to take to reach the destination
	 * - cost: the cost of this route
	 * - creationTime: the time which the route was created or last updated
	 * - destAddr: the destination ip address
	 * - subnetMask: the subnet for the destination ip address
	 */
	class RipEntry {
		public int nextHop;
		public int cost;
		public long creationTime;
		public int destAddr;
		public int subnetMask;

		public RipEntry(int nextHop, int cost, long creationTime, int destAddr, int subnetMask) {
			this.nextHop = nextHop;
			this.cost = cost;
			this.creationTime = creationTime;
			this.destAddr = destAddr;
			this.subnetMask = subnetMask;
		}
	}

	/**
	 * Initializes the route table with the directly reachable subnets of the router and sends out RIP requests (flooding).
	 * Also, sends unsolicited RIP responses out of all interfaces every 10s after initialization.
	 * Also, periodically checks for route table entries with an age > 30s which are removed.
	 */
	public void initRip() {
		for (Iface iface : interfaces.values()) {
			int nextHop = iface.getIpAddress();
			int cost = 1;
			long creationTime = -1; // to identify interface routes
			int destAddr = nextHop; // the nextHop is the same as the destAddr in these cases
			int subnetMask = iface.getSubnetMask();
			int destNetAddr = destAddr & subnetMask;

			if (dbg) System.out.println("initialized rip + routetable");
			RipEntry entry = new RipEntry(nextHop, cost, creationTime, destAddr, subnetMask);
			ripMap.put(destNetAddr, entry);
			routeTable.insert(destNetAddr, 0, subnetMask, iface);
			
			if (dbg) System.out.println("initial route table\n" + routeTable.toString() + "----");
		
			sendRipPacket(RIPType.RIP_REQUEST, null, iface);	
		}

		TimerTask sendUnsolResponseTask = new TimerTask() {
			public void run() {
				for (Iface iface : interfaces.values()) {
					sendRipPacket(RIPType.RIP_UNSOL_RESPONSE, null, iface);
				}
			}
		};

		TimerTask checkRouteTableTask = new TimerTask() {
			public void run() {
				if (dbg) {
					System.out.println("current route table:\n" + routeTable.toString() + "----");
					System.out.println("current rip map:");
					printRipMap();
					System.out.println("----");
				}
				for (RipEntry e : ripMap.values()) {
					if (e.creationTime != -1) { // we do not want to remove its interfaces
						long age = System.currentTimeMillis() - e.creationTime;
						if (age >= 30000) {
							ripMap.remove(e.destAddr & e.subnetMask);
							routeTable.remove(e.destAddr, e.subnetMask);
							if (dbg) System.out.println("removed old entry:\n" + routeTable.toString() + "----");
						}
					}
				}
			}
		};

		Timer timer = new Timer(true);
		timer.schedule(sendUnsolResponseTask, 0, 10000);
		timer.schedule(checkRouteTableTask, 0, 1000);
	}

	private void printRipMap() {
		System.out.println("destNetAddr    nextHop        cost    creationTime        destAddr        subnetMask");
		for (Map.Entry<Integer, RipEntry> e : ripMap.entrySet()) {
			RipEntry ripE = e.getValue();
			System.out.println(
				IPv4.fromIPv4Address(e.getKey()) + "   | "
				+ IPv4.fromIPv4Address(ripE.nextHop) + "\t"
				+ ripE.cost + "\t"
				+ ripE.creationTime + "\t"
				+ IPv4.fromIPv4Address(ripE.destAddr) + "\t"
				+ ripE.subnetMask
			);
		}
	}

	/**
	 * Handles the incoming RIP request/response packet.
	 */
	private void handleRipPacket(byte type, Ethernet etherPacket, Iface inIface) {
		switch (type) {
			case RIPv2.COMMAND_REQUEST:
				if (dbg) System.out.println("received rip request, sending rip response");
				sendRipPacket(RIPType.RIP_SOL_RESPONSE, etherPacket, inIface);
				break;
			case RIPv2.COMMAND_RESPONSE:
				if (dbg) System.out.println("received rip response, updating rip map + route table");
				IPv4 ip = (IPv4) etherPacket.getPayload();
				UDP udp = (UDP) ip.getPayload();
				RIPv2 ripv2 = (RIPv2) udp.getPayload();

				List<RIPv2Entry> entries = ripv2.getEntries();
				for (RIPv2Entry newEntry : entries) {
					int newEntryNetAddr = newEntry.getAddress() & newEntry.getSubnetMask();

					if (ripMap.containsKey(newEntryNetAddr)) { // there is an existing route
						RipEntry localEntry = ripMap.get(newEntryNetAddr);

						if (newEntry.getMetric() + 1 < localEntry.cost) { // better route found
							updateExistingRoute(newEntry, localEntry, ip, inIface);
						}
					} else { // this is a new route
						addNewRoute(newEntry, ip, inIface);
					}
				}
				break;
			default:
				break;
		}
	}

	private void updateExistingRoute(RIPv2Entry newEntry, RipEntry localEntry, IPv4 ip, Iface iface) {
		if (dbg) System.out.println("updating existing route");

		localEntry.creationTime = System.currentTimeMillis();
		localEntry.cost = newEntry.getMetric() + 1;
		localEntry.nextHop = ip.getSourceAddress();

		routeTable.update(newEntry.getAddress(), newEntry.getSubnetMask(), ip.getSourceAddress(), iface);
	}

	private void addNewRoute(RIPv2Entry newEntry, IPv4 ip, Iface iface) {
		if (dbg) System.out.println("adding new route");

		int nextHop = ip.getSourceAddress();
		int cost = newEntry.getMetric() + 1;
		long creationTime = System.currentTimeMillis();
		int destAddr = newEntry.getAddress();
		int subnetMask = newEntry.getSubnetMask();
		int destNetAddr = destAddr & subnetMask;

		ripMap.put(destNetAddr, new RipEntry(nextHop, cost, creationTime, destAddr, subnetMask));
		if (cost < 16) routeTable.insert(destAddr, nextHop, subnetMask, iface);
	}

	/**
	 * Constructs a RIP packet of the corresponding type and sends it out.
	 */
	private void sendRipPacket(RIPType type, Ethernet etherPacket, Iface inIface) {
		Ethernet ether = new Ethernet();
		IPv4 ip = new IPv4();
		UDP udp = new UDP();
		RIPv2 ripv2 = new RIPv2();

		// populate ethernet header
		ether.setEtherType(Ethernet.TYPE_IPv4);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());

		// populate ip header
		ip.setProtocol(IPv4.PROTOCOL_UDP);
		ip.setTtl((byte) 64);
		ip.setSourceAddress(inIface.getIpAddress());
		
		switch (type) {
			case RIP_SOL_RESPONSE:
				IPv4 ipv4 = (IPv4) etherPacket.getPayload();
				ether.setDestinationMACAddress(ether.getSourceMACAddress());
				ip.setDestinationAddress(ipv4.getSourceAddress());
				ripv2.setCommand(RIPv2.COMMAND_RESPONSE);
				break;
			case RIP_UNSOL_RESPONSE:
				ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
				ip.setDestinationAddress("224.0.0.9");
				ripv2.setCommand(RIPv2.COMMAND_RESPONSE);
				break;
			case RIP_REQUEST:
				ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
				ip.setDestinationAddress("224.0.0.9");
				ripv2.setCommand(RIPv2.COMMAND_REQUEST);
				break;
			default:
				break;
		}

		// populate udp header
		udp.setSourcePort((short) 520);
		udp.setDestinationPort((short) 520);

		// populate rip header
		List<RIPv2Entry> entries = new ArrayList<RIPv2Entry>();
		for (RipEntry localEntry : ripMap.values()) {
			RIPv2Entry entry = new RIPv2Entry();
			entry.setAddress(localEntry.destAddr);
			entry.setMetric(localEntry.cost);
			entry.setSubnetMask(localEntry.subnetMask);
			entries.add(entry);
		}

		ripv2.setEntries(entries);
		udp.setPayload(ripv2);
		ip.setPayload(udp);
		ether.setPayload(ip);

		sendPacket(ether, inIface);
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
				// we need to check if the incoming IPv4 packet contains a RIP packet
				IPv4 ip = (IPv4) etherPacket.getPayload();
				if (ip.getDestinationAddress() == IPv4.toIPv4Address("224.0.0.9") && ip.getProtocol() == IPv4.PROTOCOL_UDP) {
					UDP udp = (UDP) ip.getPayload();
					if (udp.getDestinationPort() == 520) {
						RIPv2 ripv2 = (RIPv2) udp.getPayload();
						handleRipPacket(ripv2.getCommand(), etherPacket, inIface);
						break;
					}
				}

				// else it is just a normal IP packet
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
		ARP arpPacket = (ARP) etherPacket.getPayload();
		int targetIp = ByteBuffer.wrap(arpPacket.getTargetProtocolAddress()).getInt();
		
		for (Iface iface : interfaces.values()) {
			if (targetIp == iface.getIpAddress()) {
				if (arpPacket.getOpCode() == ARP.OP_REQUEST) {
					sendArpPacket(ARPType.ARP_REPLY, etherPacket, inIface, inIface, 0);
					break;
				} else if (arpPacket.getOpCode() == ARP.OP_REPLY) {
					// get the sender MAC and IP address from the arp packet
					MACAddress mac = new MACAddress(arpPacket.getSenderHardwareAddress());
					Integer ip = ByteBuffer.wrap(arpPacket.getSenderProtocolAddress()).getInt();
		
					// add this to the arp cache
					arpCache.insert(mac, ip);
		
					// send all the pending packets in the queue to the newly added arp cache entry
					List<Ethernet> queue = packetQueueMap.remove(ip);
					if (queue == null) return;
					for (Ethernet packet : queue) {
						packet.setDestinationMACAddress(mac.toString());
						sendPacket(packet, inIface);
					}
					break;
				}
			}
		}

		return;
	}

	/**
	 * Constructs and sends ARP replies and requests.
	 * @param type the type of ARP packet to construct
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the port on which the packet was received
	 * @param outIface the port on which to send the ARP packet
	 * @param ip the IP address whose associated MAC we desire
	 */
	private void sendArpPacket(ARPType type, Ethernet etherPacket, Iface inIface, Iface outIface, int ip) {
		ARP arpPacket = null;
		if (type == ARPType.ARP_REPLY) arpPacket = (ARP) etherPacket.getPayload();
		
		Ethernet ether = new Ethernet();
		ARP arp = new ARP();
		
		// populate ethernet header fields
		ether.setEtherType(Ethernet.TYPE_ARP);
		ether.setSourceMACAddress(inIface.getMacAddress().toBytes());
		if (type == ARPType.ARP_REPLY) ether.setDestinationMACAddress(etherPacket.getSourceMACAddress());
		if (type == ARPType.ARP_REQUEST) ether.setDestinationMACAddress("FF:FF:FF:FF:FF:FF");
	
		// populate ARP header
		arp.setHardwareType(ARP.HW_TYPE_ETHERNET);
		arp.setProtocolType(ARP.PROTO_TYPE_IP);
		arp.setHardwareAddressLength((byte) Ethernet.DATALAYER_ADDRESS_LENGTH);
		arp.setProtocolAddressLength((byte) 4);

		if (type == ARPType.ARP_REPLY) arp.setOpCode(ARP.OP_REPLY);
		if (type == ARPType.ARP_REQUEST) arp.setOpCode(ARP.OP_REQUEST);

		arp.setSenderHardwareAddress(inIface.getMacAddress().toBytes());
		arp.setSenderProtocolAddress(inIface.getIpAddress());

		if (type == ARPType.ARP_REPLY) {
			arp.setTargetHardwareAddress(arpPacket.getSenderHardwareAddress());
			arp.setTargetProtocolAddress(arpPacket.getSenderProtocolAddress());
		}

		if (type == ARPType.ARP_REQUEST) {
			arp.setTargetHardwareAddress(Ethernet.toMACAddress("00:00:00:00:00:00"));
			arp.setTargetProtocolAddress(ip);
		}

		ether.setPayload(arp);
	
		sendPacket(ether, outIface);
	}

	/**
	 * Enqueue incoming packet and generate ARP request on ARP cache miss.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the port on which the packet was received
	 * @param outIface the port on which to send the ARP packet
	 * @param ip the IP address whose associated MAC we desire
	 */
	private void handleArpMiss(final Ethernet etherPacket, final Iface inIface, final Iface outIface, final int ip) {
		IPv4 ipPacket = (IPv4) etherPacket.getPayload();
		Integer ipDestAddr = (Integer) ipPacket.getDestinationAddress();

		RouteEntry re = routeTable.lookup(ipDestAddr);
		if (re == null) return;
		
		// check for the ip of the next hop to take to reach the packet's destination
		Integer ipNextHopAddr = re.getGatewayAddress();
		if (ipNextHopAddr == 0) ipNextHopAddr = ipDestAddr;

		final int nextIp = ipNextHopAddr;
		if (packetQueueMap.containsKey(nextIp)) { // there is already an existing queue for that next hop ip destination so we just add the incoming packet to that queue
			packetQueueMap.get(nextIp).add(etherPacket);
		} else { // we create a packet queue for that ip
			packetQueueMap.put(nextIp, new ArrayList<Ethernet>());
			packetQueueMap.get(nextIp).add(etherPacket);

			// we use a timer task (similar functionality to a thread) to send ARP requests to the destination until we get a reply or till 3 requests has been sent
			// we send a DEST_NET_UNREACHABLE icmp message after sending 3 requests
			TimerTask task = new TimerTask() {
				int cnt = 0;
				public void run() {
					if (arpCache.lookup(nextIp) != null) {
						this.cancel();
					} else {
						if (cnt > 2) {
							List<Ethernet> removedQ = packetQueueMap.remove(nextIp);
							for (Ethernet ether : removedQ) {
								sendICMP(ICMPMessageType.DEST_HOST_UNREACHABLE, ether, inIface);
							}

							this.cancel();
						} else {
							sendArpPacket(ARPType.ARP_REQUEST, etherPacket, inIface, outIface, ip);
							cnt++;
						}
					}
				}
			};
			Timer timer = new Timer(true);
			timer.schedule(task, 0, 1000);
		}
	}
	
	/**
	 * Handles the incoming IP packet.
	 * @param etherPacket the Ethernet packet that was received
	 * @param inIface the port on which the packet was received
	 */
	private void handleIpPacket(Ethernet etherPacket, Iface inIface) {
		for (Iface iface : interfaces.values()) {
			arpCache.insert(iface.getMacAddress(), iface.getIpAddress());
		}

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
			sendICMP(ICMPMessageType.TIME_EXCEEDED, etherPacket, inIface);
			return;
		}

		// check if destination IP exactly matches one of the interface's IP
		int ipPacketDestIP = ipPacket.getDestinationAddress();
		for (Iface iface : interfaces.values()) {
			if (iface.getIpAddress() == ipPacketDestIP) {
				byte protocol = ipPacket.getProtocol();
				
				if (protocol == IPv4.PROTOCOL_UDP || protocol == IPv4.PROTOCOL_TCP) {
					sendICMP(ICMPMessageType.DEST_PORT_UNREACHABLE, etherPacket, inIface);
				} else if (protocol == IPv4.PROTOCOL_ICMP) {
					// get icmp packet from the ip packet
					ICMP icmpPacket = (ICMP) ipPacket.getPayload();
					if (icmpPacket.getIcmpType() == ICMP.TYPE_ECHO_REQUEST) {
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
			Iface outIface = re.getInterface();
			if (inIface == outIface) return;

			ArpEntry ae = null;
			int nextIp = -1;
			if (re.getGatewayAddress() != 0) { // destination IP is in another network and we need to move across routers to reach it
				nextIp = re.getGatewayAddress();
			} else { // destination IP is on the local network
				nextIp = ipPacketDestIP;
			}
			ae = arpCache.lookup(nextIp);

			if (ae == null)	{
				handleArpMiss(etherPacket, inIface, outIface, nextIp);
				return;
			}

			// updated the Ethernet header with the new source and destination MACs
			newPacket = newPacket.setSourceMACAddress(outIface.getMacAddress().toBytes());
			newPacket = newPacket.setDestinationMACAddress(ae.getMac().toBytes());

			// send packet
			sendPacket(newPacket, re.getInterface());
		} else {
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
			int nextIp = -1;
			if (re.getGatewayAddress() != 0) {
				nextIp = re.getGatewayAddress();
			} else {
				nextIp = ipPacket.getSourceAddress();
			}
			ae = arpCache.lookup(nextIp);

			if (ae == null) {
				handleArpMiss(etherPacket, inIface, inIface, nextIp);
				return;
			}
			
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
