package edu.wisc.cs.sdn.apps.loadbalancer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Arrays;

import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.action.OFActionSetField;

import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;
import org.openflow.protocol.instruction.OFInstructionGotoTable;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPacketIn;
import org.openflow.protocol.OFType;
import org.openflow.protocol.OFPort;
import org.openflow.protocol.OFOXMFieldType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.ArpServer;
import edu.wisc.cs.sdn.apps.util.SwitchCommands;
import edu.wisc.cs.sdn.apps.l3routing.L3Routing;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.internal.DeviceManagerImpl;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.util.MACAddress;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.ARP;

public class LoadBalancer implements IFloodlightModule, IOFSwitchListener, IOFMessageListener {
	public static final String MODULE_NAME = LoadBalancer.class.getSimpleName();
	
	private static final byte TCP_FLAG_SYN = 0x02;
	
	private static final short IDLE_TIMEOUT = 20;
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;
    
    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Set of virtual IPs and the load balancer instances they correspond with
    private Map<Integer,LoadBalancerInstance> instances;

    /**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info(String.format("Initializing %s...", MODULE_NAME));
		
		// Obtain table number from config
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
        // Create instances from config
        this.instances = new HashMap<Integer,LoadBalancerInstance>();
        String[] instanceConfigs = config.get("instances").split(";");
        for (String instanceConfig : instanceConfigs)
        {
        	String[] configItems = instanceConfig.split(" ");
        	if (configItems.length != 3)
        	{ 
        		log.error("Ignoring bad instance config: " + instanceConfig);
        		continue;
        	}
        	LoadBalancerInstance instance = new LoadBalancerInstance(
        			configItems[0], configItems[1], configItems[2].split(","));
            this.instances.put(instance.getVirtualIP(), instance);
            log.info("Added load balancer instance: " + instance);
        }
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        
        /*********************************************************************/
        /* Initialize other class variables, if necessary                    */
        /*********************************************************************/
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.floodlightProv.addOFMessageListener(OFType.PACKET_IN, this);
		
		/*********************************************************************/
		/* Perform other tasks, if necessary                                 */
		/*********************************************************************/
	}
	
	/**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override
	public void switchAdded(long switchId) {
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************************************/
		/* Install rules to send:                                                                    */
		/*       (1) packets from new connections to each virtual load balancer IP to the controller */
		/*       (2) ARP packets to the controller                                                   */
		/*       (3) all other packets to the next rule table in the switch                          */
		/*																							 */
		/* Rules related to case (3) will have default priority as per writeup 						 */
		/* Rules related to case (1) and (2) will have a higher priority							 */
		/* 																							 */
		/* This makes sense because if its 'hoppable', we want to send it on its way				 */
		/* Packets that can't be found, means they are new connections and we handle case (1) & (2)  */
		/*********************************************************************************************/

		// install rules to send all other packets to the next rule table in the switch
		OFMatch matchAllOther = new OFMatch();
		OFInstruction instrAllOther = new OFInstructionGotoTable(L3Routing.table);
		SwitchCommands.installRule(
			sw,
			this.table,
			SwitchCommands.DEFAULT_PRIORITY,
			matchAllOther,
			Arrays.asList(instrAllOther)
		);
		
		// we need to install rules for each load balancer instance virtual IP
		for (Integer virtualIp : this.instances.keySet()) {
			// create rules to send packets from new connections
			OFMatch matchNew = new OFMatch();
			matchNew.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
			matchNew.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
			matchNew.setNetworkDestination(virtualIp);

			OFAction actionNew = new OFActionOutput(OFPort.OFPP_CONTROLLER);
			OFInstruction instrNew = new OFInstructionApplyActions(Arrays.asList(actionNew));

			// create rules to send ARP packets
			OFMatch matchArp = new OFMatch();
			matchArp.setDataLayerType(OFMatch.ETH_TYPE_ARP);
			matchArp.setNetworkDestination(virtualIp);

			OFAction actionArp = new OFActionOutput(OFPort.OFPP_CONTROLLER);
			OFInstruction instrArp = new OFInstructionApplyActions(Arrays.asList(actionArp));

			// install the rules
			SwitchCommands.installRule(
				sw,
				this.table,
				(short) (SwitchCommands.DEFAULT_PRIORITY + 1),
				matchNew,
				Arrays.asList(instrNew)
			);

			SwitchCommands.installRule(
				sw,
				this.table,
				(short) (SwitchCommands.DEFAULT_PRIORITY + 1),
				matchArp,
				Arrays.asList(instrArp)
			);
		}
	}
	
	/**
	 * Handle incoming packets sent from switches.
	 * @param sw switch on which the packet was received
	 * @param msg message from the switch
	 * @param cntx the Floodlight context in which the message should be handled
	 * @return indication whether another module should also process the packet
	 */
	@Override
	public net.floodlightcontroller.core.IListener.Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		// We're only interested in packet-in messages
		if (msg.getType() != OFType.PACKET_IN)
		{ return Command.CONTINUE; }
		OFPacketIn pktIn = (OFPacketIn)msg;
		
		// Handle the packet
		Ethernet ethPkt = new Ethernet();
		ethPkt.deserialize(pktIn.getPacketData(), 0,
				pktIn.getPacketData().length);
		
		/*****************************************************************************************************/
		/* For ARP requests, send an ARP reply for virtual IPs              								 */
		/* For TCP SYNs, select a host and install connection-specific rules to rewrite IP and MAC addresses */
		/* Ignore all other packets                                                                          */
		/*  																								 */
		/* These rules need to be a higher priority than the previous cases in switchAdded()				 */
		/*****************************************************************************************************/
		switch(ethPkt.getEtherType()) { 
			case Ethernet.TYPE_ARP:
				ARP arpPacket = (ARP) ethPkt.getPayload();
				int virtualIp = IPv4.toIPv4Address(arpPacket.getTargetProtocolAddress());

				if (this.instances.containsKey(virtualIp)) {
					Ethernet ether = generateArpReply(ethPkt, arpPacket, virtualIp);
					SwitchCommands.sendPacket(sw, (short) pktIn.getInPort(), ether);	
				}

				break;
			case Ethernet.TYPE_IPv4:
				IPv4 ipPacket = (IPv4) ethPkt.getPayload();
				if (ipPacket.getProtocol() == IPv4.PROTOCOL_TCP) {
					TCP tcpPacket = (TCP) ipPacket.getPayload();
					if (tcpPacket.getFlags() == TCP_FLAG_SYN) {
						int destinationIP = ipPacket.getDestinationAddress();
						if (this.instances.containsKey(destinationIP)) {
							LoadBalancerInstance instance = this.instances.get(destinationIP);
							
							// create connection-specific rules to rewrite destination IP and MAC from client to virtual IP
							int nextHopIp = instance.getNextHostIP();
							OFInstruction defaultInstr = new OFInstructionGotoTable(L3Routing.table); // these are the instructions to get the next hop

							OFMatch matchToVirt = new OFMatch();
							matchToVirt.setDataLayerType(Ethernet.TYPE_IPv4);
							matchToVirt.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
							matchToVirt.setNetworkSource(ipPacket.getSourceAddress());
							matchToVirt.setNetworkDestination(destinationIP);
							matchToVirt.setTransportSource(OFMatch.IP_PROTO_TCP, tcpPacket.getSourcePort());
							matchToVirt.setTransportDestination(OFMatch.IP_PROTO_TCP, tcpPacket.getDestinationPort());

							OFAction ipActionToVirt = new OFActionSetField(OFOXMFieldType.IPV4_DST, nextHopIp);
							OFAction macActionToVirt = new OFActionSetField(OFOXMFieldType.ETH_DST, this.getHostMACAddress(nextHopIp));

							OFInstruction instrToVirt = new OFInstructionApplyActions(Arrays.asList(ipActionToVirt, macActionToVirt));

							// create connection-specific rules to rewrite source IP and MAC from server to client
							OFMatch matchToClient = new OFMatch();
							matchToClient.setDataLayerType(Ethernet.TYPE_IPv4);
							matchToClient.setNetworkProtocol(OFMatch.IP_PROTO_TCP);
							matchToClient.setNetworkSource(nextHopIp);
							matchToClient.setNetworkDestination(ipPacket.getSourceAddress());
							matchToClient.setTransportSource(OFMatch.IP_PROTO_TCP, tcpPacket.getDestinationPort());
							matchToClient.setTransportDestination(OFMatch.IP_PROTO_TCP, tcpPacket.getSourcePort());

							OFAction ipActionToClient = new OFActionSetField(OFOXMFieldType.IPV4_SRC, destinationIP);
							OFAction macActionToClient = new OFActionSetField(OFOXMFieldType.ETH_SRC, instance.getVirtualMAC());

							OFInstruction instrToClient = new OFInstructionApplyActions(Arrays.asList(ipActionToClient, macActionToClient));
							
							// install all created rules
							SwitchCommands.installRule(
								sw,
								this.table,
								(short) (SwitchCommands.DEFAULT_PRIORITY + 2),
								matchToVirt,
								Arrays.asList(instrToVirt, defaultInstr),
								SwitchCommands.NO_TIMEOUT, 
								IDLE_TIMEOUT
							);

							SwitchCommands.installRule(
								sw,
								this.table,
								(short) (SwitchCommands.DEFAULT_PRIORITY + 2),
								matchToClient,
								Arrays.asList(instrToClient, defaultInstr),
								SwitchCommands.NO_TIMEOUT, 
								IDLE_TIMEOUT
							);
						}
					}
				}
				break;
		}
		
		// ignore other packets
		return Command.CONTINUE;
	}

	/**
	 * Helper function for receive() to generate ether packet.
	 * @param ethPacket the incoming Ethernet packet
	 * @param arpPacket the incoming ARP packet
	 * @param virtualIp the virtual IP the packet is being received from
	 * @return
	 */
	private Ethernet generateArpReply(Ethernet ethPacket, ARP arpPacket, int virtualIp) {
		Ethernet ether = new Ethernet();
		ARP arp = new ARP();    
		byte[] macAddress = this.instances.get(virtualIp).getVirtualMAC();

		// populate ethernet header fields
		ether.setEtherType(Ethernet.TYPE_ARP);
		ether.setSourceMACAddress(macAddress);
		ether.setDestinationMACAddress(ethPacket.getSourceMACAddress());

		// populate ARP header
		arp.setHardwareType(arpPacket.getHardwareType());
		arp.setProtocolType(arpPacket.getProtocolType());

		arp.setHardwareAddressLength(arpPacket.getHardwareAddressLength());
		arp.setProtocolAddressLength(arpPacket.getProtocolAddressLength());

		arp.setOpCode(ARP.OP_REPLY); 

		arp.setSenderHardwareAddress(macAddress);
		arp.setSenderProtocolAddress(virtualIp);
		arp.setTargetHardwareAddress(arpPacket.getSenderHardwareAddress());
		arp.setTargetProtocolAddress(arpPacket.getSenderProtocolAddress());

		// set payload
		ether.setPayload(arp);

		return ether;
	}
	
	/**
	 * Returns the MAC address for a host, given the host's IP address.
	 * @param hostIPAddress the host's IP address
	 * @return the hosts's MAC address, null if unknown
	 */
	private byte[] getHostMACAddress(int hostIPAddress) {
		Iterator<? extends IDevice> iterator = this.deviceProv.queryDevices(
				null, null, hostIPAddress, null, null);
		if (!iterator.hasNext())
		{ return null; }
		IDevice device = iterator.next();
		return MACAddress.valueOf(device.getMACAddress()).toBytes();
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) { /* Unneeded, since the switch is no longer active */ }

	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId) { /* Unneeded, since we're not switching controller roles */ }

	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,PortChangeType type) { /* Unneeded, since load balancer rules are port-agnostic */}

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) { /* Unneeded */ }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() { return null; }

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() { return null; }

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService >> floodlightService =
	        new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() { return MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return (OFType.PACKET_IN == type 
				&& (name.equals(ArpServer.MODULE_NAME) 
					|| name.equals(DeviceManagerImpl.MODULE_NAME))); 
	}

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) { return false; }
}
