package tunnel;

import ch.qos.logback.classic.Logger;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.EventCommon;
import jdwp.EventCmds.Events.TunnelRequestReplies;
import jdwp.*;
import jdwp.TunnelCmds.EvaluateProgramReply;
import jdwp.TunnelCmds.EvaluateProgramRequest;
import jdwp.util.Pair;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.slf4j.LoggerFactory;
import tunnel.synth.Partitioner;
import tunnel.synth.Synthesizer;
import tunnel.synth.program.Program;
import tunnel.util.Either;
import tunnel.util.ToCode;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static tunnel.State.Mode.CLIENT;
import static tunnel.State.Mode.NONE;

/**
 * handles unfinished requests and supports listeners
 */
@Getter
public class State {

    private final Logger LOG;

    public enum Mode {
        /** use programs on request and send them to the server */
        CLIENT,
        /** use programs on events and evaluate them directly */
        SERVER,
        NONE
    }

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

        public WrappedPacket(R packet) {
            this(packet, System.currentTimeMillis());
        }
    }

    private final VM vm;
    private final Mode mode;
    private int currentRequestId;
    private final Map<Integer, WrappedPacket<Request<?>>> unfinished;

    private final Map<Integer, EvaluateProgramRequest> unfinishedEvaluateRequests;
    private final Set<Listener> listeners;

    private final ReplyCache replyCache;
    private final ProgramCache programCache;

    public State(VM vm, Mode mode) {
        this.vm = vm;
        this.unfinished = new HashMap<>();
        this.listeners = new HashSet<>();
        this.replyCache = new ReplyCache();
        this.programCache = new ProgramCache();
        this.unfinishedEvaluateRequests = new HashMap<>();
        this.mode = mode;
        LOG = (Logger) LoggerFactory.getLogger((mode == NONE ? "" : mode.name().toLowerCase() + "-") + "tunnel");
        if (mode != NONE) {
            registerCacheListener();
            registerProgramCacheListener();
        }
    }

    public State() {
        this(NONE);
    }

    public State(Mode mode) {
        this(new VM(0), mode);
    }

    private void registerCacheListener() {
        listeners.add(new Listener() {
            @Override
            public void onRequest(Request<?> request) {
                if (!request.onlyReads()) {
                    replyCache.invalidate();
                }
            }

            @Override
            public void onReply(Request<?> request, Reply reply) {
                if (request.onlyReads()) {
                    replyCache.put(request, reply);
                }
            }

            @Override
            public void onEvent(Events events) {
                replyCache.invalidate();
            }
        });
    }

    private void registerProgramCacheListener() {
        listeners.add(new Partitioner().addListener(partition -> {
            if (partition.hasCause()) {
                var cause = partition.getCause();
                if (cause.isLeft() == (State.this.mode == CLIENT)) {
                    var program = Synthesizer.synthesizeProgram(partition);
                    LOG.info("Cache program {}", program.toPrettyString());
                    programCache.accept(program);
                }
            }
        }));
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
        LOG.debug(request.toCode());
        addRequest(new WrappedPacket<>(request));
        try {
            vm.captureInformation(request);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to capture information from request %s", request), e);
        }
        return request;
    }

    public @Nullable Either<Events, ReplyOrError<?>> readReply(InputStream inputStream,
                                                               @Nullable OutputStream clientOutputStream) throws IOException {
        var ps = PacketInputStream.read(vm, inputStream);
        if (ps.isReply()) {
            if (unfinishedEvaluateRequests.containsKey(ps.id())) {
                var reply = PacketError.call(() -> EvaluateProgramReply.parse(ps), ps);
                LOG.debug(reply.toCode());
                unfinishedEvaluateRequests.remove(ps.id());
                if (reply.isError()) {
                    addReply(new WrappedPacket<>(new ReplyOrError<>(ps.id(), (short)1)));
                } else {
                    var realReply = reply.getReply();
                    assert clientOutputStream != null;
                    for (var p : BasicTunnel.parseEvaluateProgramReply(vm, realReply)) {
                        captureInformation(p.first, p.second);
                        replyCache.put(p.first, p.second);
                    }
                    // now go through all unfinished requests and check
                    for (WrappedPacket<Request<?>> value : unfinished.values()) {
                        var unfinishedRequest = value.packet;
                        if (hasCachedReply(unfinishedRequest)) {
                            var cachedReply = replyCache.get(unfinishedRequest);
                            addReply(new WrappedPacket<>(new ReplyOrError<>(cachedReply),
                                    System.currentTimeMillis()));
                            writeReply(clientOutputStream, Either.right(new ReplyOrError<>(cachedReply)));
                        }
                    }
                    var originalReply = new ReplyOrError<>(ps.id(), replyCache.get(getUnfinishedRequest(ps.id()).getPacket()));
                    addReply(new WrappedPacket<>(originalReply));
                    return Either.right(originalReply);
                }
            }
            var request = getUnfinishedRequest(ps.id());

            var reply = PacketError.call(() -> request.packet.parseReply(ps), ps);
            addReply(new WrappedPacket<>(reply));
            if (reply.isReply()) {
                var realReply = reply.getReply();
                captureInformation(request.packet, realReply);
            }
            return Either.right(reply);
        } else {
            var events = Events.parse(ps);
            LOG.debug(events.toCode());
            captureInformation(events);
            for (EventCommon event : events.events) {
                if (event instanceof TunnelRequestReplies) {
                    assert events.events.size() == 1; // is the only event in the list
                    var parsed = BasicTunnel.parseTunnelRequestReplyEvent(vm, (TunnelRequestReplies)event);
                    addEvent(new WrappedPacket<>(parsed.first));
                    for (Pair<Request<?>, Reply> p : parsed.second) {
                        replyCache.put(p.first, p.second);
                    }
                    return Either.left(parsed.first);
                }
            }
            addEvent(new WrappedPacket<>(events));
            return Either.left(events);
        }
    }

    public void captureInformation(Request<?> request, Reply reply) {
        try {
            vm.captureInformation(request, reply);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to capture information from request %s and reply %s",
                    request, reply), e);
        }
    }

    public void captureInformation(Events events) {
        try {
            vm.captureInformation(events);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to capture information from events %s", events), e);
        }
    }

    /**
     * write reply without triggering listeners
     */
    public void writeReply(OutputStream outputStream, Either<Events, ReplyOrError<?>> reply) {
        LOG.debug("Write {}", ((ToCode)reply.get()).toCode());
        try {
            ((ParsedPacket)reply.get()).toPacket(vm).write(outputStream);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to write reply or events %s", reply.<ParsedPacket>get()), e);
        }
    }

    public void addAndWriteReply(OutputStream outputStream, Either<Events, ReplyOrError<?>> reply) {
        if (reply.isRight()) {
            addReply(new WrappedPacket<>(reply.getRight()));
        } else {
            addEvent(new WrappedPacket<>(reply.getLeft()));
        }
        writeReply(outputStream, reply);
    }

    public void addAndWriteError(OutputStream outputStream, int id, int error) {
        var replyOrError = new ReplyOrError<>(id, (short) error);
        addAndWriteReply(outputStream, Either.right(replyOrError));
    }

    /**
     * write request without triggering listeners
     */
    public void writeRequest(OutputStream outputStream, Request<?> request) {
        try {
            LOG.debug("Write {}", request.toCode());
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

    public boolean hasUnfinishedRequests() {
        return unfinished.size() > 0;
    }

    public boolean hasUnfinishedRequests(int ignoreId) {
        return unfinished.size() > 1 || (unfinished.size() == 1 && !unfinished.containsKey(ignoreId));
    }

    public boolean hasUnfinishedRequest(int id) {
        return unfinished.containsKey(id);
    }

    public boolean hasCachedReply(Request<?> request) {
        return replyCache.get(request) != null;
    }

    public Reply getCachedReply(Request<?> request) {
        return replyCache.get(request);
    }

    public boolean hasCachedProgram(ParsedPacket packet) {
        return programCache.get(packet).isPresent();
    }

    public Program getCachedProgram(ParsedPacket packet) {
        return programCache.get(packet).get();
    }

    public void addUnfinishedEvaluateRequest(EvaluateProgramRequest evaluateRequest) {
        unfinishedEvaluateRequests.put(evaluateRequest.getId(), evaluateRequest);
    }
}
