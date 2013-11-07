/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet socket implementation</p>
 *
 * <p>Copyright: Copyright (c) 2006</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Hao Wang
 * @version 1.0
 */

public class TCPSock {

  public static final int DEFAULT_WINDOW = 16 * 1024;
  private static final int BUFFER_SIZE = 1024 * 1024;
  private static final byte dummy[] = new byte[0];

  // TCP socket states
  enum State {
    // protocol states
    CLOSED,
    LISTEN,
    SYN_SENT,
    ESTABLISHED,
    SHUTDOWN // close requested, FIN not sent (due to unsent data in queue)
  }
  private State state;
  private Node node;
  private TCPManager tcpMan;

  private int local_adr;
  private int local_port;
  private boolean stateEstablished;

  // Listener state
  private int backlog;
  TCPSock[] requestQueue;
  private int entryPointer;

  // Nonlistener state
  boolean clientSocket;
  int currentSeqNo;
  int dest_adr;
  int dest_port;
  byte[] buffer;
  int startData;
  int endData;

  public TCPSock(Node node, TCPManager tcpMan, int local_adr) {
    this.node = node;
    this.tcpMan = tcpMan;
    this.local_adr = local_adr;
    this.local_port = -1;
    this.stateEstablished = false;
  }

  /*
   * The following are the socket APIs of TCP transport service.
   * All APIs are NON-BLOCKING.
   */

  /**
   * Bind a socket to a local port
   *
   * @param localPort int local port number to bind the socket to
   * @return int 0 on success, -1 otherwise
   */
  public int bind(int localPort) {
    if (stateEstablished || localPort < 0 || localPort > Transport.MAX_PORT_NUM || !tcpMan.claimPort(local_adr, localPort)) {
      return -1;
    }

    this.local_port = localPort;
    return 0;
  }

  /**
   * Listen for connections on a socket
   * @param backlog int Maximum number of pending connections
   * @return int 0 on success, -1 otherwise
   */
  public int listen(int backlog) {
    if (stateEstablished || local_port < 0) {
      return -1;    
    }

    this.backlog = backlog;
    this.requestQueue = new TCPSock[backlog];
    this.entryPointer = 0;
    tcpMan.registerListenSocket(local_adr, local_port, this);
    state = State.LISTEN;
    stateEstablished = true;
    return 0;
  }

  /**
   * Accept a connection on a socket
   *
   * @return TCPSock The first established connection on the request queue
   */
  public TCPSock accept() {
    if (!stateEstablished || state != State.LISTEN || entryPointer <= 0) {
      return null;
    }
    return requestQueue[--entryPointer];
  }

  public boolean isConnectionPending() {
    return (state == State.SYN_SENT);
  }

  public boolean isClosed() {
    return (state == State.CLOSED);
  }

  public boolean isConnected() {
    return (state == State.ESTABLISHED);
  }

  public boolean isClosurePending() {
    return (state == State.SHUTDOWN);
  }

  /**
   * Initiate connection to a remote socket
   *
   * @param destAddr int Destination node address
   * @param destPort int Destination port
   * @return int 0 on success, -1 otherwise
   */
  // Have to roll the dice here. Since the API
  // has to be nonblocking, we will have to assume
  // the connection is OK and allow the destination
  // to send us a FIN packet later if the connection is refused.
  public int connect(int destAddr, int destPort) {
    if (stateEstablished || local_port < 0 || !tcpMan.resgisterConnectionSocket(local_adr, local_port, destAddr, destPort, this)) {
      return -1;
    }

    dest_adr = destAddr;
    dest_port = destPort;
    clientSocket = true;
    buffer = new byte[BUFFER_SIZE];
    startData = 0;
    endData = 0;
    currentSeqNo = getStartingSeqNo();
    Transport conInitiation = new Transport(local_port, destPort, Transport.SYN, DEFAULT_WINDOW, currentSeqNo, dummy);
    byte[] payload = conInitiation.pack();
    node.sendSegment(local_adr, destAddr, Protocol.TRANSPORT_PKT, payload);
    state = State.SYN_SENT;
    stateEstablished = true;
    return 0;
  }

  /**
   * Initiate closure of a connection (graceful shutdown)
   */
  public void close() {
    if (stateEstablished && state == State.LISTEN) {
      // Close everything in request queue.
      while (entryPointer > 0) {
        requestQueue[--entryPointer].close(); 
      }
      state = State.CLOSED;
    }
    else if (stateEstablished && (!clientSocket || startData == endData)) {
      Transport returnPacket = new Transport(local_port, dest_port, Transport.FIN, TCPSock.DEFAULT_WINDOW, currentSeqNo, dummy);
      byte[] payload = returnPacket.pack();
      node.sendSegment(local_adr, dest_adr, Protocol.TRANSPORT_PKT, payload);
      state = state.SHUTDOWN;
    }
    else {
      state = state.SHUTDOWN;
    }
  }

  /**
   * Release a connection immediately (abortive shutdown)
   */
  public void release() {
    if (stateEstablished && state == State.LISTEN) {
      tcpMan.deregisterListenSocket(local_adr, local_port);
    }
    else if (stateEstablished) {
      tcpMan.deregisterConnectionSocket(local_adr, local_port, dest_adr, dest_port);
    }
    state = State.CLOSED;
  }

  /**
   * Write to the socket up to len bytes from the buffer buf starting at
   * position pos.
   *
   * @param buf byte[] the buffer to write from
   * @param pos int starting position in buffer
   * @param len int number of bytes to write
   * @return int on success, the number of bytes written, which may be smaller
   *             than len; on failure, -1
   */
  public int write(byte[] buf, int pos, int len) {
    if (!stateEstablished || state != State.ESTABLISHED || !clientSocket) {
      return -1;
    }

    return 0;
  }

  /**
   * Read from the socket up to len bytes into the buffer buf starting at
   * position pos.
   *
   * @param buf byte[] the buffer
   * @param pos int starting position in buffer
   * @param len int number of bytes to read
   * @return int on success, the number of bytes read, which may be smaller
   *             than len; on failure, -1
   */
  public int read(byte[] buf, int pos, int len) {
    if (!stateEstablished || state != State.ESTABLISHED || clientSocket) {
      return -1;
    }

    return 0;
  }

  /*
   * End of socket API
   */

  // Additional functions
  public void acceptPacket(Transport transportPacket, int from_adr) {

  }

  // Start initial sequence number relatively low, so we don't
  // have to worry about overflow
  public int getStartingSeqNo() {
    return (int) (Math.random() * (1 << 16));
  }
}
