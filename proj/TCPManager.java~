/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet TCP manager</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */
public class TCPManager {
  private Node node;
  private int addr;
  private Manager manager;
  private SimpleTCPSockSpace activeConnections;

  private static final byte dummy[] = new byte[0];

  public TCPManager(Node node, int addr, Manager manager) {
    this.node = node;
    this.addr = addr;
    this.manager = manager;
  }

  /**
   * Start this TCP manager
   */
  public void start() {
    activeConnections = new SimpleTCPSockSpace();
  }

  /*
   * Begin socket API
   */

  /**
   * Create a socket
   *
   * @return TCPSock the newly created socket, which is not yet bound to
   *                 a local port
   */
  public TCPSock socket() {
    return new TCPSock(node, this, addr);
  }

  /*
   * End Socket API
   */

  // Additional functions

  // Stub to demultiplex received packets
  public void receiveTransportPacket(Transport transportPacket, int from_adr, int to_adr) {
    int from_port = transportPacket.getSrcPort();
    int to_port = transportPacket.getDestPort();
    TCPSock toSock = activeConnections.demultiplexConnection(to_adr, to_port, from_adr, from_port);
    // Connection exists to send packet to.
    if (toSock != null) {
      toSock.acceptPacket(transportPacket, from_adr);
    }
    // Send fin packet to sender because connection refused.
    else {
      Transport returnPacket = new Transport(to_port, from_port, Transport.FIN, TCPSock.DEFAULT_WINDOW, 0, dummy);
      byte[] payload = returnPacket.pack();
      node.sendSegment(to_adr, from_adr, Protocol.TRANSPORT_PKT, payload);
    }
  }

  boolean claimPort(int src_adr, int src_port) {
    return activeConnections.claimPort(src_adr, src_port);
  }

  boolean registerListenSocket(int src_adr, int src_port, TCPSock socket) {
    return activeConnections.registerListenSocket(src_adr, src_port, socket);
  }

  boolean resgisterConnectionSocket(int src_adr, int src_port,
                                    int dest_adr, int dest_port, TCPSock socket) {
    return activeConnections.resgisterConnectionSocket(src_adr, src_port,
                                                       dest_adr, dest_port, socket);
  }

  void deregisterListenSocket(int src_adr, int src_port) {
    activeConnections.deregisterListenSocket(src_adr, src_port);
  }

  void deregisterConnectionSocket(int src_adr, int src_port, int dest_adr, int dest_port) {
    activeConnections.deregisterConnectionSocket(src_adr, src_port, dest_adr, dest_port);
  }
}
