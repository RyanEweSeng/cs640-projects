package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.MACAddress;
import edu.wisc.cs.sdn.vnet.Device;
import edu.wisc.cs.sdn.vnet.DumpFile;
import edu.wisc.cs.sdn.vnet.Iface;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.Runnable;
import java.lang.Thread;

public class Switch extends Device implements Runnable {
	/**
	 * Helper class to encapsulate port info and check age.
	 */
	private class PortInfo {
		/**
		 * The time the MAC address was entered into the table.
		 */
		private long birth;

		/**
		 * Iface of the port that corresponds with the incoming packet.
		 */
		private Iface portID;

		/**
		 * Instantiates the age to the current time and portID to the incoming packet.
		 * @param port Iface of the incoming packet
		 */
		public PortInfo(Iface port) {
			this.birth = System.currentTimeMillis();
			this.portID = port;
		}
	}

	/**
	 * Port forwarding table;
	 * We use a concurrent hashmap because we are utilizing threads.
	 */
	private ConcurrentHashMap<MACAddress, PortInfo> table;

	/**
	 * Thread to check entry age at 1s intervals.
	 */
	private Thread thread;

	/**
	 * Flag to trigger debug statements (remember to set to false on submission).
	 */
	 private boolean dbg = true;

	/**
	 * Iterates through the table and checks the ages of the entries;
	 * Operates at 1s intervals (granularity).
	 */
	public void run() {
		try {
			while(true) {
				for (Map.Entry<MACAddress, PortInfo> entry : table.entrySet()) {
					PortInfo info = entry.getValue();
					MACAddress mac = entry.getKey();
					
					// calculate the age of the entry
					long age = System.currentTimeMillis() - info.birth;

					// remove entry greater than 15s
					if (age >= 15000) table.remove(mac);
				}

				// repeat this every 1s
				thread.sleep(1000);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Creates a router for a specific host.
	 * @param host hostname for the router
	 */
	public Switch(String host, DumpFile logfile) {
		super(host, logfile);

		// instantiate a concurrent hashmap and a thread (for timeout checking)
		table = new ConcurrentHashMap<MACAddress, PortInfo>();
		thread = new Thread(this);
		thread.start();
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
			System.out.println("initial table");
			printTable();
		}

		// whenever we receive a packet, we learn its MAC
		learn(etherPacket, inIface);

		if (dbg) {
			System.out.println("learning complete");
			printTable();
		}

		// we look for the destination MAC in our table
		Iface outputPort = searchTable(etherPacket);

		if (dbg) {
			System.out.println("searching complete");
			printTable();
		}
		
		// if there is a match we forward, else broadcast
		if (outputPort != null) {
			if (dbg) System.out.println("forwarding...");
			sendPacket(etherPacket, outputPort);
		} else {
			if (dbg) System.out.println("broadcasting...");
			broadcast(etherPacket, inIface);
		}
	}

	/**
	 * Adds the source MAC address of the incoming packet along with its port info;
	 * Stored as an entry in a table using a HashMap.
	 * @param etherPacket the incoming packet
	 * @param port the port we received the packet from
	 */
	private void learn(Ethernet etherPacket, Iface port) {
		if (dbg) System.out.print("learning...");

		MACAddress macAddr = etherPacket.getSourceMAC();

		// we only want to learn a new mac
		if (!table.containsKey(macAddr)) {
			if (dbg) System.out.println("learnt new MAC");

			PortInfo info = new PortInfo(port);
			table.put(macAddr, info);

			return;
		}

		if (dbg) System.out.println("already learnt");
	}

	/**
	 * Sends the incoming packet to all other ports on the switch;
	 * Does not send the packet to the port which the incoming packet originated.
	 * @param etherPacket the packet to be broadcasted
	 * @param port the port the packet was received and to be excluded from broadcast
	 */
	private void broadcast(Ethernet etherPacket, Iface port) {
		// iterate through all ports in the switch
		Map<String, Iface> ports = getInterfaces();
		for (Map.Entry<String, Iface> entry : ports.entrySet()) {
			Iface p = entry.getValue();
			// skip the port which we received the packet from
			if (!p.equals(port)) sendPacket(etherPacket, p);
		}

	}

	/**
	 * Searches the port forwarding table for the provided MAC address.
	 * @param etherPacket the incoming packet whose srcMAC and dstMAC are needed
	 * @return the found port Iface object, otherwise null
	 */
	private Iface searchTable(Ethernet etherPacket) {
		if (dbg) System.out.print("searching...");

		MACAddress src = etherPacket.getSourceMAC();
		MACAddress dst = etherPacket.getDestinationMAC();
		
		// we reset the age for the source MAC
		if (table.containsKey(src)) {
			if (dbg) System.out.print("resetting source MAC (" + src.toString() + ") age...");

			PortInfo info = table.get(src);
			info.birth = System.currentTimeMillis();
		}

		// we search for the destination MAC in the table
		if (table.containsKey(dst)) {
			if (dbg) System.out.println("found");

			PortInfo info = table.get(dst);
			return info.portID;
		} else {
			if (dbg) System.out.println("not found");

			return null;
		}
	}

	/**
	 * Helper function to display the port forwarding table.
	 */
	private void printTable() {
		System.out.println("-------------------------------------");
		for (Map.Entry<MACAddress, PortInfo> entry : table.entrySet()) {
			String mac = entry.getKey().toString();
			String port = entry.getValue().portID.getName();
			long age = System.currentTimeMillis() - entry.getValue().birth;
			System.out.println(mac + "\t" + port + "\t" + age);
		}
		System.out.println("-------------------------------------\n");
	}
}

