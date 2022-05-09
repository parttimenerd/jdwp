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


    static class State {

        @Getter
        static class Item<R extends Value & Reply> {
            @Nullable
            final Request<R> request;
            @Nullable
            final R reply;
            @Nullable
            final EventInstance event;

            private Item(@Nullable Request<R> request, @Nullable R reply, @Nullable EventInstance event) {
                this.request = request;
                this.reply = reply;
                this.event = event;
            }

            public Item(Request<R> request) {
                this(request, null, null);
            }

            public Item(EventInstance event) {
                this(null, null, event);
            }

            public Item<R> addReply(R reply) {
                assert this.reply == null && request != null;
                return new Item<R>(this.request, reply, null);
            }

            public boolean isEvent() {
                return event != null;
            }

            public boolean isRequest() {
                return request != null;
            }

            public boolean hasReply() {
                return reply != null;
            }
        }

        List<Item> requests;
        Map<Integer, Request<?>> unfinished;

        synchronized void add(Request<?> request) {
            requests.add(new Item<>(request));
            unfinished.put(request.getId(), request);
        }

        @SuppressWarnings("unchecked")
        synchronized <R extends Value & Reply> Request<R> getUnfinishedRequest(int id) {
            return (Request<R>) unfinished.get(id);
        }

        synchronized <R extends Value & Reply> void add(R reply) {
            requests.add(new Item<R>(getUnfinishedRequest(reply.getId())).addReply(reply));
        }

        synchronized void add(EventInstance event) {
            requests.add(new Item<>(event));
        }
    }

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
