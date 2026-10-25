package peygirbuild;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

public final class LocalUdpPort {
    private static final AtomicInteger candidate=new AtomicInteger(40000+(int)(ProcessHandle.current().pid()%8000));
    public static int nextPort() {
        for(int i=0;i<9000;i++) {
            int port=40000+Math.floorMod(candidate.getAndIncrement()-40000,9000);
            try(DatagramSocket probe=new DatagramSocket(null)) {
                probe.bind(new InetSocketAddress("0.0.0.0",port));return port;
            } catch(java.net.SocketException unavailable) { }
        }
        throw new IllegalStateException("No free explicit UDP port for Gradle cache locks");
    }
}
