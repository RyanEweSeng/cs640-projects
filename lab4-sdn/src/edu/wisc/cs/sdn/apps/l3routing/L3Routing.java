package edu.wisc.cs.sdn.apps.l3routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.wisc.cs.sdn.apps.util.Host;
import edu.wisc.cs.sdn.apss.util.SwitchCommands;

import org.openflow.protocol.OFMatch;
import org.openflow.protocol.action.OFAction;
import org.openflow.protocol.action.OFActionOutput;
import org.openflow.protocol.instruction.OFInstruction;
import org.openflow.protocol.instruction.OFInstructionApplyActions;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;

public class L3Routing implements IFloodlightModule, IOFSwitchListener, ILinkDiscoveryListener, IDeviceListener {
    public static final String MODULE_NAME = L3Routing.class.getSimpleName();
	
    // Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;

    // Interface to link discovery service
    private ILinkDiscoveryService linkDiscProv;

    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    public static byte table;
    
    // Map of hosts to devices
    private Map<IDevice, Host> knownHosts;

	/**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info(String.format("Initializing %s...", MODULE_NAME));
		
        Map<String,String> config = context.getConfigParams(this);
        table = Byte.parseByte(config.get("table"));
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        
        this.knownHosts = new ConcurrentHashMap<IDevice,Host>();
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context) throws FloodlightModuleException {
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);
	}
	
    /**
     * Get a list of all known hosts in the network.
     */
    private Collection<Host> getHosts() { return this.knownHosts.values(); }
	
    /**
     * Get a map of all active switches in the network. Switch DPID is used as
     * the key.
     */
	private Map<Long, IOFSwitch> getSwitches() { return floodlightProv.getAllSwitchMap(); }
	
    /**
     * Get a list of all active links in the network.
     */
    private Collection<Link> getLinks() { return linkDiscProv.getLinks().keySet(); }

    /**
     * Finds the shortest routes using the Bellman-Ford algorithm.
     */
    private Map<Long, Integer> bellmanford(IOFSwitch start) {
        Map<Long, Integer> dist = new ConcurrentHashMap<Long, Integer>();
        Map<Long, Integer> prev = new ConcurrentHashMap<Long, Integer>();

        Queue<Long> queue = new LinkedList<Long>();

        Collection<Link> edges;

        // set start node to 0 and every other node to inf
        for (IOFSwitch s : this.getSwitches().values()) {
            dist.put(s.getId(), Integer.MAX_VALUE);
        }
        dist.put(start.getId(), 0);

        // begin bellman-ford
        for (int i = 0; i < this.getSwitches().size(); i++) {
            edges = this.cleanLinks();
            queue.add(start.getId()); 

            while (!queue.isEmpty()) {
                long currID = queue.remove();
                
                // find all the edges of the current node
                Collection<Link> currEdges = new ArrayList<Link>();
                for (Link edge : edges) {
                    if (edge.getSrc() == currID || edge.getDst() == currID) currEdges.add(edge);
                }

                for (Link edge : currEdges) {
                    int currDist = dist.get(currID);
                    long nextNode = -1;
                    int nextDist = -1;
                    int nextNodePort = -1;

                    // update procedure for Bellman-Ford
                    // we do this because of the Link objects that Floodlight uses
                    if (currID == edge.getSrc()) {
                        nextNode = edge.getDst();
                        nextDist = dist.get(nextNode);
                        nextNodePort = edge.getDstPort();
                    } else {
                        nextNode = edge.getSrc();
                        nextDist = dist.get(nextNode);
                        nextNodePort = edge.getSrcPort();
                    }

                    if (currDist + 1 < nextDist) {
                        dist.put(nextNode, currDist + 1);
                        prev.put(nextNode, nextNodePort);
                    }

                    queue.add(nextNode);
                    edges.remove(edge);
                }
            }
        }

        return prev;
    }

    /**
     * This is a helper function to clean up the links as Floodlight uses two Link objects to represent an edge.
     */
    private Collection<Link> cleanLinks() {
        Collection<Link> newLinks = new ArrayList<Link>();
        Collection<Link> ogLinks = this.getLinks();

        for (Link ogL : ogLinks) {
            if (newLinks.isEmpty()) {
                newLinks.add(ogL);
                continue;
            }

            for (Link newL : newLinks) {
                if (ogL.getSrc() == newL.getSrc() && ogL.getDst() == newL.getDst() ||
                    ogL.getSrc() == newL.getDst() && ogL.getDst() == newL.getSrc()) {
                    break;
                } else {
                    newLinks.add(ogL);
                    break;
                }
            }
        }

        return newLinks;
    }

    /**
     * Adds a rule to the table.
     */
    private void addRule(Host host) {
        // get the shortest routes to all other nodes from the host
        Map<Long, Integer> shortestRoutes = this.bellmanford(host.getSwitch());

        // the rule should match IP packets whose dest IP is the host IP
        // set etherType before dest IP
        OFMatch match = createMatch(host);

        // add a rule in table for every node in the path
        // a rule's action is output packets of the appropriate port to reach the next switch in route
        for (Long id : shortestRoutes.keySet()) {
            OFAction action = new OFActionOutput(shortestRoutes.get(id));
            OFInstruction instr = new OFInstructionApplyActions(Arrays.asList(action));

            SwitchCommands.installRule(
                this.getSwitches().get(id),
                this.table,
                SwitchCommands.DEFAULT_PRIORITY,
                match,
                Arrays.asList(instr),
                SwitchCommands.NO_TIMEOUT,
                SwitchCommands.NO_TIMEOUT
            );
        }

        // add the host to the switch table
        OFAction action = new OFActionOutput(shortestRoutes.get(host.getPort()));
        OFInstruction instr = new OFInstructionApplyActions(Arrays.asList(action));

        SwitchCommands.installRule(
            host.getSwitch(),
            this.table,
            SwitchCommands.DEFAULT_PRIORITY,
            match,
            Arrays.asList(instr),
            SwitchCommands.NO_TIMEOUT,
            SwitchCommands.NO_TIMEOUT
        );
    }

    /**
     * Helper function to create the OFMatch and sets the ether type and dest IP fields.
     */
    private OFMatch createMatch(Host host) {
        OFMatch m = new OFMatch();
        m.setDataLayerType(OFMatch.ETH_TYPE_IPV4);
        m.setNetworkDestination(host.getIPv4Address());

        return m;
    }

    /**
     * Event handler called when a host joins the network.
     * @param device information about the host
     */
	@Override
	public void deviceAdded(IDevice device) {
		Host host = new Host(device, this.floodlightProv);

		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null) {
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);

            // update routing: add rules to route to new host only if host is attached
            if (host.isAttachedToSwitch()) { this.addRule(host); }
		}
	}

	/**
     * Event handler called when a host is no longer attached to a switch.
     * @param device information about the host
     */
	@Override
	public void deviceRemoved(IDevice device) {
		Host host = this.knownHosts.get(device);

		if (null == host) { return; }

		this.knownHosts.remove(device);
		
		log.info(String.format("Host %s is no longer attached to a switch", host.getName()));
		
		// update routing: remove rules to route to host
        	for (IOFSwitch switch : this.getSwitches().values()) {
            		SwitchCommands.removeRules(switch, this.table, createMatch(host)); 
        	}
	}

	/**
     * Event handler called when a host moves within the network.
     * @param device information about the host
     */
	@Override
	public void deviceMoved(IDevice device) {
		Host host = this.knownHosts.get(device);
		if (null == host) {
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		if (!host.isAttachedToSwitch()) {
			this.deviceRemoved(device);
			return;
		}

		log.info(String.format("Host %s moved to s%d:%d", host.getName(), host.getSwitch().getId(), host.getPort()));
		
		// update routing: change rules to route to host
        for (IOFSwitch switch : this.getSwitches().values()) {
            SwitchCommands.removeRules(switch, this.table, createMatch(host));
        } 
        if (host.isAttachedToSwitch()) { this.addRule(host); }
	}
	
    /**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override		
	public void switchAdded(long switchId) {
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		// update routing: change routing rules for all hosts
        for (Host h : this.getHosts()) {
            for (IOFSwitch s : this.getSwitches.values()) {
                SwitchCommands.removeRules(s, this.table, createMatch(h));
            }
            if (h.isAttachedToSwitch()) { this.addRule(h); }
        }
	}

	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) {
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d removed", switchId));
		
		// update routing: change routing rules for all hosts
		for (Host h : this.getHosts()) {
            for (IOFSwitch s : this.getSwitches.values()) {
                SwitchCommands.removeRules(s, this.table, createMatch(h));
            }
            if (h.isAttachedToSwitch()) { this.addRule(h); }
        }
	}

	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		for (LDUpdate update : updateList) {
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			if (0 == update.getDst()) {
				log.info(String.format("Link s%s:%d -> host updated", 
					update.getSrc(), update.getSrcPort()));
			}
			// Otherwise, the link is between two switches
			else {
				log.info(String.format("Link s%s:%d -> s%s:%d updated", 
					update.getSrc(), update.getSrcPort(),
					update.getDst(), update.getDstPort()));
			}
		}
		
		// update routing: change routing rules for all hosts
		for (Host h : this.getHosts()) {
            for (IOFSwitch s : this.getSwitches.values()) {
                SwitchCommands.removeRules(s, this.table, createMatch(h));
            }
            if (h.isAttachedToSwitch()) { this.addRule(h); }
        }
	}

	/**
	 * Event handler called when link goes up or down.
	 * @param update information about the change in link state
	 */
	@Override
	public void linkDiscoveryUpdate(LDUpdate update) { this.linkDiscoveryUpdate(Arrays.asList(update)); }
	
	/**
     * Event handler called when the IP address of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceIPV4AddrChanged(IDevice device) { this.deviceAdded(device); }

	/**
     * Event handler called when the VLAN of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceVlanChanged(IDevice device) { /* Nothing we need to do */ }
	
	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId) { /* Nothing we need to do */ }

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) { /* Nothing we need to do */ }
	
	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port, PortChangeType type) { /* Nothing we need to do */ }

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() { return this.MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(String type, String name) { return false; }

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(String type, String name) { return false; }
	
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
		Collection<Class<? extends IFloodlightService >> floodlightService = new ArrayList<Class<? extends IFloodlightService>>();
        floodlightService.add(IFloodlightProviderService.class);
        floodlightService.add(ILinkDiscoveryService.class);
        floodlightService.add(IDeviceService.class);
        return floodlightService;
	}
}
