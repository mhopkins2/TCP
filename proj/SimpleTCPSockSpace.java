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

  public SimpleTCPSockSpace() {
    listenSockets = new Hashtable<String, TCPSock>();
    connectionSockets = new Hashtable<String, TCPSock>();
    socketCounts = new Hashtable<String, Integer>();
  }

  boolean claimPort(int src_adr, int src_port) {
    String key = src_adr + ":" + src_port;
    if (!socketCounts.contains(key) || socketCounts.get(key) == 0) {
      socketCounts.put(key, 1);
      return true;
    }
    else {
      return false;
    }
  }

  // Add new listen socket and return true since claimPort must have already
  // been called. Therefore, there cannot be a conflicting listen socket.
  boolean registerListenSocket(int src_adr, int src_port, TCPSock socket) {
    String key = src_adr + ":" + src_port;
    listenSockets.put(key, socket);
    return true;
  }

  // Remove listen socket
  void deregisterListenSocket(int src_adr, int src_port) {
    String key = src_adr + ":" + src_port;
    socketCounts.put(key, socketCounts.get(key) - 1);
    listenSockets.remove(key);
  }

  // Add new direct connection socket and return true if there isn't already another
  // direct connection socket at src_adr:src_port to dest_adr:dest_port
  boolean resgisterConnectionSocket(int src_adr, int src_port,
                                        int dest_adr, int dest_port, TCPSock socket) {
    String fullKey = src_adr + ":" + src_port + ":" + dest_adr + ":" + dest_port;
    String partialKey = src_adr + ":" + src_port;

    if (connectionSockets.contains(fullKey)) {
      return false;
    }
    else {
      // This connection corresponds to a new connection
      // initiated by a welcome socket
      if (listenSockets.contains(partialKey)) {
        socketCounts.put(partialKey, socketCounts.get(partialKey) + 1);
      }
      connectionSockets.put(fullKey, socket);
      return true;
    }
  }

  // Remove connection socket
  void deregisterConnectionSocket(int src_adr, int src_port, int dest_adr, int dest_port) {
    String fullKey = src_adr + ":" + src_port + ":" + dest_adr + ":" + dest_port;
    String partialKey = src_adr + ":" + src_port;
    socketCounts.put(partialKey, socketCounts.get(partialKey) - 1);
    connectionSockets.remove(fullKey);
  }

  // If there is a direct connection between local address and port and destination
  // address and port, return that connection. Otherwise, return a listen connection
  // at local address and port if it exists. Otherwise, return null.
  TCPSock demultiplexConnection(int src_adr, int src_port, int dest_adr, int dest_port) {
    String connectionKey = src_adr + ":" + src_port + ":" + dest_adr + ":" + dest_port;
    String listenKey = src_adr + ":" + src_port;

    if (connectionSockets.contains(connectionKey)) {
      return connectionSockets.get(connectionKey);
    }
    else if (listenSockets.contains(listenKey)) {
      return listenSockets.get(listenKey);
    }
    else {
      return null;
    }
  }
}