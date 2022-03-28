package tunnel;

import java.net.InetSocketAddress;

public class Tunnel {

    InetSocketAddress src;
    InetSocketAddress dst;
    public Tunnel(InetSocketAddress src, InetSocketAddress dst) {
        this.src = src;
        this.dst = dst;
    }

    void run() {

    }
}
