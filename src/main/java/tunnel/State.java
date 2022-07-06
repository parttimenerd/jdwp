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
import lombok.Setter;
import org.slf4j.LoggerFactory;
import tunnel.ProgramCache.DisabledProgramCache;
import tunnel.ReplyCache.DisabledReplyCache;
import tunnel.synth.Partitioner;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.Synthesizer;
import tunnel.synth.program.Evaluator;
import tunnel.synth.program.Evaluator.EvaluationAbortException;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Program;
import tunnel.util.Either;
import tunnel.util.ToStringMode;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

import static tunnel.State.Mode.*;

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
    public static class WrappedPacket<R> {
        final R packet;
        final long time;

        public WrappedPacket(R packet, long time) {
            this.packet = Objects.requireNonNull(packet);
            this.time = time;
        }

        public WrappedPacket(R packet) {
            this(packet, System.currentTimeMillis());
        }
    }

    @AllArgsConstructor
    @Getter
    @Setter
    public static class Formatter {

        static final int MAX_LENGTH = 200;
        private ToStringMode packetToStringMode;
        private ToStringMode partitionToStringMode;

        public String format(Packet packet) {
            return cut(packetToStringMode.format(packet));
        }

        public String format(ParsedPacket packet) {
            return cut(packetToStringMode.format(packet));
        }

        public String format(ReplyOrError<?> packet) {
            return cut(packetToStringMode.format(packet));
        }

        public String format(Partition partition) {
            return cut(packetToStringMode.format(partition));
        }

        static String cut(String s) {
            if (s.length() > MAX_LENGTH - 3) {
                return s.substring(0, MAX_LENGTH - 3) + "...";
            }
            return s;
        }
    }

    private final VM vm;
    private final Mode mode;
    private int currentRequestId;
    private final Map<Integer, WrappedPacket<Request<?>>> unfinished;
    private final Set<Integer> ignoredUnfinished;

    private final Map<Integer, EvaluateProgramRequest> unfinishedEvaluateRequests;
    private final LinkedBlockingQueue<Program> programsToSendToServer = new LinkedBlockingQueue<>();
    private final Set<Listener> listeners;
    private Partitioner clientPartitioner;
    private Partitioner serverPartitioner;

    private ReplyCache replyCache;
    private ProgramCache programCache;
    private Formatter formatter;
    private @Nullable Path programCacheFile;

    public State(VM vm, Mode mode, ReplyCache.Options replyCacheOptions) {
        this(vm, mode, replyCacheOptions, new Formatter(ToStringMode.CODE, ToStringMode.CODE));
    }

    public State(VM vm, Mode mode, ReplyCache.Options replyCacheOptions, Formatter formatter) {
        this.vm = vm;
        this.unfinished = new HashMap<>();
        this.ignoredUnfinished = new HashSet<>();
        this.listeners = new HashSet<>();
        this.replyCache = mode == Mode.SERVER ? new DisabledReplyCache(vm) : new ReplyCache(replyCacheOptions, vm);
        this.programCache = new ProgramCache();
        this.unfinishedEvaluateRequests = new HashMap<>();
        this.mode = mode;
        this.formatter = formatter;
        LOG = (Logger) LoggerFactory.getLogger((mode == NONE ? "" : mode.name().toLowerCase() + "-") + "tunnel");
        registerCacheListener();
        if (mode != NONE) {
            if (mode == CLIENT) {
                registerPartitionListener();
            }
        }
        vm.setLogger(LOG);
    }

    public State() {
        this(NONE);
    }

    public State(Mode mode) {
        this(new VM(0), mode, ReplyCache.DEFAULT_OPTIONS);
    }

    public void loadProgramCache(Path programCacheFile) {
        this.programCacheFile = programCacheFile;
        if (!programCacheFile.toFile().exists()) {
            return;
        }
        try (var input = Files.newInputStream(programCacheFile)) {
            int count = programCache.load(input);
            LOG.info("Loaded {} debug programs from {}", count, programCacheFile);
            programsToSendToServer.addAll(programCache.getServerPrograms());
        } catch (IOException e) {
            LOG.error("Cannot load program cache", e);
        }
    }

    public void storeProgramCache() {
        if (programCache == null || programCacheFile == null) {
            return;
        }
        if (!programCacheFile.toFile().exists()) {
            try {
                Files.createFile(programCacheFile);
            } catch (IOException e) {
                LOG.error("Cannot create program cache file {}", programCacheFile);
                return;
            }
        }
        try (var out = Files.newOutputStream(programCacheFile)) {
            programCache.store(out);
        } catch (IOException e) {
            LOG.error("Cannot store program cache", e);
        }
    }

    public State disableReplyCache() {
        this.replyCache = new DisabledReplyCache(vm);
        return this;
    }

    public State disableProgramCache() {
        this.programCache = new DisabledProgramCache();
        return this;
    }

    private void registerCacheListener() {
        listeners.add(replyCache);
    }

    /**
     * register client and server partitioner, only runs in client mode
     * <p>
     * we have to run both on the client, as the server has an incomplete picture, due to reply caching
     * in the client
     * <p>
     * Idea: send the gathered server programs (start with events) to the server
     */
    private void registerPartitionListener() {
        assert mode == CLIENT;
        clientPartitioner = new Partitioner(replyCache.getOptions().conservative).addListener(partition -> {
            LOG.error("Partition: {}", partition);
            if (partition.hasCause()) {
                var cause = partition.getCause();
                if (cause.isLeft()) {
                    try {
                        var program = Synthesizer.synthesizeProgram(partition);
                        LOG.info("Synthesized client program: {}", program.toPrettyString());
                        programCache.accept(program);
                        storeProgramCache();
                    } catch (AssertionError err) {
                        LOG.error("Error synthesizing program for partition {}", formatter.format(partition), err);
                    }
                }
            } else {
                LOG.error("omit because of missing cause");
            }
        });
        addPartitionerListener(clientPartitioner);
        serverPartitioner = new Partitioner(replyCache.getOptions().conservative).addListener(partition -> {
            LOG.error("Partition: {}", partition);
            if (partition.hasCause()) {
                var cause = partition.getCause();
                if (cause.isRight()) {
                    try {
                        var program = Synthesizer.synthesizeProgram(partition);
                        LOG.info("Synthesized server program: {}", program.toPrettyString());
                        programCache.accept(program);
                        storeProgramCache();
                        programsToSendToServer.add(program);
                    } catch (AssertionError err) {
                        LOG.error("Error synthesizing program for partition {}", formatter.format(partition), err);
                    }
                }
            } else {
                LOG.error("omit because of missing cause");
            }
        });
        addPartitionerListener(serverPartitioner);
    }

    private void addPartitionerListener(Partitioner partitioner) {
        listeners.add(new Listener() {
            @Override
            public void onRequest(WrappedPacket<Request<?>> request) {
                partitioner.onRequest(request);
            }

            @Override
            public void onReply(WrappedPacket<Request<?>> request, WrappedPacket<ReplyOrError<?>> reply) {
                if (request.getPacket() instanceof EvaluateProgramRequest) {
                    if (reply.getPacket().isReply()) {
                        var repl = BasicTunnel.parseEvaluateProgramReply(vm,
                                (EvaluateProgramReply) reply.getPacket().getReply());
                        partitioner.onReply(repl.get(0).first, repl.get(0).second);
                        return;
                    }
                }
                partitioner.onReply(request, reply);
            }

            @Override
            public void onEvent(WrappedPacket<Events> event) {
                partitioner.onEvent(event);
            }

            @Override
            public void onTick() {
                partitioner.onTick();
            }
        });
    }

    public void addRequest(WrappedPacket<Request<?>> request) {
        unfinished.put(request.packet.getId(), request);
        listeners.forEach(l -> l.onRequest(request));
    }

    public boolean isIgnoredUnfinished(int id) {
        return ignoredUnfinished.contains(id);
    }

    public void removeIgnoredUnfinished(int id) {
        ignoredUnfinished.remove(id);
    }

    public void ignoreUnfinished() {
        ignoredUnfinished.addAll(unfinished.keySet());
        unfinished.clear();
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

    public void cache(Request<?> request, ReplyOrError<?> reply, boolean prefetched) {
        if (request.onlyReads()) {
            replyCache.put(request, reply, prefetched);
        }
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
        LOG.debug("Read {}", formatter.format(request));
        addRequest(new WrappedPacket<>(request));
        try {
            vm.captureInformation(request);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to capture information from request %s", request), e);
        }
        return request;
    }

    public @Nullable Either<Events, ReplyOrError<?>> readReply(InputStream inputStream,
                                                               @Nullable OutputStream clientOutputStream,
                                                               OutputStream serverOutputStream) throws IOException {
        var ps = PacketInputStream.read(vm, inputStream);
        if (ps.isReply()) {
            if (ignoredUnfinished.contains(ps.id())) {
                LOG.debug("Ignoring reply {}", ps.id());
                ignoredUnfinished.remove(ps.id());
                return null;
            }
            if (unfinishedEvaluateRequests.containsKey(ps.id())) {
                var reply = PacketError.call(() -> EvaluateProgramReply.parse(ps), ps);
                LOG.debug(formatter.format(reply));
                var program = unfinishedEvaluateRequests.get(ps.id()).program;
                LOG.debug("original program " + unfinishedEvaluateRequests.get(ps.id()));
                unfinishedEvaluateRequests.remove(ps.id());
                if (reply.isError()) {
                    LOG.error("Error in evaluate program reply: " + reply);
                    addReply(new WrappedPacket<>(new ReplyOrError<>(ps.id(), EvaluateProgramRequest.METADATA,
                            (short) 1)));
                    removeCachedProgram(Program.parse(program.getValue()));
                } else {
                    var request = hasUnfinishedRequest(ps.id()) ? getUnfinishedRequest(ps.id()).getPacket() : null;
                    var realReply = reply.getReply();
                    ReplyOrError<?> originalReply = processReceivedRequestRepliesFromEvent(clientOutputStream, request,
                            BasicTunnel.parseEvaluateProgramReply(vm, realReply), ps.id());
                    LOG.debug("original reply " + originalReply);
                    return originalReply == null ? null : Either.right(originalReply);
                }
            }
            var request = getUnfinishedRequest(ps.id());

            var reply = PacketError.call(() -> request.packet.parseReply(ps), ps);
            addReply(new WrappedPacket<>(reply));
            if (reply.isReply()) {
                var realReply = reply.getReply();
                captureInformation(request.packet, realReply);
                cache(request.packet, new ReplyOrError<Reply>(reply.getReply()), false);
            }
            return Either.right(reply);
        } else {
            var events = Events.parse(ps);
            LOG.debug("Read event " + formatter.format(events));
            captureInformation(events);
            for (EventCommon event : events.events) {
                if (event instanceof TunnelRequestReplies) {
                    try {
                        assert events.events.size() == 1; // is the only event in the list
                        var parsed = BasicTunnel.parseTunnelRequestReplyEvent(vm, (TunnelRequestReplies) event);
                        var abortedRequestId = ((TunnelRequestReplies) event).abortedRequest.value;
                        if (abortedRequestId == -1) {
                            addEvent(new WrappedPacket<>(parsed.first));
                            for (Pair<Request<?>, ReplyOrError<?>> p : parsed.second) {
                                replyCache.put(p.first, p.second, true);
                            }
                        } else {
                            // the event caused the abortion of an EvaluateProgramRequest
                            // but we collected some requests before
                            if (!parsed.second.isEmpty()) {
                                // we did collect some replies
                                processReceivedRequestRepliesFromEvent(clientOutputStream,
                                        unfinished.entrySet().stream().filter(e -> unfinishedEvaluateRequests.containsKey(e.getKey()))
                                                .map(e -> e.getValue().getPacket()).findFirst().get(),
                                        parsed.second, abortedRequestId);
                            } else if (unfinished.containsKey(abortedRequestId)) {
                                // we have to resend the original request, as it apparently is still unfinished
                                serverOutputStream.write(unfinished.get(abortedRequestId).packet.toPacket(vm).toByteArray());
                                LOG.debug("Resending request {} as its triggered EvaluateProgramRequest was aborted",
                                        formatter.format(unfinished.get(abortedRequestId).packet));
                            }
                            unfinishedEvaluateRequests.remove(abortedRequestId);
                            // this order of statements ensures that the event can invalidate the appropriate cache
                            // entries
                            addEvent(new WrappedPacket<>(parsed.first));
                        }
                        return Either.left(parsed.first);
                    } catch (Exception | AssertionError e) {
                        LOG.error("Error in tunnel request reply event: " + e.getMessage(), e);
                        throw new PacketError("Error in tunnel request reply event", e);
                    }
                }
            }
            addEvent(new WrappedPacket<>(events));
            return Either.left(events);
        }
    }

    @Nullable
    private ReplyOrError<?>
    processReceivedRequestRepliesFromEvent(@Nullable OutputStream clientOutputStream,
                                           Request<?> request, List<Pair<Request<?>, ReplyOrError<?>>> realReply,
                                           int id) {
        ReplyOrError<?> originalReply = null;
        assert clientOutputStream != null;
        for (var p : realReply) {
            captureInformation(p.first, p.second);
            if (originalReply == null && p.first.equals(request)) {
                originalReply = (ReplyOrError<?>) p.second.withNewId(id);
            }
            if (p.first.onlyReads()) {
                cache(p.first, p.second, p.second != originalReply);
                LOG.debug("put into reply cache: {} -> {}", formatter.format(p.first),
                        formatter.format(p.second));
            }
        }
        clientPartitioner.disable();
        serverPartitioner.disable();
        // now go through all unfinished requests and check (but only if is not the original request)
        for (WrappedPacket<Request<?>> value : new HashMap<>(unfinished).values()) {
            // might cause a concurrent modification exception
            var unfinishedRequest = value.packet;
            if (unfinishedRequest.getId() != id) {
                var cachedReply = replyCache.get(unfinishedRequest);
                if (cachedReply != null) {
                    addReply(new WrappedPacket<>(cachedReply, System.currentTimeMillis()));
                    writeReply(clientOutputStream, Either.right(cachedReply));
                }
            }
        }
        // handle the original request
        if (originalReply != null) {
            try {
                addReply(new WrappedPacket<>(originalReply, System.currentTimeMillis()));
            } catch (Exception e) {
                LOG.error("Failed to add reply to reply cache", e);
            }
        }
        clientPartitioner.enable();
        serverPartitioner.enable();
        if (originalReply != null) { // add to partition
            clientPartitioner.onReply(new WrappedPacket<>(request), new WrappedPacket<>(originalReply));
        }
        return originalReply;
    }

    public void captureInformation(Request<?> request, Reply reply) {
        try {
            vm.captureInformation(request, reply);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to capture information from request %s and reply %s",
                    request, reply), e);
        }
    }

    public void captureInformation(Request<?> request, ReplyOrError<?> reply) {
        if (reply.isReply()) {
            captureInformation(request, reply.getReply());
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
        LOG.debug("Write {}", formatter.format((ParsedPacket)reply.get()));
        try {
            ((ParsedPacket)reply.get()).toPacket(vm).write(outputStream);
            if (reply.isLeft() && reply.getLeft().events.get(0) instanceof TunnelRequestReplies) {
                LOG.debug("Wrote TRR {}", reply.getLeft().events.get(0));
            }
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to write reply or events %s in %s", reply.<ParsedPacket>get(), mode), e);
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

    public void addAndWriteError(OutputStream outputStream, ReplyOrError<?> error) {
        addAndWriteReply(outputStream, Either.right(error));
    }

    /**
     * write request without triggering listeners
     */
    public void writeRequest(OutputStream outputStream, Request<?> request) {
        try {
            assert isClient() || !(request instanceof EvaluateProgramRequest); // just a sanity check
            LOG.debug("Write {}", formatter.format(request));
            request.toPacket(vm).write(outputStream);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to write request %s", request), e);
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
        return !isServer() && replyCache.contains(request);
    }

    public ReplyOrError<?> getCachedReply(Request<?> request) {
        return replyCache.get(request);
    }

    public @Nullable Program getCachedProgram(ParsedPacket packet) {
        return programCache.get(packet).or(() -> programCache.getSimilar(packet)).orElse(null);
    }

    public void addUnfinishedEvaluateRequest(EvaluateProgramRequest evaluateRequest) {
        unfinishedEvaluateRequests.put(evaluateRequest.getId(), evaluateRequest);
    }

    public List<Program> drainProgramsToSendToServer() {
        List<Program> programs = new ArrayList<>();
        programsToSendToServer.drainTo(programs);
        return programs;
    }

    public boolean hasProgramsToSendToServer() {
        return programsToSendToServer.size() > 0;
    }

    public void updateProgramCache(List<String> programs) {
        LOG.info("Received programs from client: {}", programs);
        programs.forEach(program -> programCache.accept(Program.parse(program)));
        storeProgramCache();
    }

    public boolean isClient() {
        return mode == CLIENT;
    }

    public boolean isServer() {
        return mode == SERVER;
    }

    public void removeCachedProgram(Program program) {
        programCache.remove(program);
    }

    /**
     * remove all requests from the program that are cached
     */
    public Program reduceProgramToNonCachedRequests(Program program) {
        var notEvaluated = new Evaluator(vm, new Functions() {
            @Override
            protected Optional<Value> processRequest(Request<?> request) {
                var reply = replyCache.get(request);
                if (reply != null) {
                    return Optional.of(reply.asCombined());
                }
                throw new EvaluationAbortException(false);
            }
        }).evaluate(program).second;
        var toRemove = program.collectBodyStatements();
        toRemove.removeAll(notEvaluated);
        try {
            return program.removeStatements(new HashSet<>(toRemove));
        } catch (AssertionError e) {
            LOG.error("Failed to remove cached requests ({}) from program {}", program, toRemove, e);
        }
        return program;
    }
}
