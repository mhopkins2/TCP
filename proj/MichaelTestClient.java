/**
 * <p>Title: CPSC 433/533 Programming Assignment</p>
 *
 * <p>Copyright: Copyright (c) 2013</p>
 *
 * <p>Company: Yale University</p>
 *
 * @author Michael Hopkins
 * @version 1.0
 */

/**
 * <p> A modified test client from example provided </p>
 */
public class MichaelTestClient extends FishThread {
    private TCPSock[] socks;
    private long interval;
    private byte[][] buf;

    public static final long DEFAULT_CLIENT_INTERVAL = 1000;
    public static final int DEFAULT_BUFFER_SZ = 65536;

    // number of bytes to send
    private int[] amount;
    // starting and finishing time in milliseconds
    private long startTime;
    private long finishTime;
    private int[] pos;
    private boolean[] closed;
    private int closeCount;

    public MichaelTestClient(Manager manager, Node node, TCPSock[] socks, int amount,
                          long interval, int sz) {
        super(manager, node);
        this.socks = socks;
        this.interval = interval;
        this.buf = new byte[socks.length][sz];
        this.amount = new int[socks.length];
        this.pos = new int[socks.length];
        this.closed = new boolean[socks.length];
        for (int i = 0; i < socks.length; i++) {
          this.amount[i] = amount;
          this.pos[i] = 0;
          this.closed[i] = false;
        }
        this.startTime = 0;
        this.finishTime = 0;
        this.closeCount = 0;

        this.setInterval(this.interval);
    }

    public MichaelTestClient(Manager manager, Node node, TCPSock[] socks, int amount) {
        this(manager, node, socks, amount,
             DEFAULT_CLIENT_INTERVAL,
             DEFAULT_BUFFER_SZ);
    }

    public void execute() {
        int currentIndex = (int) (Math.random() * socks.length); 

        if (socks[currentIndex].isConnectionPending()) {
            //node.logOutput("connecting...");
            return;
        } else if (socks[currentIndex].isConnected()) {

            if (startTime == 0) {
                // record starting time
                startTime = manager.now();
                node.logOutput("time = " + startTime + " msec");
                node.logOutput("started");
            }

            if (amount[currentIndex] == 0) {
                // sending completed, initiate closure of connection
                node.logOutput("time = " + manager.now());
                node.logOutput("sending completed");
                node.logOutput("closing connection...");
                socks[currentIndex].close();
                return;
            }

            //node.logOutput("sending...");
            int index = pos[currentIndex] % buf[currentIndex].length;

            if (index == 0) {
                // generate new data
                for (int i = 0; i < buf[currentIndex].length; i++) {
                    buf[currentIndex][i] = (byte) i;
                }
            }

            int len = Math.min(buf[currentIndex].length - index, amount[currentIndex]);
            byte[] bufCopy = new byte[len];
            for (int i = 0; i < len; i++) {
              bufCopy[i] = buf[currentIndex][index + i];
            }
            int count = socks[currentIndex].write(bufCopy, 0, len);

            if (count == -1) {
                // on error, release the socket immediately
                node.logError("time = " + manager.now() + " msec");
                node.logError("sending aborted");
                node.logError("position = " + pos);
                node.logError("releasing connection...");
                socks[currentIndex].release();
                this.stop();
                return;
            }

            pos[currentIndex] += count;
            amount[currentIndex] -= count;

            //node.logOutput("time = " + manager.now());
            //node.logOutput("bytes sent = " + count);
            return;
        } else if (socks[currentIndex].isClosurePending()) {
            //node.logOutput("closing connection...");
            return;
        } else if (socks[currentIndex].isClosed() && !closed[currentIndex]) {
            socks[currentIndex].release();
            closed[currentIndex] = true;
            closeCount++;

            if (closeCount == socks.length) {
              finishTime = manager.now();
              node.logOutput("time = " + manager.now() + " msec");
              node.logOutput("connection closed");
              node.logOutput("time elapsed = " +
                             (finishTime - startTime) + " msec");
              this.stop();
            }
            return;
        }
        else if (socks[currentIndex].isClosed()) {
          return;
        }

        node.logError("shouldn't reach here");
        System.exit(1);
    }
}
