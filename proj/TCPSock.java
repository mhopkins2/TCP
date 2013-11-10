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
  private static final int INITIAL_ESTIMATE_RTT = 40;
  private static final int INITIAL_DEV_RTT = 10;
  private static final byte dummy[] = new byte[0];

  private Node node;
  private TCPManager tcpMan;
  private Manager manager;

  // TCP socket states
  enum State {
    // protocol states
    SETUP,
    CLOSED,
    LISTEN,
    SYN_SENT,
    ESTABLISHED,
    SHUTDOWN // close requested, FIN not sent (due to unsent data in queue)
  }
  private State state;
  private int local_adr;
  private int local_port;

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
  int endData;                      // For sender, spot in buffer for next sent byte
                                    // For receiver, spot in buffer for next in-order byte received
  int estimatedRTT;
  int devRTT;
  boolean timerOutstanding;         // True if there is a valid timeout even outstanding on the socket (an
                                    // even that will cause the socket to have to resend a packet)
  boolean closePending;             // True if client has requested socket close, but server
                                    // isn't finished reading data

  // To create a listening socket or a client socket
  public TCPSock(Node node, TCPManager tcpMan, Manager manager, int local_adr) {
    this.node = node;
    this.tcpMan = tcpMan;
    this.manager = manager;
    this.state = State.SETUP;
    this.local_adr = local_adr;
    this.local_port = -1;
  }

  // For sockets that are created through listening sockets
  protected TCPSock(Node node, TCPManager tcpMan, Manager man, 
                    int local_adr, int local_port, 
                    int dest_adr, int dest_port, int initialSeqNo) {
    
    this.node = node;
    this.tcpMan = tcpMan;
    this.manager = manager;
    this.state = State.ESTABLISHED;
    this.local_adr = local_adr;
    this.local_port = local_port;
    this.clientSocket = false;
    this.currentSeqNo = initialSeqNo + 1;
    this.currentWindowSize = DEFAULT_WINDOW;
    this.dest_adr = dest_adr;
    this.dest_port = dest_port;
    this.buffer = new byte[BUFFER_SIZE];
    this.outOfOrderBytes = new Hashtable<Integer, Byte>();
    this.startData = 0;
    this.endData = 0;
    this.closePending = false;

    tcpMan.resgisterConnectionSocket(local_adr, local_port, dest_adr, dest_port, this);
    sendTransportPacket(Transport.ACK, currentSeqNo, dummy, false);
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
    if (state != State.SETUP || localPort < 0 || localPort > Transport.MAX_PORT_NUM || !tcpMan.claimPort(local_adr, localPort)) {
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
    if (state != State.SETUP || local_port < 0) {
      return -1;
    }

    this.requestQueue = new TCPSock[backlog];
    this.entryPointer = 0;
    tcpMan.registerListenSocket(local_adr, local_port, this);
    state = State.LISTEN;
    return 0;
  }

  /**
   * Accept a connection on a socket
   *
   * @return TCPSock The first established connection on the request queue
   */
  public TCPSock accept() {
    if (state != State.LISTEN || entryPointer <= 0) {
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
    if (state != State.SETUP || local_port < 0 || !tcpMan.resgisterConnectionSocket(local_adr, local_port, destAddr, destPort, this)) {
      return -1;
    }

    // Initialize instance variables
    this.clientSocket = true;
    this.currentSeqNo = getStartingSeqNo();
    this.currentWindowSize = DEFAULT_WINDOW;
    this.dest_adr = destAddr;
    this.dest_port = destPort;
    this.buffer = new byte[BUFFER_SIZE];
    this.startData = 0;
    this.endData = 0;
    this.estimatedRTT = INITIAL_ESTIMATE_RTT;
    this.devRTT = INITIAL_DEV_RTT;
    this.timerOutstanding = false;
  
    // Send SYN packet
    sendTransportPacket(Transport.SYN, currentSeqNo, dummy, false);
    updateTimer(currentSeqNo);
    state = State.SYN_SENT;
    currentSeqNo++;

    return 0;
  }

  /**
   * Initiate closure of a connection (graceful shutdown)
   */
  public void close() {
    // If this is a listening socket or a connection socket that does not have
    // outstanding data which has not yet been ack'ed, then release the socket.
    if (state == State.SETUP || state == State.LISTEN || !clientSocket || startData == endData) {
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
    if (state == State.LISTEN) {
      // Close everything in request queue.
      while (entryPointer > 0) {
        requestQueue[--entryPointer].close(); 
      }
      tcpMan.deregisterListenSocket(local_adr, local_port);
    }
    // Partially setup socket
    else if (state == State.SETUP && local_port >= 0) {
      tcpMan.deregisterPortOnly(local_adr, local_port);
    }
    else if (!clientSocket && closePending) {
      tcpMan.deregisterConnectionSocket(local_adr, local_port, dest_adr, dest_port);
    }
    // Connection socket
    else if (state != State.CLOSED) {
      sendTransportPacket(Transport.FIN, currentSeqNo, dummy, false);
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
    if ((state != State.ESTABLISHED && state != State.SYN_SENT) || !clientSocket) {
      return -1;
    }

    // Length for write is the minimum of len, the number of bytes starting at
    // pos in the buffer, and the remaining room within the window.
    len = Math.min(len, buf.length - pos);
    len = Math.min(len, currentWindowSize - bufferedDataSize());

    // If some writing can occur
    if (len != 0) {
      byte[] acceptedBytes = getAcceptedBytes(buf, pos, len);
    
      node.logDebug("write: the following bytes were written");
      for (int i = 0; i < acceptedBytes.length; i++) {
        node.logDebug(i + ": " + acceptedBytes[i]);
      }

      if (state == State.ESTABLISHED) {
        sendTransportData(currentSeqNo, acceptedBytes, false);
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
    if (state != State.ESTABLISHED || clientSocket) {
      return -1;
    }

    // Length for reading is the minimum of len, the remaining room in 'buf'
    // starting at 'pos', and the amount of buffered data.
    len = Math.min(len, buf.length - pos);
    len = Math.min(len, bufferedDataSize());
    readBytesFromBuffer(buf, pos, len);

    if (closePending && startData == endData)
      release();

    return len;
  }

  /*
   * End of socket API
   */

  // Additional functions
  public void acceptPacket(Transport transportPacket, int from_adr) {
    if (state == State.SETUP || state == State.CLOSED) 
      return;

    node.logDebug("acceptPacket: sequence number " + transportPacket.getSeqNum());

    //printReceiverCharacter(transportPacket.getType(), transportPacket.getSeqNum());
    
    // Incoming connection on listen socket and there is room in the request queue.
    if (state == State.LISTEN && transportPacket.getType() == Transport.SYN && entryPointer < requestQueue.length) {
      TCPSock connectionSock = new TCPSock(node, tcpMan, manager, local_adr, local_port, 
                                           from_adr, transportPacket.getSrcPort(), transportPacket.getSeqNum());
      requestQueue[entryPointer++] = connectionSock;
    }

    // Duplicate SYN packet
    else if (state == State.ESTABLISHED && transportPacket.getType() == Transport.SYN && !clientSocket) {
      sendTransportPacket(Transport.ACK, currentSeqNo, dummy, true);
    }
  
    // Ack packet for some data that has not yet been ack'ed.
    else if ((state == state.ESTABLISHED || state == state.SHUTDOWN) && clientSocket && 
              transportPacket.getType() == Transport.ACK &&
              transportPacket.getSeqNum() > currentSeqNo - bufferedDataSize() &&
              transportPacket.getSeqNum() <= currentSeqNo) {
      int unackedBytes = currentSeqNo - transportPacket.getSeqNum();
      this.startData = (endData - unackedBytes + BUFFER_SIZE) % BUFFER_SIZE;
      timerOutstanding = false;
      if (startData != endData) {
        updateTimer(currentSeqNo - bufferedDataSize());
      }

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
      node.logDebug("acceptPacket: type DATA, Payload size: " + transportPacket.getPayload().length);

      int initialSeqNo = currentSeqNo;
      bufferReceivedData(transportPacket.getPayload(), transportPacket.getSeqNum());
      boolean repeat = initialSeqNo == currentSeqNo;
      sendTransportPacket(Transport.ACK, currentSeqNo, dummy, repeat);
    } 

    // Keep socket open until application is finished reading
    else if (transportPacket.getType() == Transport.FIN && state == State.ESTABLISHED && 
             !clientSocket && startData != endData) {
      this.closePending = true;
    }

    else if (transportPacket.getType() == Transport.FIN && state != State.LISTEN && state != State.CLOSED) {
      tcpMan.deregisterConnectionSocket(local_adr, local_port, dest_adr, dest_port);
      state = State.CLOSED;
    }
    
  }

  protected void printReceiverCharacter(int packetType, int seqNo) {
    if (packetType == Transport.SYN && state == State.LISTEN) {
      System.out.print("S");
    }
    else if (packetType == Transport.FIN && state != State.CLOSED) {
      System.out.print("F");
    }
    else if (packetType == Transport.DATA && seqNo >= currentSeqNo) {
      System.out.print(".");
    }
    else if (packetType == Transport.ACK && (seqNo > currentSeqNo - bufferedDataSize() ||
             (seqNo == currentSeqNo && state == State.SYN_SENT))) {
      System.out.print(":");
    }
    else if (packetType == Transport.ACK && seqNo <= currentSeqNo - bufferedDataSize()) {
      System.out.print("?");
    }
    else {
      System.out.print("!");
    }
  }

  protected void printSenderCharacter(int transportType, boolean repeat) {
    if (repeat) {
      System.out.print("!");
    }
    else if (transportType == Transport.SYN) {
      System.out.print("S");
    }
    else if (transportType == Transport.FIN) {
      System.out.print("F");
    }
    else if (transportType == Transport.DATA) {
      System.out.print(".");
    }
  }

  protected void sendAllDataInBuffer() {
    byte[] payload = new byte[bufferedDataSize()];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = buffer[(startData + i) % BUFFER_SIZE];
    }
    sendTransportData(currentSeqNo - payload.length, payload, false);
  }

  // The first byte of 'newData' corresponds to the sequence number 'seqNo'.
  // Buffer all data that will fit from newData, including in order bytes and
  // out of order bytes.
  protected void bufferReceivedData(byte[] newData, int seqNo) {
    for (int i = 0; i < newData.length; i++, seqNo++) {
      if (currentSeqNo == seqNo) {
        node.logDebug("bufferedReceivedData: seqNo " + currentSeqNo + " is byte " + newData[i]);
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
    while (outOfOrderBytes.containsKey(currentSeqNo)) {
      buffer[endData] = outOfOrderBytes.get(currentSeqNo);
      outOfOrderBytes.remove(currentSeqNo);
      endData = (endData + 1) % BUFFER_SIZE;
      currentSeqNo++;
    }
  }

  // When you send a packet, add an event for handleSocketTimeout
  // with the initial sequence number of the packet as an argument (for the callback)
  public void handleSocketTimeout(Integer seqNo) {
    node.logDebug("handleSocketTimeout: Timeout occured for seqNo " + seqNo);

    // Timeout of SYN packet
    if (state == State.SYN_SENT) {
      timerOutstanding = false;
      sendTransportPacket(Transport.SYN, seqNo, dummy, true);
      updateTimer(seqNo);
    }
    // Timeout of DATA packet
    else if ((state == State.ESTABLISHED || state == State.SHUTDOWN) &&
              seqNo == currentSeqNo - bufferedDataSize()) {
      timerOutstanding = false;
      byte[] payload = getFirstBufferedDataPacket();
      sendTransportPacket(Transport.DATA, seqNo, payload, true);
      updateTimer(seqNo);
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
    for (int i = pos; i < pos + len; i++) {
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
  protected void sendTransportData(int seqNo, byte[] payload, boolean repeat) {
    for (int i = 0; i < payload.length; i += Transport.MAX_PAYLOAD_SIZE) {
      int abbreviatedLength = Math.min(Transport.MAX_PAYLOAD_SIZE, payload.length - i);
      byte[] abbreviatedData = new byte[abbreviatedLength];
      for (int j = 0; j < abbreviatedLength; j++) {
        abbreviatedData[j] = payload[i + j];
      }

      sendTransportPacket(Transport.DATA, seqNo + i, abbreviatedData, repeat);
    }
  }

  // Helper function to send a transport packet.
  protected void sendTransportPacket(int transportType, int seqNo, byte[] payload, boolean repeat) {
    //printSenderCharacter(transportType, repeat);

    node.logDebug("sendTransportPacket: sequence number of packet " + seqNo);
    node.logDebug("sendTransportPacket: sequence number of node " + currentSeqNo);
    node.logDebug("sendTransportPacket: the following bytes were sent as payload");
    for (int i = 0; i < payload.length; i++) {
      node.logDebug(i + ": " + payload[i]);
    }

    Transport transportPacket = new Transport(local_port, dest_port, transportType, currentWindowSize, seqNo, payload);
    byte[] packetPayload = transportPacket.pack();
    node.sendSegment(local_adr, dest_adr, Protocol.TRANSPORT_PKT, packetPayload);
  }

  // Creates a timer if valid timer is not currently outstanding
  protected void updateTimer(int seqNo) {
    if (!timerOutstanding) {
	    try {
        String[] paramTypes = {"java.lang.Integer"};
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
