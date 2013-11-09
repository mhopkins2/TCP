import java.lang.reflect.Method;
import java.util.Hashtable;

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
  private static final double BETA = 0.25;
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
  private Manager manager;

  private int local_adr;
  private int local_port;
  private boolean stateEstablished; // true if value has been assigned to 'state'

  // Listener state
  TCPSock[] requestQueue;           // Array of established connections which have not yet been "accepted"
                                    // from listening socket
  private int entryPointer;         // Pointer to index for next insertion into requestQueue

  // Nonlistener state
  boolean clientSocket;             // true if this socket is sending data (as opposed to receiving data)
  int currentSeqNo;                 // For sender, the next sequence number for a new outgoing byte
                                    // For receiver, the lowest sequence number that has not yet been ack'ed
  int currentWindowSize;
  int dest_adr;
  int dest_port;
  byte[] buffer;
  Hashtable<Integer, Byte> outOfOrderBytes; // Buffer for bytes received out of order
  int startData;                    // For sender, the earliest byte that has not yet been ack'ed
                                    // For receiver, the earliest byte that has not yet been read by application
  int endData;                      // For sender, the latest byte sent that has not yet been ack'ed
                                    // For receiver, the latest byte ack'ed that has not been read by the application
  int estimatedRTT;
  int devRTT;
  boolean timerOutstanding;         // True if there is a valid timeout even outstanding on the socket (an
                                    // even that will cause the socket to have to resend a packet)

  // To create a listening socket or a client socket
  public TCPSock(Node node, TCPManager tcpMan, Manager manager, int local_adr) {
    this.node = node;
    this.tcpMan = tcpMan;
    this.manager = manager;
    this.local_adr = local_adr;
    this.local_port = -1;
    this.stateEstablished = false;
    this.currentWindowSize = DEFAULT_WINDOW;
    this.estimatedRTT = 100;
    this.devRTT = 20;
    this.timerOutstanding = false;
  }

  // For sockets that are created through listening sockets
  protected TCPSock(Node node, TCPManager tcpMan, Manager man, 
                    int local_adr, int local_port, 
                    int dest_adr, int dest_port, int initialSeqNo) {
    
    this.node = node;
    this.tcpMan = tcpMan;
    this.manager = manager;
    this.local_adr = local_adr;
    this.local_port = local_port;
    this.dest_adr = dest_adr;
    this.dest_port = dest_port;
    this.currentSeqNo = initialSeqNo;
    this.currentWindowSize = DEFAULT_WINDOW;
    this.buffer = new byte[BUFFER_SIZE];
    this.outOfOrderBytes = new Hashtable<Integer, Byte>();
    this.startData = 0;
    this.endData = 0;
    this.state = State.ESTABLISHED;
    this.stateEstablished = true;
    this.clientSocket = false;

    tcpMan.resgisterConnectionSocket(local_adr, local_port, dest_adr, dest_port, this);
    sendTransportPacket(Transport.ACK, currentSeqNo, dummy);
    currentSeqNo++;
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

    // Initialize instance variables
    dest_adr = destAddr;
    dest_port = destPort;
    clientSocket = true;
    buffer = new byte[BUFFER_SIZE];
    startData = 0;
    endData = 0;
  
    // Send SYN packet
    currentSeqNo = getStartingSeqNo();
    sendTransportPacket(Transport.SYN, currentSeqNo, dummy);
    updateTimer(currentSeqNo);
    state = State.SYN_SENT;
    currentSeqNo++;
    stateEstablished = true;

    return 0;
  }

  /**
   * Initiate closure of a connection (graceful shutdown)
   */
  public void close() {
    // If this is a listening socket or a connection socket that does not have
    // outstanding data which has not yet been ack'ed, then release the socket.
    if (stateEstablished && (state == State.LISTEN || !clientSocket || startData == endData)) {
      release();
    }
    else {
      state = state.SHUTDOWN;
    }
  }

  /**
   * Release a connection immediately (abortive shutdown)
   */
  public void release() { 
    // Listen socket
    if (stateEstablished && state == State.LISTEN) {
      // Close everything in request queue.
      while (entryPointer > 0) {
        requestQueue[--entryPointer].close(); 
      }
      tcpMan.deregisterListenSocket(local_adr, local_port);
    }
    // Connection socket
    else if (stateEstablished) {
      sendTransportPacket(Transport.FIN, currentSeqNo, dummy);
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
    if (!stateEstablished || (state != State.ESTABLISHED && state != State.SYN_SENT) || !clientSocket) {
      return -1;
    }

    // Length for write is the minimum of len, the number of bytes starting at
    // pos in the buffer, and the remaining room within the window.
    len = Math.min(len, buf.length - pos);
    len = Math.min(len, currentWindowSize - bufferedDataSize());

    // If some writing can occur
    if (len != 0) {
      byte[] acceptedBytes = getAcceptedBytes(buf, pos, len);

      if (state == State.ESTABLISHED) {
        sendTransportData(currentSeqNo, acceptedBytes);
        updateTimer(currentSeqNo); 
      }

      currentSeqNo += len;
      addDataToBuffer(acceptedBytes);
    }

    return len;
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

    // Length for reading is the minimum of len, the remaining room in 'buf'
    // starting at 'pos', and the amount of buffered data.
    len = Math.min(len, buf.length - pos);
    len = Math.min(len, bufferedDataSize());
    readBytesFromBuffer(buf, pos, len);
    return len;
  }

  /*
   * End of socket API
   */

  // Additional functions
  public void acceptPacket(Transport transportPacket, int from_adr) {
    printCharacterForPacket(transportPacket.getType(), transportPacket.getSeqNum());

    if (!stateEstablished || state == State.CLOSED) 
      return;
    
    // Incoming connection on listen socket and there is room in the request queue.
    if (state == State.LISTEN && transportPacket.getType() == Transport.SYN && entryPointer < requestQueue.length) {
      TCPSock connectionSock = new TCPSock(node, tcpMan, manager, local_adr, local_port, 
                                           from_adr, transportPacket.getSrcPort(), transportPacket.getSeqNum());
      requestQueue[entryPointer++] = connectionSock;
    }
  
    // Ack packet for some data that has not yet been ack'ed.
    else if ((state == state.ESTABLISHED || state == state.SHUTDOWN) && clientSocket && 
              transportPacket.getType() == Transport.ACK &&
              transportPacket.getSeqNum() > currentSeqNo - bufferedDataSize() &&
              transportPacket.getSeqNum() <= currentSeqNo) {
      int unackedBytes = currentSeqNo - transportPacket.getSeqNum();
      startData = (endData - unackedBytes + BUFFER_SIZE) % BUFFER_SIZE;
      timerOutstanding = false;
      if (startData != endData) {
        updateTimer(currentSeqNo - bufferedDataSize());
      }
      // FIGURE OUT HOW TO UPDATE ESTIMATED RTT AND DEVRTT

      // Try to close the socket now that more data has been received
      if (state == state.SHUTDOWN) {
        close();
      }
    }

    // Acknowledgement for an outstanding SYN packet
    else if (state == state.SYN_SENT && transportPacket.getType() == Transport.ACK &&
             transportPacket.getSeqNum() >= currentSeqNo - bufferedDataSize()) {
      state = state.ESTABLISHED;
      timerOutstanding = false;

      // If there is data in the buffer, send it all because no data has been 
      // send while socket was in SYN_SENT state.
      if (startData != endData) {
        sendAllDataInBuffer();
        updateTimer(currentSeqNo - bufferedDataSize());
      }
    }

    // Accept incoming data into buffer if space permits.
    else if (state == state.ESTABLISHED && !clientSocket && transportPacket.getType() == Transport.DATA) {
      bufferReceivedData(transportPacket.getPayload(), transportPacket.getSeqNum());
      sendTransportPacket(Transport.ACK, currentSeqNo, dummy);
    } 

    else if (transportPacket.getType() == Transport.FIN && state != State.LISTEN && state != State.CLOSED) {
      tcpMan.deregisterConnectionSocket(local_adr, local_port, dest_adr, dest_port);
      state = State.CLOSED;
    }
    
  }

  protected void printCharacterForPacket(int packetType, int seqNo) {
    if (packetType == Transport.SYN) {
      System.out.print("S");
    }
    else if (packetType == Transport.FIN) {
      System.out.print("F");
    }
    else if (packetType == Transport.DATA && seqNo >= currentSeqNo) {
      System.out.print(".");
    }
    else if (packetType == Transport.DATA && seqNo < currentSeqNo) {
      System.out.print("!");
    }
    else if (packetType == Transport.ACK && seqNo > currentSeqNo - bufferedDataSize()) {
      System.out.print(":");
    }
    else if (packetType == Transport.ACK && seqNo <= currentSeqNo - bufferedDataSize()) {
      System.out.print("?");
    }
  }

  protected void sendAllDataInBuffer() {
    byte[] payload = new byte[bufferedDataSize()];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = buffer[(startData + i) % BUFFER_SIZE];
    }
    sendTransportData(currentSeqNo - payload.length, payload);
  }

  // The first byte of 'newData' corresponds to the sequence number 'seqNo'.
  // Buffer all data that will fit from newData, including in order bytes and
  // out of order bytes.
  protected void bufferReceivedData(byte[] newData, int seqNo) {
    for (int i = 0; i < newData.length; i++, seqNo++) {
      if (currentSeqNo == seqNo) {
        buffer[endData] = newData[i];
        endData = (endData + 1) % BUFFER_SIZE;
        currentSeqNo++;
      }
      else if (currentSeqNo < seqNo && bufferedDataSize() + (seqNo - currentSeqNo + 1) < BUFFER_SIZE) {
        outOfOrderBytes.put(seqNo, newData[i]);
      }
    }

    extractOutOfOrderBytes();
  }

  // Move all bytes possible from 'outOfOrderBytes' to 'buffer'.
  protected void extractOutOfOrderBytes() {
    while (outOfOrderBytes.contains(currentSeqNo)) {
      buffer[endData] = outOfOrderBytes.get(currentSeqNo);
      outOfOrderBytes.remove(currentSeqNo);
      endData = (endData + 1) % BUFFER_SIZE;
      currentSeqNo++;
    }
  }

  // When you send a packet, add an event for handleSocketTimeout
  // with the initial sequence number of the packet as an argument (for the callback)
  protected void handleSocketTimeout(int seqNo) {
    // Timeout of SYN packet
    if (state == State.SYN_SENT) {
      timerOutstanding = false;
      sendTransportPacket(Transport.SYN, seqNo, dummy);
      updateTimer(seqNo);
    
      // Print character for retransmission
      System.out.print("!");
    }
    // Timeout of DATA packet
    else if ((state == State.ESTABLISHED || state == State.SHUTDOWN) &&
              seqNo == currentSeqNo - bufferedDataSize()) {
      timerOutstanding = false;
      byte[] payload = getFirstBufferedDataPacket();
      sendTransportPacket(Transport.DATA, seqNo, payload);
      updateTimer(seqNo);

      // Print character for retransmission
      System.out.print("!");
    }
  }

  // return a byte array with as many bytes from buffered data that will
  // fit in one packet
  protected byte[] getFirstBufferedDataPacket() {
    int size = Math.min(Transport.MAX_PAYLOAD_SIZE, bufferedDataSize());
    byte[] dataPacket = new byte[size];
    for (int i = 0; i < dataPacket.length; i++) {
      dataPacket[i] = buffer[(i + startData) % BUFFER_SIZE];
    }
    return dataPacket;
  }

  protected int bufferedDataSize() {
    return (endData - startData + BUFFER_SIZE) % BUFFER_SIZE;
  }

  // Return byte array with 'len' number of bytes from 'buf' starting at 'pos'
  protected byte[] getAcceptedBytes(byte[] buf, int pos, int len) {
    byte[] acceptedBytes = new byte[len];
    for (int i = 0; i < len; i++) {
      acceptedBytes[i] = buf[pos + i];
    }
    return acceptedBytes;
  }

  // Read 'len' bytes from 'buffer' into 'buf' starting at 'pos'
  protected void readBytesFromBuffer(byte[] buf, int pos, int len) {
    for (int i = pos; i < len; i++) {
      buf[i] = buffer[startData++];
    }
  } 

  // Insert 'dataToAdd' into 'buffer'
  protected void addDataToBuffer(byte[] dataToAdd) {
    for (int i = 0; i < dataToAdd.length; i++) {
      buffer[endData] = dataToAdd[i];
      endData = (endData + 1) % BUFFER_SIZE;
    }
  }

  // Start initial sequence number relatively low, so we don't
  // have to worry about overflow
  protected int getStartingSeqNo() {
    return (int) (Math.random() * (1 << 16));
  }

  // Breaks up 'payload' into appropriate sized byte arrays and sends each in
  // a transport packet where the first byte of payload has sequence number 'seqNo'
  protected void sendTransportData(int seqNo, byte[] payload) {
    for (int i = 0; i < payload.length; i += Transport.MAX_PAYLOAD_SIZE) {
      int abbreviatedLength = Math.min(Transport.MAX_PAYLOAD_SIZE, payload.length - i);
      byte[] abbreviatedData = new byte[abbreviatedLength];
      for (int j = 0; j < abbreviatedLength; j++) {
        abbreviatedData[j] = payload[i];
      }
      sendTransportPacket(Transport.DATA, seqNo + i, abbreviatedData);
    }
  }

  // Helper function to send a transport packet.
  protected void sendTransportPacket(int transportType, int seqNo, byte[] payload) {
    Transport transportPacket = new Transport(local_port, dest_port, transportType, currentWindowSize, seqNo, payload);
    byte[] packetPayload = transportPacket.pack();
    node.sendSegment(local_adr, dest_adr, Protocol.TRANSPORT_PKT, packetPayload);
  }

  // Creates a timer if valid timer is not currently outstanding
  protected void updateTimer(int seqNo) {
    if (!timerOutstanding) {
	    try {
        String[] paramTypes = {"Integer"};
        Object[] params = {new Integer(seqNo)};
	      Method method = Callback.getMethod("handleSocketTimeout", this, paramTypes);
	      Callback cb = new Callback(method, this, params);
	      this.manager.addTimer(local_adr, estimatedRTT + 4 * devRTT, cb);
        timerOutstanding = true;
	    }catch(Exception e) {
	      node.logError("Failed to add timer callback. Method Name: " + "handleSocketTimeout" +
		       "\nException: " + e);
	    }
    }
  }
}
