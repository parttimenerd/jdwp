package tunnel;

import jdwp.EventInstance;
import jdwp.Reply;
import jdwp.Request;
import jdwp.Value;
import jdwp.util.Pair;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Proxy.Type;
import java.net.Socket;
import java.util.List;
import java.util.Map;

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
