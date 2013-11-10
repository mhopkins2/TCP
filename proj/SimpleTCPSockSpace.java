import java.util.Hashtable;

/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Description: Fishnet simple TCP socket space</p>
 *
 * <p>Copyright: Copyright (c) 2013</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Michael Hopkins
 * @version 1.0
 */
public class SimpleTCPSockSpace {
  // Hashtable of all sockets actively listening for connections
  // The key is of the form src_adr:src_port
  Hashtable<String, TCPSock> listenSockets;

  // Hashtable of all sockets directly connected to a destination
  // The key is of the form src_adr:src_port:dest_adr:dest_port
  Hashtable<String, TCPSock> connectionSockets;

  // Hashtable that counts the number of sockets that are bound to 
  // src_adr:src_port (could be multiple because of connections)
  // accepted through welcome socket. Bind is only successful
  // if this count is 0.
  Hashtable<String, Integer> socketCounts;

  private Node node;

  public SimpleTCPSockSpace(Node node) {
    listenSockets = new Hashtable<String, TCPSock>();
    connectionSockets = new Hashtable<String, TCPSock>();
    socketCounts = new Hashtable<String, Integer>();
    this.node = node;
  }

  // Return true and award port to inquiring TCPSock if no 
  // TCPSock is currently bound to src_adr:src_port
  public boolean claimPort(int src_adr, int src_port) {
    String key = getLocalConnectionString(src_adr, src_port);
    if (!socketCounts.containsKey(key) || socketCounts.get(key) == 0) {
      socketCounts.put(key, 1);
      return true;
    }
    else {
      return false;
    }
  }

  // Add new listen socket and return true since claimPort must have already
  // been called. Therefore, there cannot be a conflicting listen socket.
  public boolean registerListenSocket(int src_adr, int src_port, TCPSock socket) {
    String key = getLocalConnectionString(src_adr, src_port);
    listenSockets.put(key, socket);
    return true;
  }

  // Remove listen socket
  public void deregisterListenSocket(int src_adr, int src_port) {
    String key = getLocalConnectionString(src_adr, src_port);
    socketCounts.put(key, socketCounts.get(key) - 1);
    listenSockets.remove(key);
  }

  // Add new direct connection socket and return true if there isn't already another
  // direct connection socket at src_adr:src_port to dest_adr:dest_port
  public boolean resgisterConnectionSocket(int src_adr, int src_port,
                                    int dest_adr, int dest_port, TCPSock socket) {
    String fullKey = getFullConnectionString(src_adr, src_port, dest_adr, dest_port);
    String partialKey = getLocalConnectionString(src_adr, src_port);

    if (connectionSockets.containsKey(fullKey)) {
      return false;
    }
    else {
      // This connection corresponds to a new connection
      // initiated by a welcome socket
      if (listenSockets.containsKey(partialKey)) {
        socketCounts.put(partialKey, socketCounts.get(partialKey) + 1);
      }
      connectionSockets.put(fullKey, socket);
      return true;
    }
  }

  // Remove connection socket
  public void deregisterConnectionSocket(int src_adr, int src_port, int dest_adr, int dest_port) {
    String fullKey = getFullConnectionString(src_adr, src_port, dest_adr, dest_port);
    String partialKey = getLocalConnectionString(src_adr, src_port);
    socketCounts.put(partialKey, socketCounts.get(partialKey) - 1);
    connectionSockets.remove(fullKey);
  }

  // If there is a direct connection between local address and port and destination
  // address and port, return that connection. Otherwise, return a listen connection
  // at local address and port if it exists. Otherwise, return null.
  TCPSock demultiplexConnection(int src_adr, int src_port, int dest_adr, int dest_port) {
    node.logDebug("demultiplexConnection: listening sockets count: " + listenSockets.size());
    node.logDebug("demultiplexConnection: connection sockets count: " + connectionSockets.size());
    node.logDebug("demultiplexConnection: searching for: " + src_adr + ":" + src_port + " " + dest_adr + ":" + dest_port);

    String connectionKey = getFullConnectionString(src_adr, src_port, dest_adr, dest_port);
    String listenKey = getLocalConnectionString(src_adr, src_port);

    if (connectionSockets.containsKey(connectionKey)) {
      return connectionSockets.get(connectionKey);
    }
    else if (listenSockets.containsKey(listenKey)) {
      return listenSockets.get(listenKey);
    }
    else {
      return null;
    }
  }

  public void deregisterPortOnly(int src_adr, int src_port) {
    String key = getLocalConnectionString(src_adr, src_port);
    socketCounts.put(key, 0);
  }

  protected String getLocalConnectionString(int src_adr, int src_port) {
    return src_adr + ":" + src_port;
  }
    
  protected String getFullConnectionString(int src_adr, int src_port, int dest_adr, int dest_port) {
    return src_adr + ":" + src_port + ":" + dest_adr + ":" + dest_port;
  }
}
