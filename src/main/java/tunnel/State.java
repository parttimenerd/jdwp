package tunnel;

import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.EventCommon;
import jdwp.*;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import tunnel.util.Either;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * handles unfinished requests and supports listeners
 */
public class State {

    public static class NoSuchRequestException extends PacketError {

        public NoSuchRequestException(int id) {
            super("No request with id " + id);
        }
    }

    @Getter
    @EqualsAndHashCode
    @AllArgsConstructor
    public static class WrappedPacket<R> {
        final R packet;
        final long time;
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

    public void addRequest(WrappedPacket<Request<?>> request) {
        unfinished.put(request.packet.getId(), request);
        listeners.forEach(l -> l.onRequest(request));
    }

    public WrappedPacket<Request<?>> getUnfinishedRequest(int id) {
        var ret = unfinished.get(id);
        if (ret == null) {
            throw new NoSuchRequestException(id);
        }
        return ret;
    }

    public void addReply(WrappedPacket<ReplyOrError<?>> reply) {
        var id = reply.packet.getId();
        var request = getUnfinishedRequest(id);
        listeners.forEach(l -> l.onReply(request, reply));
        unfinished.remove(id);
    }

    public void addEvent(WrappedPacket<Events> event) {
        listeners.forEach(l -> l.onEvent(event));
    }

    public State addListener(Listener listener) {
        listeners.add(listener);
        return this;
    }

    public Request<?> readRequest(InputStream inputStream) throws IOException {
        var ps = PacketInputStream.read(vm, inputStream); // already wrapped in PacketError
        var request = PacketError.call(() -> JDWP.parse(ps), ps);
        addRequest(new WrappedPacket<>(request, System.currentTimeMillis()));
        try {
            vm.captureInformation(request);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to capture information from request %s", request), e);
        }
        return request;
    }

    public Either<Events, ReplyOrError<?>> readReply(InputStream inputStream) throws IOException {
        var ps = PacketInputStream.read(vm, inputStream);
        if (ps.isReply()) {
            var request = getUnfinishedRequest(ps.id());

            var reply = PacketError.call(() -> request.packet.parseReply(ps), ps);
            addReply(new WrappedPacket<>(reply, System.currentTimeMillis()));
            if (reply.isReply()) {
                try {
                    vm.captureInformation(request.packet, reply.getReply());
                } catch (Exception | AssertionError e) {
                    throw new PacketError(String.format("Failed to capture information from request %s and reply %s",
                            request, reply), e);
                }
            }
            return Either.right(reply);
        } else {
            var events = Events.parse(ps);
            for (EventCommon event : events.events) {
                try {
                vm.captureInformation(event);
                } catch (Exception | AssertionError e) {
                    throw new PacketError(String.format("Failed to capture information from event %s", event), e);
                }
            }
            addEvent(new WrappedPacket<>(events, System.currentTimeMillis()));
            return Either.left(events);
        }
    }

    /**
     * write reply without triggering listeners
     */
    public void writeReply(OutputStream outputStream, Either<Events, ReplyOrError<?>> reply) {
        try {
            ((ParsedPacket)reply.get()).toPacket(vm).write(outputStream);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to write reply or events %s", reply.<ParsedPacket>get()), e);
        }
    }

    /**
     * write request without triggering listeners
     */
    public void writeRequest(OutputStream outputStream, Request<?> request) {
        try {
            request.toPacket(vm).write(outputStream);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to write request %s", request));
        }
    }

    public VM vm() {
        return vm;
    }

    public void tick() {
        listeners.forEach(Listener::onTick);
    }
}
