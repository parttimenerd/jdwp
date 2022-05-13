package tunnel;

import java.net.InetSocketAddress;

public class Tunnel {

    InetSocketAddress clientAddress;
    InetSocketAddress serverAddress;
    State state;

    public Tunnel(int clientPort, InetSocketAddress serverAddress) {
        this.clientAddress = new InetSocketAddress(clientPort);
        this.serverAddress = serverAddress;
    }
    public Tunnel(InetSocketAddress clientAddress, InetSocketAddress serverAddress) {
        this.clientAddress = clientAddress;
        this.serverAddress = serverAddress;
    }

    /*State connect() {
        var clientSocket = new Socket(new Proxy(Type.DIRECT, clientAddress));
        var serverSocket = new Socket(new Proxy(Type.DIRECT, serverAddress));
    }*/
}
