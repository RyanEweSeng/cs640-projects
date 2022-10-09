package edu.wisc.cs.sdn.vnet.rt;

import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;

/**
 * @author Aaron Gember-Jacobson and Anubhavnidhi Abhashkumar
 */
public class Router extends Device {	
	/** Routing table for the router */
	private RouteTable routeTable;
	
	/** ARP cache for the router */
	private ArpCache arpCache;

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
	 * @param inIface the interface on which the packet was received
	 */
	public void handlePacket(Ethernet etherPacket, Iface inIface) {
		if (dbg) {
			System.out.println("###########################################################");
			System.out.println("hostname: " + getHost());
			System.out.println("*** -> Received packet: " + etherPacket.toString().replace("\n", "\n\t") + "\n");
		}

		// check if it contains an IPv4 packet
		if (etherPacket.getEtherType() != Ethernet.TYPE_IPv4) return;

		// get the payload
		IPv4 header = (IPv4) etherPacket.getPayload();

		// store the checksum and zero it
		short checksum = header.getChecksum();
		header = header.setChecksum((short) 0);

		// compute the checksum
		byte[] serializedData = header.serialize();
		header = (IPv4) header.deserialize(serializedData, 0, serializedData.length);
		short computedChecksum = header.getChecksum();

		// verify checksum
		if (checksum != computedChecksum) return;

		// decrement the IPv4 packet's TTL by 1 and verify
		byte origTtl = header.getTtl();
		header = header.setTtl((byte)(origTtl - 1));
		if (header.getTtl() == 0) return;

		// check if destination IP exactly matches one of the interface's IP
		int headerDestIP = header.getDestinationAddress();
		for (Iface iface : interfaces.values()) {
			if (iface.getIpAddress() == headerDestIP) return;
		}

		if (dbg) {
			System.out.println("checking ether type = 0x" + Integer.toHexString(etherPacket.getEtherType() & 0xffff));
			System.out.println("checksum = " + checksum + "\tcomputedChecksum = " + computedChecksum);
			System.out.println("original ttl = " + (int) origTtl + "\tnew ttl = " + (int) header.getTtl());
			System.out.println("destination IP = " + header.fromIPv4Address(headerDestIP));
			System.out.println();
		}

		// Update header (wtf does serialize + deserialize do???) and create new packet
		header = header.setChecksum((short) 0);
		serializedData = header.serialize();
		header = (IPv4) header.deserialize(serializedData, 0, serializedData.length);
		Ethernet newPacket = (Ethernet) etherPacket.setPayload(header);
		
		if (dbg) System.out.print("searching route table...");

		// search for new src/dst MACs and forward new packet
		RouteEntry re = routeTable.lookup(headerDestIP);
		if (re != null) {
			if (dbg) { 
				System.out.println("entry found");
				System.out.print("searching ARP cache...");
			}

			ArpEntry ae = null;
			if (re.getGatewayAddress() != 0) {
				// destination IP is in another network and we need to move across routers to reach it
				ae = arpCache.lookup(re.getGatewayAddress());
				
				if (dbg) System.out.println("using gateway address..." + ae.getMac().toString());
			} else {
				// destination IP is on the local network
				ae = arpCache.lookup(headerDestIP);
				
				if (dbg) System.out.println("using destination address..." + ae.getMac().toString());
			}

			if (ae == null)	return;

			// updated the Ethernet header with the new source and destination MACs
			newPacket = newPacket.setSourceMACAddress(re.getInterface().getMacAddress().toBytes());
			newPacket = newPacket.setDestinationMACAddress(ae.getMac().toBytes());

			if (dbg) {
				System.out.println();
				System.out.println("*** -> New outgoing packet: " + newPacket.toString().replace("\n", "\n\t"));
			}

			// send packet
			sendPacket(newPacket, re.getInterface());
		} 
		
		return;
	}
}
