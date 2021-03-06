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

  public static final int DEFAULT_WINDOW = 8 * 1024;
  private static final int BUFFER_SIZE = 16 * 1024 - 1;
  private static final double ALPHA = 0.125;
  private static final double BETA = 0.25;
  private static final double CONGESTION_DECREASE_FACTOR = 0.5;
  private static final int INITIAL_TIMEOUT = 2000;
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
  double currentWindowSize;
  int ssthresh;
  int dest_adr;
  int dest_port;
  byte[] buffer;
  Hashtable<Integer, Byte> outOfOrderBytes; // Buffer for bytes received out of order
  int startData;                    // For sender, the earliest byte that has not yet been ack'ed
                                    // For receiver, the earliest byte that has not yet been read by application
  int endData;                      // For sender, spot in buffer for next written byte
                                    // For receiver, spot in buffer for next in-order byte received
  int endSendData;                  // For sender, spot in buffer of next unsent byte.
  int smoothedRTT;
  int devRTT;
  int timeout;
  int seqNoTracked;                 // Sequence number which is being used to calculate latest RTT;
  long seqNoTimestamp;              // Time which 'seqNoTracked' was sent
  boolean timerOutstanding;         // True if there is a valid timeout even outstanding on the socket (an
                                    // event that will cause the socket to have to resend a packet)
  boolean closePending;             // True if client has requested socket close, but server
                                    // isn't finished reading data
  int lastAckReceived;              // Byte number of last received ack packet
  int duplicateAckCount;            // Number of consecutive times 'lastAckReceived' has been received as ACK
  boolean delayTimeout;             // True if retransmission due to a triple duplicate ACK
  int consecutiveTimeouts;
  int recoverSeqNo;

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
    this.currentWindowSize = BUFFER_SIZE - 1;
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
    this.currentWindowSize = 2 * Transport.MAX_PAYLOAD_SIZE;      // Specified by TCP Reno
    this.ssthresh = BUFFER_SIZE;
    this.dest_adr = destAddr;
    this.dest_port = destPort;
    this.buffer = new byte[BUFFER_SIZE];
    this.startData = 0;
    this.endData = 0;
    this.endSendData = 0;
    this.smoothedRTT = -1;
    this.devRTT = -1;
    this.timeout = INITIAL_TIMEOUT;
    this.seqNoTracked = -1;
    this.seqNoTimestamp = -1;
    this.timerOutstanding = false;
    this.lastAckReceived = -1;
    this.duplicateAckCount = 0;
    this.delayTimeout = false;
    this.consecutiveTimeouts = 0;
    this.recoverSeqNo = -1;
  
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
    if (pos < 0 || pos >= buf.length || len < 0 || (state != State.ESTABLISHED && state != State.SYN_SENT) || !clientSocket) {
      return -1;
    }

    // Length for write is the minimum of len, the number of bytes starting at
    // pos in the buffer, and the remaining room within the buffer.
    len = Math.min(len, buf.length - pos);
    len = Math.min(len, remainingBufferSize());

    // If some writing can occur
    if (len > 0) {
      byte[] acceptedBytes = getAcceptedBytes(buf, pos, len);
      addDataToBuffer(acceptedBytes);

      if (state == State.ESTABLISHED) {
        sendAllDataPossible();
      }

      return len;
    }
    else {
      return 0;
    }

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
    if (pos < 0 || pos >= buf.length || len < 0 || state != State.ESTABLISHED || clientSocket) {
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

    printReceiverCharacter(transportPacket.getType(), transportPacket.getSeqNum());
    
    // Incoming connection on listen socket and there is room in the request queue.
    if (state == State.LISTEN && transportPacket.getType() == Transport.SYN && entryPointer < requestQueue.length) {
      TCPSock connectionSock = new TCPSock(node, tcpMan, manager, local_adr, local_port, 
                                           from_adr, transportPacket.getSrcPort(), transportPacket.getSeqNum());
      requestQueue[entryPointer++] = connectionSock;
    }

    // Duplicate SYN packet
    else if (state == State.ESTABLISHED && transportPacket.getType() == Transport.SYN && !clientSocket) {
      sendTransportPacket(Transport.ACK, currentSeqNo, dummy, false);
    }
  
    // Ack packet for some data that has not yet been ack'ed.
    else if ((state == state.ESTABLISHED || state == state.SHUTDOWN) && clientSocket && 
              transportPacket.getType() == Transport.ACK &&
              transportPacket.getSeqNum() > currentSeqNo - outstandingDataSize() &&
              transportPacket.getSeqNum() <= currentSeqNo) {

      this.lastAckReceived = transportPacket.getSeqNum();
      this.duplicateAckCount = 1;
      this.delayTimeout = false;
      this.consecutiveTimeouts = 0;
      int initialBufferedDataSize = bufferedDataSize();

      // Update timeout value as per RFC 2988
      if (seqNoTracked != -1 && transportPacket.getSeqNum() > seqNoTracked) {
        updateTimeoutValue();  
      }
      else if (smoothedRTT >= 0) {
        timeout = smoothedRTT + Math.max(1, 4 * devRTT);
      }

      node.logDebug("acceptPacket: bufferedData size " + bufferedDataSize());

      int unackedBytes = currentSeqNo - transportPacket.getSeqNum();
      this.startData = (endSendData - unackedBytes + BUFFER_SIZE) % BUFFER_SIZE;
      timerOutstanding = false;
      if (startData != endSendData) {
        updateTimer(currentSeqNo - outstandingDataSize());
      }

      // Try to close the socket now that more data has been received
      if (state == state.SHUTDOWN) {
        node.logDebug("acceptPacket: calling close");
        close();
      }

      updateWindowSize(transportPacket.getWindow(), initialBufferedDataSize - bufferedDataSize());
      sendAllDataPossible();

      if (transportPacket.getSeqNum() < recoverSeqNo) {
        byte[] payload = getFirstBufferedDataPacket();
        sendTransportData(currentSeqNo - outstandingDataSize(), payload, true);
        delayTimeout = true;
        timerOutstanding = false;
        seqNoTracked = -1;
        updateTimer(transportPacket.getSeqNum());
      }
    }

    // Duplicate ACK
    else if ((state == state.ESTABLISHED || state == state.SHUTDOWN) && clientSocket && 
              transportPacket.getType() == Transport.ACK && this.lastAckReceived == transportPacket.getSeqNum()) {
      
      duplicateAckCount++;
      if (duplicateAckCount == 4) {
        ssthresh = (int) (currentWindowSize * CONGESTION_DECREASE_FACTOR);
        currentWindowSize = currentWindowSize * CONGESTION_DECREASE_FACTOR + 3 * Transport.MAX_PAYLOAD_SIZE; // RFC 5681
        byte[] payload = getFirstBufferedDataPacket();
        sendTransportData(currentSeqNo - outstandingDataSize(), payload, true);
        delayTimeout = true;
        timerOutstanding = false;
        seqNoTracked = -1;
        recoverSeqNo = currentSeqNo;
        updateTimer(transportPacket.getSeqNum());
      }
      else if (duplicateAckCount > 4) {
        recoverSeqNo = currentSeqNo;
      }
    }

    // Acknowledgement for an outstanding SYN packet
    else if (state == state.SYN_SENT && transportPacket.getType() == Transport.ACK &&
             transportPacket.getSeqNum() >= currentSeqNo - outstandingDataSize()) {
      state = state.ESTABLISHED;
      timerOutstanding = false;
      sendAllDataPossible();
   }

    // Accept incoming data into buffer if space permits.
    else if (state == state.ESTABLISHED && !clientSocket && transportPacket.getType() == Transport.DATA) {
      node.logDebug("acceptPacket: type DATA, Payload size: " + transportPacket.getPayload().length);

      bufferReceivedData(transportPacket.getPayload(), transportPacket.getSeqNum());
      sendTransportPacket(Transport.ACK, currentSeqNo, dummy, false);
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

  protected void updateWindowSize(int advertisedWindow, int bytesAcked) {
    
    // Congestion Control
    if (currentWindowSize < ssthresh) {
      currentWindowSize += bytesAcked;                                    // Slow Start
    }
    else {
      currentWindowSize += Transport.MAX_PAYLOAD_SIZE * bytesAcked / currentWindowSize;
    }

    currentWindowSize = Math.min(currentWindowSize, advertisedWindow);    // Min of congestion control and flow control
    currentWindowSize = Math.min(currentWindowSize, BUFFER_SIZE);         // cwnd cannot be greater than buffer
    currentWindowSize = Math.max(currentWindowSize, Transport.MAX_PAYLOAD_SIZE); // ensure cwnd is at least one full packet
    node.logDebug("updateWindowSize: advertisedWindow is " + advertisedWindow);
    node.logDebug("updateWindowSize: new window size is " + (int) currentWindowSize);

  //  System.out.println("Current window size " + (int) currentWindowSize);
  //  System.out.println("Number of bytes in transit " + outstandingDataSize());
  }

  protected void updateTimeoutValue() {
    seqNoTracked = -1;
    int timeDelta = (int) (manager.now() - seqNoTimestamp);

    // First RTT measurement
    if (smoothedRTT < 0) {
      smoothedRTT = timeDelta;
      devRTT = timeDelta / 2;
    }
    // Subsequent RTT measurements 
    else {
      devRTT = (int) ((1 - BETA) * devRTT + BETA * Math.abs(smoothedRTT - timeDelta));
      smoothedRTT = (int) ((1 - ALPHA) * smoothedRTT + ALPHA * timeDelta);
    }
    timeout = smoothedRTT + Math.max(1, 4 * devRTT);
    node.logDebug("acceptPacket: timeDelta " + timeDelta + " ms");
    node.logDebug("acceptPacket: new timeout value " + timeout + " ms");
  }

  protected void printReceiverCharacter(int packetType, int seqNo) {
    if (packetType == Transport.SYN) {
      System.out.print("S");
    }
    else if (packetType == Transport.FIN) {
      System.out.print("F");
    }
    else if (packetType == Transport.DATA) {
      System.out.print(".");
    }
    else if (packetType == Transport.ACK && (seqNo > currentSeqNo - outstandingDataSize() ||
             (seqNo == currentSeqNo && state == State.SYN_SENT))) {
      System.out.print(":");
    }
    else if (packetType == Transport.ACK) {
      System.out.print("?");
    }

    // Repeat receipt of packet
    if ((packetType == Transport.SYN && state != State.LISTEN) ||
        (packetType == Transport.FIN && (state == State.CLOSED || state == State.SHUTDOWN || closePending)) ||
        (packetType == Transport.DATA && seqNo < currentSeqNo)) {
      System.out.print("!");
    }
  }

  protected void printSenderCharacter(int transportType, boolean repeat) {
    if (transportType == Transport.SYN) {
      System.out.print("S");
    }
    else if (transportType == Transport.FIN) {
      System.out.print("F");
    }
    else if (transportType == Transport.DATA) {
      System.out.print(".");
    }

    if (repeat) {
      System.out.print("!");
    }
  }

  protected void sendAllDataPossible() {
    int dataToSend = Math.min((int) currentWindowSize - outstandingDataSize(), unsentDataSize());
    if (dataToSend < 0 || (dataToSend < Transport.MAX_PAYLOAD_SIZE && state != State.SHUTDOWN && startData != endSendData) || (state == State.SHUTDOWN && dataToSend < Transport.MAX_PAYLOAD_SIZE && unsentDataSize() >= Transport.MAX_PAYLOAD_SIZE)) {
      dataToSend = 0;
    }
    byte[] payload = new byte[dataToSend];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = buffer[endSendData];
      endSendData = (endSendData + 1) % BUFFER_SIZE;
    }
    if (dataToSend > 0) {

      if (seqNoTracked == -1) {
        seqNoTracked = currentSeqNo;
        seqNoTimestamp = manager.now();
      }

      sendTransportData(currentSeqNo, payload, false);
      updateTimer(currentSeqNo);
      currentSeqNo += dataToSend;
    }
  }

  // The first byte of 'newData' corresponds to the sequence number 'seqNo'.
  // Buffer all data that will fit from newData, including in order bytes and
  // out of order bytes.
  protected void bufferReceivedData(byte[] newData, int seqNo) {
    for (int i = 0; i < newData.length; i++, seqNo++) {
      if (currentSeqNo == seqNo && bufferedDataSize() < BUFFER_SIZE - 1) {
        node.logDebug("bufferReceivedData: Byte for sequence number " + currentSeqNo + ": " + newData[i]); 
        buffer[endData] = newData[i];
        endData = (endData + 1) % BUFFER_SIZE;
        currentSeqNo++;
      }
      else if (currentSeqNo < seqNo && bufferedDataSize() + (seqNo - currentSeqNo) < BUFFER_SIZE - 1) {
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
    // Timeout of SYN packet
    if (state == State.SYN_SENT) {

      node.logDebug("handleSocketTimeout: Timeout occured for seqNo " + seqNo);

      timerOutstanding = false;
      ssthresh = (int) (currentWindowSize * CONGESTION_DECREASE_FACTOR);
      currentWindowSize = Transport.MAX_PAYLOAD_SIZE;
      sendTransportPacket(Transport.SYN, seqNo, dummy, true);
      updateTimer(seqNo);
    }
    // Timeout of DATA packet
    else if ((state == State.ESTABLISHED || state == State.SHUTDOWN) &&
              seqNo == currentSeqNo - outstandingDataSize()) {

      node.logDebug("handleSocketTimeout: timeout for seqNo " + seqNo + " at time " + manager.now());
      
      if (delayTimeout) {
        delayTimeout = false;
        return;
      }

      node.logDebug("handleSocketTimeout: Timeout occured for seqNo " + seqNo);

      consecutiveTimeouts++;

      // Exponential backoff of timeout value as per RFC 2988
      if (consecutiveTimeouts > 1) {
        node.logDebug("Doubling timeout " + timeout);
        timeout *= 2;
      }

      seqNoTracked = -1;
      timerOutstanding = false;
      ssthresh = (int) (currentWindowSize * CONGESTION_DECREASE_FACTOR);
      currentWindowSize = Transport.MAX_PAYLOAD_SIZE;
      duplicateAckCount = 0;
      byte[] payload = getFirstBufferedDataPacket();
      sendTransportPacket(Transport.DATA, seqNo, payload, true);
      updateTimer(seqNo);
    }
  }

  // return a byte array with as many bytes from buffered data that will
  // fit in one packet
  protected byte[] getFirstBufferedDataPacket() {
    int size = Math.min(Transport.MAX_PAYLOAD_SIZE, bufferedDataSize());
    currentSeqNo += Math.max(0, size - outstandingDataSize()); 
    endSendData = (startData + Math.max(outstandingDataSize(), size)) % BUFFER_SIZE;
    byte[] dataPacket = new byte[size];
    for (int i = 0; i < dataPacket.length; i++) {
      dataPacket[i] = buffer[(i + startData) % BUFFER_SIZE];
    }
    return dataPacket;
  }

  protected int bufferedDataSize() {
    return (endData - startData + BUFFER_SIZE) % BUFFER_SIZE;
  }

  protected int outstandingDataSize() {
    return (endSendData - startData + BUFFER_SIZE) % BUFFER_SIZE;
  }

  protected int unsentDataSize() {
    return (endData - endSendData + BUFFER_SIZE) % BUFFER_SIZE;
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
  protected void readBytesFromBuffer(byte[] buf, int pos, int len)             {
    for (int i = pos; i < pos + len; i++) {
      buf[i] = buffer[startData];
      startData = (startData + 1) % BUFFER_SIZE;
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
   // return 0;
  }

  // Breaks up 'payload' into appropriate sized byte arrays and sends each in
  // a transport packet where the first byte of payload has sequence number 'seqNo'
  protected void sendTransportData(int seqNo, byte[] payload, boolean repeat) {
    for (int i = 0; i < payload.length; i += Transport.MAX_PAYLOAD_SIZE) {
      int abbreviatedLength = Math.min(Transport.MAX_PAYLOAD_SIZE, payload.length - i);
      byte[] abbreviatedData = new byte[abbreviatedLength];
      for (int j = 0; j < abbreviatedLength; j++) {
   //     node.logDebug("sendTransportData: Byte for sequence number " + (seqNo + i + j) + ": " + payload[i + j]); 
        abbreviatedData[j] = payload[i + j];
      }

      sendTransportPacket(Transport.DATA, seqNo + i, abbreviatedData, repeat);
    }
  }

  // Helper function to send a transport packet.
  protected void sendTransportPacket(int transportType, int seqNo, byte[] payload, boolean repeat) {
    printSenderCharacter(transportType, repeat);

    node.logDebug("sendTransportPacket: sequence number of packet " + seqNo);
    node.logDebug("sendTransportPacket: sequence number of node " + currentSeqNo);

    if (transportType == Transport.DATA) {
      for (int i = 0; i < payload.length; i++) {
        node.logDebug("sendTransportPacket: Byte for sequence number " + (seqNo + i) + ": " + payload[i]);
      }
    }
    int advertisedWindow;
    if (transportType == Transport.ACK) {
      advertisedWindow = remainingBufferSize();
    }
    else {
      advertisedWindow = BUFFER_SIZE;
    }

    Transport transportPacket = new Transport(local_port, dest_port, transportType, advertisedWindow, seqNo, payload);
    byte[] packetPayload = transportPacket.pack();
    node.sendSegment(local_adr, dest_adr, Protocol.TRANSPORT_PKT, packetPayload);
  }

  // Creates a timer if valid timer is not currently outstanding
  protected void updateTimer(int seqNo) {
    if (!timerOutstanding) {
      node.logDebug("updateTimer: timer registered for seqNo " + seqNo + " at time " + manager.now());
	    try {
        String[] paramTypes = {"java.lang.Integer"};
        Object[] params = {new Integer(seqNo)};
	      Method method = Callback.getMethod("handleSocketTimeout", this, paramTypes);
	      Callback cb = new Callback(method, this, params);
	      this.manager.addTimer(local_adr, timeout, cb);
        timerOutstanding = true;
	    }catch(Exception e) {
	      node.logError("Failed to add timer callback. Method Name: " + "handleSocketTimeout" +
		       "\nException: " + e);
	    }
    }
  }

  protected int remainingBufferSize() {
    return BUFFER_SIZE - bufferedDataSize() - 1;
  }
}
