package edu.wisc.cs.sdn.vnet.sw;

import net.floodlightcontroller.packet.Ethernet;
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
			this.birth = System.currentTimeMilis();
			this.portID = port;
		}
	}

	/**
	 * Port forwarding table;
	 * We use a concurrent hashmap because we are utilizing threads.
	 */
	private ConcurrentHashMap<MACAddress, PortInfo> table;

	/**
	 * Thread to check entry age at 1s intervals
	 */
	private Thread thread;

	/**
	 * Iterates through the table and checks the ages of the entries;
	 * Operates at 1s intervals (granularity).
	 */
	public void run() {
		try {
			while(true) {
				for (Map.Entry<MACAddress, PortInfo> entry : table) {
					PortInfo info = entry.getValue();
					MACAddresss mac = entry.getKey();
					
					// calculate the age of the entry
					long age = System.currentTimeMilis() - info.birth;

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
		System.out.println("*** -> Received packet: " +
                etherPacket.toString().replace("\n", "\n\t"));

		// whenever we receive a packet, we learn its MAC
		learn(etherPacket, inIface);

		// we look for the destination MAC in our table
		// if there is a match we forward, else broadcast
		Iface outputPort = search(etherPacket.getDestinationMAC());
		if (outputPort != null) {
			sendPacket(etherPacket, outputPort);
		} else {
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
		// create the PortInfo
		PortInfo info = new PortInfo(port);
		MACAddress macAddr = etherPacket.getSourceMAC();

		// put entry into table
		table.put(macAddr, info);
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
		for (Map.Entry<String, Iface> entry : ports) {
			// skip the port which we received the packet from
			Iface p = entry.getValue();
			if (!p.equals(port)) sendPacket(etherPacket, p);
		}

	}

	/**
	 * Searches the port forwarding table for the provided MAC address.
	 * @param mac the MAC address to look for
	 * @return the found port Iface object, otherwise null
	 */
	private Iface searchTable(MACAddress mac) {
		if (table.containsKey(mac)) {
			PortInfo info = table.get(mac);

			// age should be reset when the MAC address is used
			info.birth = System.currentTimeMilis();

			return info.portID;
		} else {
			return null;
		}
	}
}

