package tunnel;

import com.spencerwi.either.Either;
import jdwp.*;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.EventCommon;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * handles unfinished requests and supports listeners
 */
public class State {

    static class WrappedPacket<R> {
        final R packet;
        final long time;

        WrappedPacket(R packet, long time) {
            this.packet = packet;
            this.time = time;
        }
    }

    interface Listener {

        default void onRequest(WrappedPacket<Request<?>> request) {
            onRequest(request.packet);
        }

        default void onRequest(Request<?> request) {
        }

        default void onReply(WrappedPacket<Request<?>> request, WrappedPacket<ReplyOrError<?>> reply) {
            onReply(reply.packet);
        }

        default void onReply(ReplyOrError<?> reply) {
        }

        default void onEvent(WrappedPacket<Events> event) {
            onEvent(event.packet);
        }

        default void onEvent(Events events) {
        }
    }

    public static class LoggingListener implements Listener {
        @Override
        public void onRequest(Request<?> request) {
            print("Request", request);
        }

        @Override
        public void onReply(ReplyOrError<?> reply) {
            print("Reply", reply);
        }

        @Override
        public void onEvent(Events events) {
            print("Event", events);
        }

        void print(String prefix, ParsedPacket packet) {
            var ps = packet.toString();
            if (ps.length() > 200) {
                ps = ps.substring(0, 200) + String.format("... (%d more)", ps.length() - 200);
            }
            System.out.printf("%.3f: %10s[%7d]: %s\n", System.currentTimeMillis() / 1000.0, prefix, packet.getId(), ps);
        }
    }

    public static class CollectingListener implements Listener {
        @Getter
        private final List<Request<?>> requests = new ArrayList<>();
        @Getter
        private final List<ReplyOrError<?>> replies = new ArrayList<>();
        @Getter
        private final List<Events> events = new ArrayList<>();

        @Override
        public void onRequest(Request<?> request) {
            requests.add(request);
        }

        @Override
        public void onReply(ReplyOrError<?> reply) {
            replies.add(reply);
        }

        @Override
        public void onEvent(Events events) {
            this.events.add(events);
        }
    }

    private final VM vm;
    private int currentRequestId;
    private final Map<Integer, WrappedPacket<Request<?>>> unfinished;
    private final Set<Listener> listeners;

    public State(VM vm) {
        this.vm = vm;
        this.unfinished = new HashMap<>();
        this.listeners = new HashSet<>();
    }

    public State() {
        this(new VM(0));
    }

    synchronized void addRequest(WrappedPacket<Request<?>> request) {
        unfinished.put(request.packet.getId(), request);
        listeners.forEach(l -> l.onRequest(request));
    }

    synchronized WrappedPacket<Request<?>> getUnfinishedRequest(int id) {
        var ret = unfinished.get(id);
        if (ret == null) {
            throw new NoSuchElementException("No request with id " + id);
        }
        return ret;
    }

    synchronized void addReply(WrappedPacket<ReplyOrError<?>> reply) {
        var id = reply.packet.getId();
        var request = getUnfinishedRequest(id);
        listeners.forEach(l -> l.onReply(request, reply));
        unfinished.remove(id);
    }

    void addEvent(WrappedPacket<Events> event) {
        listeners.forEach(l -> l.onEvent(event));
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public Request<?> readRequest(InputStream inputStream) throws IOException {
        var ps = PacketInputStream.read(vm, inputStream);
        var request = JDWP.parse(ps);
        addRequest(new WrappedPacket<>(request, System.currentTimeMillis()));
        vm.captureInformation(request);
        return request;
    }

    public Either<Events, ReplyOrError<?>> readReply(InputStream inputStream) throws IOException {
        var ps = PacketInputStream.read(vm, inputStream);
        if (ps.isReply()) {
            var request = getUnfinishedRequest(ps.id());
            var reply = request.packet.parseReply(ps);
            addReply(new WrappedPacket<>(reply, System.currentTimeMillis()));
            if (reply.hasReply()) {
                vm.captureInformation(request.packet, reply.getReply());
            }
            return Either.right(reply);
        } else {
            var events = Events.parse(ps);
            for (EventCommon event : events.events) {
                vm.captureInformation(event);
            }
            addEvent(new WrappedPacket<>(events, System.currentTimeMillis()));
            return Either.left(events);
        }
    }

    /**
     * write reply without triggering listeners
     */
    public void writeReply(OutputStream outputStream, Either<Events, ReplyOrError<?>> reply) throws IOException {
        if (reply.isLeft()) {
            reply.getLeft().toPacket(vm).write(outputStream);
        } else {
            reply.getRight().toPacket(vm).write(outputStream);
        }
    }

    /**
     * write request without triggering listeners
     */
    public void writeRequest(OutputStream outputStream, Request<?> request) throws IOException {
        request.toPacket(vm).write(outputStream);
    }

    synchronized void submitRequest(OutputStream outputStream, Request<?> request) throws IOException {
        addRequest(new WrappedPacket<>(request, System.currentTimeMillis()));
        request.toPacket(vm).write(outputStream);
    }

    public VM vm() {
        return vm;
    }
}
