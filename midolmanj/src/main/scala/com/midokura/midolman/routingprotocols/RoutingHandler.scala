/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.routingprotocols

import akka.actor.{UntypedActorWithStash, ActorLogging}
import com.google.inject.Inject
import com.midokura.midonet.cluster.{Client, DataClient}
import com.midokura.midolman.topology.VirtualTopologyActor
import java.util.UUID
import com.midokura.midonet.cluster.client.{Port, ExteriorRouterPort, BGPListBuilder}
import com.midokura.midonet.cluster.data.{Route, AdRoute, BGP}
import collection.mutable
import java.io.File
import org.newsclub.net.unix.AFUNIXSocketAddress
import com.midokura.quagga._
import com.midokura.midolman.{PortOperation, DatapathController, FlowController}
import com.midokura.sdn.dp.Ports
import com.midokura.sdn.flows.{WildcardFlow, WildcardMatch}
import com.midokura.packets._
import com.midokura.midolman.datapath.FlowActionOutputToVrnPort
import com.midokura.sdn.dp.flows.{FlowActionUserspace, FlowActions}
import com.midokura.sdn.dp.ports.InternalPort
import com.midokura.quagga.ZebraProtocol.RIBType
import com.midokura.midolman.topology.VirtualTopologyActor.PortRequest
import com.midokura.midolman.FlowController.AddWildcardFlow
import com.midokura.midolman.DatapathController.CreatePortInternal
import com.midokura.midolman.DatapathController.PortInternalOpReply

/**
 * The RoutingHandler manages the routing protocols for a single exterior
 * virtual router port that is local to this MidoNet daemon's physical host.
 * Currently, only BGP is supported, but we may add other routing protocols
 * in later versions. The RoutingHandler can manage multiple BGP sessions on
 * one router port. One RoutingHandler will launch at most one ZebraServer
 * (implementing the server-side of the Zebra Protocol). It will launch at
 * most one Vty connection for each routing protocol configured on the port.
 * Therefore, multiple sessions of a single routing protocol will share the
 * same protocol daemon (e.g. bgpd); and multiple protocols will share
 * the same ZebraServer. This is almost identical to the Quagga architecture
 * except that the RoutingHandler replaces the role of the Zebra daemon.
 *
 * Note that different exterior virtual router ports should have their routing
 * protocol sessions managed by different RoutingHandlers even if the router
 * ports happen to be on the save virtual router. The RoutingHandlers use
 * MidoNet's central cluster to aggregate routes. In the general case two
 * exterior virtual routers may be bound to physical interfaces on different
 * physical hosts, therefore MidoNet must anyway be able to use different
 * RoutingHandlers for different virtual ports of the same router. *
 */
class RoutingHandler(var rport: ExteriorRouterPort, val bgpIdx: Int, val client: Client)
    extends UntypedActorWithStash with ActorLogging {


    @Inject
    val dataClient: DataClient = null

    //val client: Client = null

    private final val BGP_INTERNAL_PORT_NAME: String =
        "midobgp%d".format(bgpIdx)
    private final val ZSERVE_API_SOCKET =
        "/var/run/quagga/zserv%d.api".format(bgpIdx)
    private final val BGP_VTY_PORT: Int = 2605 + bgpIdx
    private final val BGP_TCP_PORT: Short = 179

    private var zebra: ZebraServer = null
    private var bgpVty: BgpConnection = null

    private val bgps = mutable.Map[UUID, BGP]()
    private val adRoutes = mutable.Set[AdRoute]()
    private val peerRoutes = mutable.Map[Route, UUID]()
    private var internalPort: InternalPort = null

    // At this moment we only support one bgpd process
    private var bgpdProcess: BgpdProcess = null

    private case class NewBgpSession(bgp: BGP)

    private case class ModifyBgpSession(bgp: BGP)

    private case class RemoveBgpSession(bgpID: UUID)

    private case class AdvertiseRoute(route: AdRoute)

    private case class StopAdvertisingRoute(route: AdRoute)

    private case class AddPeerRoute(ribType: RIBType.Value,
                                    destination: IntIPv4, gateway: IntIPv4)

    private case class RemovePeerRoute(ribType: RIBType.Value,
                                       destination: IntIPv4, gateway: IntIPv4)

    // BgpdProcess will notify via these messages
    case class BGPD_READY()

    case class BGPD_DEAD()

    override def preStart() {
        log.debug("Starting routingHandler.")

        super.preStart()

        if (rport == null) {
            log.debug("port is null")
        } else {
            log.debug("port is not null")
        }

        if (client == null) {
            log.debug("client is null")
        } else {
            log.debug("client is not null")
        }

        // Watch the BGP session information for this port.
        // In the future we may also watch for session configurations of
        // other routing protocols.
        client.subscribeBgp(rport.id, new BGPListBuilder {
            def addBGP(bgp: BGP) {
                self ! NewBgpSession(bgp)
            }

            def updateBGP(bgp: BGP) {
                self ! ModifyBgpSession(bgp)
            }

            def removeBGP(bgpID: UUID) {
                self ! RemoveBgpSession(bgpID)
            }

            def addAdvertisedRoute(route: AdRoute) {
                self ! AdvertiseRoute(route)
            }

            def removeAdvertisedRoute(route: AdRoute) {
                self ! StopAdvertisingRoute(route)
            }
        })

        // Subscribe to the VTA for updates to the Port configuration.
        VirtualTopologyActor.getRef() ! PortRequest(rport.id, update = true)

        log.debug("RoutingHandler started.")
    }

    override def postStop() {
        super.postStop()
        stopBGP()
    }

    private val handler = new ZebraProtocolHandler {
        def addRoute(ribType: RIBType.Value, destination: IntIPv4,
                     gateway: IntIPv4) {
            self ! AddPeerRoute(ribType, destination, gateway)
        }

        def removeRoute(ribType: RIBType.Value, destination: IntIPv4,
                        gateway: IntIPv4) {
            self ! RemovePeerRoute(ribType, destination, gateway)
        }
    }

    /**
     * This actor can be in these phases:
     * NotStarted: bgp has not been started (no known bgp configs on the port)
     * Starting: waiting for bgpd to come up (and maybe the internal port)
     * Started: zebra, bgpVty and bgpd are up
     * Stopping: we're in the process of stopping the bgpd
     *
     * The transitions are:
     * NotStarted -> Starting : when we learn about the first BGP config
     * Starting -> Started : when the bgpd has come up
     * Started -> Starting : if the bgpd crashes or needs a restart
     * Started -> Stopping : when all the bgp configs have been removed
     */
    object Phase extends Enumeration {
        type Phase = Value
        val NotStarted = Value("NotStarted")
        val Starting = Value("Starting")
        val Started = Value("Started")
        val Stopping = Value("Stopping")
    }

    import Phase._

    var phase = NotStarted

    @scala.throws(classOf[Exception])
    def onReceive(message: Any) {
        message match {
            case port: ExteriorRouterPort =>
                var store = true
                phase match {
                    case Starting =>
                        store = false
                        stash()
                    case Started =>
                    // TODO(pino): reconfigure the internal port now
                    case _ => // fall through
                }
                if (store)
                    rport = port

            case port: Port[_] =>
                log.error("Cannot run BGP on anything but an exterior " +
                    "virtual router port. We got {}", port)

            case NewBgpSession(bgp) =>
                log.debug("NewBgpSession called.")
                phase match {
                    case NotStarted =>
                        // This must be the first bgp we learn about.
                        if (bgps.size != 0) {
                            log.error("This should be the first bgp connection" +
                                " but there was already other connections configured")
                            return
                        }
                        bgps.put(bgp.getId, bgp)
                        startBGP()
                        phase = Starting
                    case Starting =>
                        stash()
                    case Started =>
                        // The first bgp will enforce the AS
                        // for the other bgps. For a given routingHandler, we
                        // only have one bgpd process and all the BGP configs
                        // that specify different peers must have that field
                        // in common.
                        if (bgps.size == 0) {
                            log.error("This shouldn't be the first bgp connection" +
                                " but other connections were already configured")
                        }

                        val bgpPair = bgps.toList.head
                        val originalBgp = bgpPair._2

                        if (bgp.getLocalAS != originalBgp.getLocalAS) {
                            log.error("new BGP connections must have same AS")
                            return
                        }

                        if (bgps.contains(bgp.getId)) bgps.remove(bgp.getId)
                        bgps.put(bgp.getId, bgp)
                        bgpVty.setPeer(bgp.getLocalAS, bgp.getPeerAddr, bgp.getPeerAS)

                    case Stopping =>
                }

            case ModifyBgpSession(bgp) =>
                //TODO(abel) case not implemented (yet)
                phase match {
                    case _ => log.debug("message not implemented: ModifyBgpSession")
                }

            case RemoveBgpSession(bgpID) =>
                phase match {
                    case NotStarted =>
                        // This probably shouldn't happen. A BGP config is being
                        // removed, implying we knew about it - we shouldn't be
                        // NotStarted...
                        log.error("KillBgp not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        // TODO(pino): Use bgpVty to remove this BGP and its routes
                        val Some(bgp) = bgps.remove(bgpID)
                        bgpVty.deletePeer(bgp.getLocalAS, bgp.getPeerAddr)
                        // Remove all the flows for this BGP link
                        FlowController.getRef().tell(
                            FlowController.InvalidateFlowsByTag(bgpID))

                        // If this is the last BGP for ths port, tear everything down.
                        if (bgps.size == 0) {
                            phase = Stopping
                            stopBGP()
                        }
                    case Stopping =>
                        stash()
                }

            case PortInternalOpReply(iport, PortOperation.Create,
            false, null, null) =>
                log.debug("PortInternalOpReply - create")
                phase match {
                    case Starting =>
                        internalPortReady(iport)
                    case _ =>
                        log.error("PortInternalOpReply expected only while " +
                            "Starting - we're now in {}", phase)
                }

            case PortInternalOpReply(_, _, _, _, _) => // Do nothing
                log.debug("PortInternalOpReply - unknown")

            case BGPD_READY =>
                phase match {
                    case Starting =>
                        phase = Started
                        unstashAll()
                        for (bgp <- bgps.values) {
                            // Use the bgpVty to set up sessions with all these peers
                            create(rport.nwAddr(), bgp)
                        }
                        for (route <- adRoutes) {
                            // Use the bgpVty to add all these routes/networks
                        }
                    case _ =>
                        log.error("BGP_READY expected only while " +
                            "Starting - we're now in {}", phase)
                }

            case BGPD_DEAD =>
                phase match {
                    case Stopping =>
                        phase = NotStarted
                        unstashAll()
                    case _ =>
                        log.error("BGP_DEAD expected only while " +
                            "Stopping - we're now in {}", phase)
                }

            case AdvertiseRoute(rt) =>
                phase match {
                    case NotStarted =>
                        log.error("AddRoute not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        adRoutes.add(rt)
                        val bgp = bgps.get(rt.getBgpId)
                        bgp match {
                            case b: BGP => val as = b.getLocalAS
                            bgpVty.setNetwork(as, rt.getNwPrefix.getHostAddress, rt.getPrefixLength)
                            case _ =>
                        }
                    case Stopping =>
                        //TODO(abel) do we need to stash the message when stopping?
                        stash()
                }

            case StopAdvertisingRoute(rt) =>
                phase match {
                    case NotStarted =>
                        log.error("RemoveRoute not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        adRoutes.remove(rt)
                        val bgp = bgps.get(rt.getBgpId)
                        bgp match {
                            case b: BGP => val as = b.getLocalAS
                            bgpVty.deleteNetwork(as, rt.getNwPrefix.getHostAddress, rt.getPrefixLength)
                            case _ =>
                        }
                    case Stopping =>
                        //TODO(abel) do we need to stash the message when stopping?
                        stash()
                }

            case AddPeerRoute(ribType, destination, gateway) =>
                phase match {
                    case NotStarted =>
                        log.error("AddPeerRoute not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        if (peerRoutes.size > 100) {
                            /*
                             * TODO(abel) in order not to overwhelm the cluster,
                             * we will limit the max amount of routes we store
                             * at least for this version of the code.
                             * Note that peer routes, if not limited by bgpd
                             * or by the peer, can grow to hundreds of thousands
                             * of entries.
                             * I won't use a constant for this number because
                             * this problem should be tackled in a more elegant
                             * way and it's not used elsewhere.
                             */
                            log.error("Max amount of peer routes reached (100)")
                            return
                        }

                        val route = new Route()
                        route.setDstNetworkAddr(destination.toUnicastString)
                        route.setDstNetworkLength(destination.prefixLen())
                        route.setNextHopGateway(gateway.toUnicastString)
                        val routeId = dataClient.routesCreateEphemeral(route)
                        peerRoutes.put(route, routeId)
                    case Stopping =>
                        //TODO(abel) do we need to stash the message when stopping?
                        stash()
                }

            case RemovePeerRoute(ribType, destination, gateway) =>
                phase match {
                    case NotStarted =>
                        log.error("AddPeerRoute not expected in phase NotStarted")
                    case Starting =>
                        stash()
                    case Started =>
                        val route = new Route()
                        route.setDstNetworkAddr(destination.toUnicastString)
                        route.setDstNetworkLength(destination.prefixLen())
                        route.setNextHopGateway(gateway.toUnicastString)
                        peerRoutes.get(route) match {
                            case Some(routeId) => dataClient.routesDelete(routeId)
                            case None =>
                        }
                    case Stopping =>
                        //TODO(abel) do we need to stash the message when stopping?
                        stash()
                }
        }
    }

    private def startBGP() {
        log.debug("startBGP - begin")

        val socketFile = new File(ZSERVE_API_SOCKET)
        val socketDir = socketFile.getParentFile
        if (!socketDir.exists()) {
            socketDir.mkdirs()
            // Set permission to let quagga daemons write.
            socketDir.setWritable(true, false)
        }

        if (socketFile.exists())
            socketFile.delete()

        val address = new AFUNIXSocketAddress(socketFile)

        zebra = new ZebraServer(
            address, handler, rport.nwAddr(), BGP_INTERNAL_PORT_NAME)
        zebra.start()

        bgpVty = new BgpVtyConnection(
            addr = "localhost",
            port = BGP_VTY_PORT,
            password = "zebra_password")

        // Create the interface bgpd will run on.
        log.info("Adding internal port {} for BGP link", BGP_INTERNAL_PORT_NAME)
        DatapathController.getRef() !
            CreatePortInternal(
                Ports.newInternalPort(BGP_INTERNAL_PORT_NAME)
                    .setAddress(rport.portMac.getAddress), null)

        log.debug("startBGP - end")
    }

    private def stopBGP() {
        log.debug("stopBGP - begin")

        if (bgpdProcess != null) {
            bgpdProcess.stop()
        }

        log.debug("stopBGP - end")
    }

    private def internalPortReady(newPort: InternalPort) {
        log.debug("internalPortReady - begin")

        internalPort = newPort
        // The internal port is ready. Set up the flows
        if (bgps.size <= 0) {
            log.warning("No BGPs configured for this port: {}", newPort)
            return
        }

        // Of all the possible BGPs configured for this port, we only consider
        // the very first one to create the BGPd process
        val bgpPair = bgps.toList.head
        val bgp = bgpPair._2
        setBGPFlows(internalPort.getPortNo.shortValue(), bgp, rport)
        bgpdProcess = new BgpdProcess(this, BGP_VTY_PORT)
        bgpdProcess.start()

        log.debug("internalPortReady - end")
    }

    def create(localAddr: IntIPv4, bgp: BGP) {
        bgpVty.setAs(bgp.getLocalAS)
        bgpVty.setLocalNw(bgp.getLocalAS, localAddr)
        bgpVty.setPeer(bgp.getLocalAS, bgp.getPeerAddr, bgp.getPeerAS)

        for (adRoute <- adRoutes) {
            // If an adRoute is already configured in bgp, it will be
            // silently ignored
            bgpVty.setNetwork(bgp.getLocalAS, adRoute.getNwPrefix.getHostAddress,
                adRoute.getPrefixLength)
            adRoutes.add(adRoute)
        }
    }

    def setBGPFlows(localPortNum: Short, bgp: BGP,
                    bgpPort: ExteriorRouterPort) {

        log.debug("setBGPFlows - begin")

        // Set the BGP ID in a set to use as a tag for the datapath flows
        // For some reason AddWilcardFlow needs a mutable set so this
        // construction is needed although I'm sure you can find a better one.
        val bgpTagSet = Set[Any](bgp.getId)

        // TCP4:->179 bgpd->link
        var wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.getPeerAddr)
            .setTransportDestination(BGP_TCP_PORT)

        var wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionOutputToVrnPort(bgpPort.id))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, null, bgpTagSet))

        // TCP4:179-> bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.getPeerAddr)
            .setTransportSource(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionOutputToVrnPort(bgpPort.id))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, null, bgpTagSet))

        // TCP4:->179 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr)
            .setTransportDestination(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, null, bgpTagSet))

        // TCP4:179-> link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(TCP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr)
            .setTransportSource(BGP_TCP_PORT)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, null, bgpTagSet))

        // ARP bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(ARP.ETHERTYPE)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionOutputToVrnPort(bgpPort.id))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, null, bgpTagSet))

        // ARP link->bgpd, link->midolman
        // TODO(abel) send ARP from link to both ports only if it's an ARP reply
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(ARP.ETHERTYPE)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))
            .addAction(new FlowActionUserspace)

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, null, bgpTagSet))

        // ICMP4 bgpd->link
        wildcardMatch = new WildcardMatch()
            .setInputPortNumber(localPortNum)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgpPort.portAddr)
            .setNetworkDestination(bgp.getPeerAddr)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(new FlowActionOutputToVrnPort(bgpPort.id))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, null, bgpTagSet))

        // ICMP4 link->bgpd
        wildcardMatch = new WildcardMatch()
            .setInputPortUUID(bgpPort.id)
            .setEtherType(IPv4.ETHERTYPE)
            .setNetworkProtocol(ICMP.PROTOCOL_NUMBER)
            .setNetworkSource(bgp.getPeerAddr)
            .setNetworkDestination(bgpPort.portAddr)

        wildcardFlow = new WildcardFlow()
            .setMatch(wildcardMatch)
            .addAction(FlowActions.output(localPortNum))

        DatapathController.getRef.tell(AddWildcardFlow(
            wildcardFlow, None, null, null, bgpTagSet))

        log.debug("setBGPFlows - end")
    }

}
