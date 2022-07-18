package tunnel;

import ch.qos.logback.classic.Logger;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.EventCommon;
import jdwp.EventCmds.Events.TunnelRequestReplies;
import jdwp.*;
import jdwp.TunnelCmds.EvaluateProgramReply;
import jdwp.Value;
import jdwp.TunnelCmds.EvaluateProgramRequest;
import jdwp.exception.PacketError;
import jdwp.exception.TunnelException;
import jdwp.exception.TunnelException.SynthesisException;
import jdwp.util.Pair;
import lombok.*;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tunnel.ProgramCache.DisabledProgramCache;
import tunnel.ReplyCache.DisabledReplyCache;
import tunnel.synth.DependencyGraph;
import tunnel.synth.Partitioner;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.Synthesizer;
import tunnel.synth.program.AST.AssignmentStatement;
import tunnel.synth.program.AST.Identifier;
import tunnel.synth.program.AST.RequestCall;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.Evaluator;
import tunnel.synth.program.Evaluator.EvaluationAbortException;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Program;
import tunnel.util.Box;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
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

    private final Map<Integer, Pair<EvaluateProgramRequest, ReducedProgram>> unfinishedEvaluateRequests;
    private final LinkedBlockingQueue<Program> programsToSendToServer = new LinkedBlockingQueue<>();
    private final Set<Listener> listeners;
    private Partitioner clientPartitioner;
    private Partitioner serverPartitioner;

    private ReplyCache replyCache;
    private ProgramCache programCache;
    private Formatter formatter;
    private @Nullable Path programCacheFile;
    @Setter
    private int minPartitionSizeForPartitioning = 3;
    private int minSizeOfStoredProgram = 3;

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
            programCache.store(out, minSizeOfStoredProgram);
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
            handlePartition(partition, true);
        });
        addPartitionerListener(clientPartitioner);
        serverPartitioner = new Partitioner(replyCache.getOptions().conservative).addListener(partition -> {
            handlePartition(partition, false);
        });
        addPartitionerListener(serverPartitioner);
    }

    private void handlePartition(Partition partition, boolean isClient) {
        if (partition.hasCause()) {
            var cause = partition.getCause();
            if (cause == null) {
                LOG.info("Null cause partition {}", formatter.format(partition));
                return;
            }
            if (cause.isLeft() == isClient) {
                LOG.debug("Partition: {}", formatter.format(partition));
                if (partition.size() < minPartitionSizeForPartitioning) {
                    LOG.debug("Ignoring partition as it is too small");
                    return;
                }
                try {
                    boolean foundAcceptable = false;
                    for (DependencyGraph part : DependencyGraph.compute(partition).splitOnCause()) {
                        var program = Synthesizer.synthesizeProgram(part);
                        if (programCache.isAcceptableProgram(program)) {
                            LOG.info("Synthesized client program: {}", program.toPrettyString());
                            programCache.accept(program);
                            if (!isClient) {
                                programsToSendToServer.add(program);
                            }
                            foundAcceptable = true;
                        }
                    }
                    if (foundAcceptable) {
                        storeProgramCache();
                    }
                } catch (SynthesisException err) {
                    err.log(LOG);
                } catch (Exception ex) {
                    LOG.error("Error synthesizing program for " + formatter.format(partition), ex);
                }
            }
        } else {
            LOG.debug("omit because of missing cause");
        }
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
        addReply(reply, false);
    }

    public void addReply(WrappedPacket<ReplyOrError<?>> reply, boolean withOutReplyCache) {
        var id = reply.packet.getId();
        var request = getUnfinishedRequest(id);
        listeners.forEach(l -> {
            if (!(withOutReplyCache && l instanceof ReplyCache)) {
                l.onReply(request, reply);
            }
        });
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
        currentRequestId = ps.id();
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

    @Getter
    @EqualsAndHashCode
    @ToString
    public static class ReadReplyResult {
        Events events;
        ReplyOrError<?> reply;
        boolean ignored;

        private ReadReplyResult(Events events, ReplyOrError<?> reply, boolean ignored) {
            this.events = events;
            this.reply = reply;
            this.ignored = ignored;
        }

        public boolean hasReply() {
            return reply != null;
        }

        public boolean hasEvents() {
            return events != null;
        }

        public Optional<Either<Events, ReplyOrError<?>>> getEither() {
            return ignored ? Optional.empty() : Optional.of(events != null ? Either.left(events) : Either.right(reply));
        }
    }

    public @Nullable ReadReplyResult readReply(InputStream inputStream,
                                               @Nullable OutputStream clientOutputStream,
                                               OutputStream serverOutputStream) throws IOException {
        var ps = PacketInputStream.read(vm, inputStream);
        if (ps.isReply()) {
            if (ignoredUnfinished.contains(ps.id())) {
                LOG.debug("Ignoring reply {}", ps.id());
                ignoredUnfinished.remove(ps.id());
                return new ReadReplyResult(null, null, true);
            }
            if (unfinishedEvaluateRequests.containsKey(ps.id())) {
                var reply = PacketError.call(() -> EvaluateProgramReply.parse(ps), ps);
                LOG.debug(formatter.format(reply));
                var reduced = unfinishedEvaluateRequests.get(ps.id());
                var program = reduced.first.program;
                LOG.debug("original program " + unfinishedEvaluateRequests.get(ps.id()).first.program);
                var evalRequest = unfinishedEvaluateRequests.get(ps.id());
                unfinishedEvaluateRequests.remove(ps.id());
                if (reply.isError()) {
                    LOG.error("Error in evaluate program reply (resending original request): " + reply);
                    removeCachedProgram(Program.parse(program.getValue()));
                    var originalRequest = getUnfinishedRequest(ps.id());
                    unfinished.remove(ps.id());
                    addRequest(originalRequest);
                    writeRequest(serverOutputStream, originalRequest.packet);
                    return null;
                } else {
                    var request = hasUnfinishedRequest(ps.id()) ? getUnfinishedRequest(ps.id()).getPacket() : null;
                    var realReply = reply.getReply();
                    var rrs = BasicTunnel.parseEvaluateProgramReply(vm, realReply);
                    boolean shouldReturnNull = handleCachedInitiatingRequest(serverOutputStream, clientOutputStream,
                            ps.id(),
                            evalRequest.second.getCached(), rrs, null);
                    if (shouldReturnNull) {
                        return null;
                    }
                    ReplyOrError<?> originalReply = processReceivedRequestRepliesFromEvent(clientOutputStream, request,
                            rrs, ps.id());
                    LOG.debug("original reply " + originalReply);
                    return originalReply == null ? null : new ReadReplyResult(null, originalReply, false);
                }
            }
            if (!unfinished.containsKey(ps.id())) {
                LOG.debug("Ignore reply {}, it is possibly already answered using a cached reply", ps.id());
                if (ignoredUnfinished.contains(ps.id())) {
                    ignoredUnfinished.remove(ps.id());
                    return new ReadReplyResult(null, null, true);
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
            return new ReadReplyResult(null, reply, false);
        } else {
            Events events;
            try {
                events = Events.parse(ps);
            } catch (Exception err) {
                LOG.error("Failed to parse events", err);
                return null;
            }
            LOG.debug("Read event " + formatter.format(events));
            captureInformation(events);
            for (EventCommon event : events.events) {
                if (event instanceof TunnelRequestReplies) {
                    try {
                        assert events.events.size() == 1; // is the only event in the list
                        var parsed = BasicTunnel.parseTunnelRequestReplyEvent(vm, (TunnelRequestReplies) event);
                        var abortedRequestId = ((TunnelRequestReplies) event).abortedRequest.value;
                        if (abortedRequestId == -1) {
                            for (Pair<Request<?>, ReplyOrError<?>> p : parsed.getLeft()) {
                                replyCache.put(p.first, p.second, true);
                            }
                            addEvent(new WrappedPacket<>(parsed.getMiddle()));
                            for (Pair<Request<?>, ReplyOrError<?>> p : parsed.getRight()) {
                                replyCache.put(p.first, p.second, true);
                            }
                            for (WrappedPacket<Request<?>> unfinishedReq : unfinished.values()) {
                                var unf = unfinishedReq.getPacket();
                                if (replyCache.contains(unf)) {
                                    var cachedReply = replyCache.get(unf);
                                    if (cachedReply != null) {
                                        addReply(new WrappedPacket<>(cachedReply, System.currentTimeMillis()));
                                        writeReply(clientOutputStream, Either.right(cachedReply));
                                        ignoredUnfinished.add(unf.getId());
                                    }
                                }
                            }
                        } else {
                            // the event caused the abortion of an EvaluateProgramRequest
                            // but we collected some requests before
                            // and possibly after, as the event might have an associated program
                            var request = unfinished.entrySet().stream().filter(e -> unfinishedEvaluateRequests.containsKey(e.getKey()))
                                    .map(e -> e.getValue().getPacket()).findFirst();
                            var cached =
                                    request.map(r -> unfinishedEvaluateRequests.get(r.getId()).second.getCached())
                                            .orElse(Map.of());
                            if (!parsed.getLeft().isEmpty()) {
                                // we did collect some replies before receiving the event
                                request.ifPresent(value -> processReceivedRequestRepliesFromEvent(clientOutputStream,
                                        value,
                                        parsed.getLeft(), abortedRequestId));
                            } else if (unfinished.containsKey(abortedRequestId)) {
                                // we have to resend the original request, as it apparently is still unfinished
                                serverOutputStream.write(unfinished.get(abortedRequestId).packet.toPacket(vm).toByteArray());
                                LOG.debug("Resending request {} as its triggered EvaluateProgramRequest was aborted",
                                        formatter.format(unfinished.get(abortedRequestId).packet));
                            }
                            unfinishedEvaluateRequests.remove(abortedRequestId);
                            // this order of statements ensures that the event can invalidate the appropriate cache
                            // entries
                            addEvent(new WrappedPacket<>(parsed.getMiddle()));
                            handleCachedInitiatingRequest(serverOutputStream, clientOutputStream,
                                    abortedRequestId, cached, parsed.getLeft(), parsed.getMiddle());
                            if (parsed.getRight().size() > 0) {
                                processReceivedRequestRepliesFromEvent(clientOutputStream, null, parsed.getRight(), abortedRequestId);
                            }
                        }
                        return new ReadReplyResult(parsed.getMiddle(), null, false);
                    } catch (Exception | AssertionError e) {
                        LOG.error("Error in tunnel request reply event: " + e.getMessage(), e);
                        throw new PacketError("Error in tunnel request reply event", e);
                    }
                }
            }
            addEvent(new WrappedPacket<>(events));
            return new ReadReplyResult(events, null, false);
        }
    }

    /**
     * check whether there is an unfinished request with the passed id, which is also present in cached,
     * if so: check if it is invalidated by the passed event, if so write a new request
     */
    private boolean handleCachedInitiatingRequest(OutputStream jvmOutputStream,
                                                  OutputStream clientOutputStream,
                                                  int originalRequestId, Map<Request<?>, ReplyOrError<?>> cached,
                                                  List<Pair<Request<?>, ReplyOrError<?>>> rrs,
                                                  @Nullable Events possibleInvalidatingEvents) {
        var unfinishedRequest = unfinished.get(originalRequestId);
        if (unfinishedRequest != null && !unfinishedRequest.packet.onlyReads() &&
                !cached.containsKey(unfinishedRequest.packet) &&
                rrs.stream().noneMatch(p -> p.first.equals(unfinishedRequest.packet))) {
            return true;
        }
        if (unfinishedRequest == null || !unfinishedRequest.packet.onlyReads()) {
            return false;
        }
        var cachedReplyOrNull = cached.get(unfinishedRequest.getPacket());
        if (cachedReplyOrNull == null) {
            return false;
        }
        var cachedReply = cachedReplyOrNull.withNewId(originalRequestId);
        if (possibleInvalidatingEvents != null && cachedReply.isAffectedBy(possibleInvalidatingEvents)) {
            // the important case where the cached cause is invalidated by an event
            // leading to the request never being answered
            LOG.debug("Resending cached request {} as it has been invalidated by {}",
                    formatter.format(unfinishedRequest.getPacket()), formatter.format(possibleInvalidatingEvents));
            unfinished.remove(originalRequestId);
            addRequest(new WrappedPacket<>(unfinishedRequest.getPacket()));
            writeRequest(jvmOutputStream, unfinishedRequest.getPacket());
            return false;
        }
        LOG.debug("Cached request {} for reduced program, sending it now",
                formatter.format(unfinishedRequest.getPacket()));
        addReply(new WrappedPacket<>(cachedReply));
        writeReply(clientOutputStream, Either.right(cachedReply));
        // all other requests are not important, as their replies are still in the cache
        // so probably the last case should never happen
        return false;
    }

    /**
     * @param id (aborted) request id, or -1 in case of an event
     */
    private @Nullable ReplyOrError<?>
    processReceivedRequestRepliesFromEvent(@Nullable OutputStream clientOutputStream,
                                           @Nullable Request<?> request, List<Pair<Request<?>, ReplyOrError<?>>> realReply,
                                           int id) {
        ReplyOrError<?> originalReply = null;
        for (var p : realReply) {
            captureInformation(p.first, p.second);
            if (originalReply == null && p.first.equals(request)) {
                originalReply = p.second.withNewId(id);
                try {
                    addReply(new WrappedPacket<>(originalReply, System.currentTimeMillis()));
                    clientPartitioner.onReply(new WrappedPacket<>(request), new WrappedPacket<>(originalReply));
                } catch (Exception e) {
                    LOG.error("Failed to add reply to reply cache", e);
                }
            } else if (p.first.onlyReads()) {
                cache(p.first, p.second, p.second != originalReply);
                LOG.debug("put into reply cache: {} -> {}", formatter.format(p.first),
                        formatter.format(p.second));
                if (unfinished.containsKey(p.first.getId())) {
                    LOG.debug("Removing request {} from unfinished requests",
                            formatter.format(unfinished.get(p.first.getId()).packet));
                    var cachedReply = replyCache.get(p.first);
                    if (cachedReply != null) {
                        addReply(new WrappedPacket<>(cachedReply, System.currentTimeMillis()));
                        writeReply(clientOutputStream, Either.right(cachedReply));
                        ignoredUnfinished.add(p.first.getId());
                    }
                }
            }
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
            if (replyCache.contains(request)) {
                LOG.error("Should be cached");
            }
            request.toPacket(vm).write(outputStream);
        } catch (Exception | AssertionError e) {
            throw new PacketError(String.format("Failed to write request %s", request), e);
        }
    }

    public VM vm() {
        return vm;
    }

    public void tick() {
        programCache.tick();
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
        return programCache.get(packet).orElse(null);
    }

    public void addUnfinishedEvaluateRequest(EvaluateProgramRequest evaluateRequest, ReducedProgram reducedProgram) {
        unfinishedEvaluateRequests.put(evaluateRequest.getId(), p(evaluateRequest, reducedProgram));
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

    @lombok.Value
    public static class ReducedProgram {
        Program program;
        /** replies that are considered cached */
        Map<Request<?>, ReplyOrError<?>> cached;

        public boolean hasCached() {
            return !cached.isEmpty();
        }
    }

    /**
     * remove all requests from the program that are cached, returns the same program if nothing can be cached
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ReducedProgram reduceProgramToNonCachedRequests(Program program) {
        Map<RequestCall, Value> cachedRequests = new HashMap<>();
        Map<RequestCall, Pair<Request<?>, ReplyOrError<?>>> cachedRequests2 = new HashMap<>();
        Box<Value> causeCache = new Box<>(null);
        Set<Statement> notEvaluated;
        var causeAffects = program.hasCause() ? program.getCauseMetadata().getAffectsBits() : 0;
        try {
            notEvaluated = new Evaluator(vm, new Functions() {
                @Override
                public Optional<Value> processRequest(RequestCall call, Request<?> request) {
                    var reply = replyCache.get(request);
                    if (reply != null && (call.equals(program.getCause()) ||
                            (!request.isAffectedByTime() && (request.getAffectedByBits() & causeAffects) == 0))) {
                        var cached = reply.isError() ? wrap(1) : reply.getReply().asCombined();
                        cachedRequests.put(call, cached);
                        cachedRequests2.put(call, p(request, reply));
                        if (call.equals(program.getCause())) {
                            causeCache.set(cached);
                        }
                        return Optional.of(reply.asCombined());
                    }
                    throw new EvaluationAbortException(false);
                }
            }).evaluate(program).second;
        } catch (EvaluationAbortException e) {
            LOG.debug("Cannot evaluate program {} properly", program.toPrettyString(), e);
            return new ReducedProgram(program, Map.of());
        }
        var toRemove = program.collectBodyStatements();
        toRemove.removeAll(notEvaluated);
        if (toRemove.isEmpty()) {
            return new ReducedProgram(program, Map.of());
        }
        try {
            Map<Request<?>, ReplyOrError<?>> cached = new HashMap<>();
            // check if there is any non-removed statement that depends on a removed statement
            // collect all defined identifiers with their assignment statements
            Map<Identifier, AssignmentStatement> removedVars =
                    toRemove.stream().filter(s -> s instanceof AssignmentStatement)
                            .flatMap(s -> s.getDefinedIdentifiers().stream()
                                    .map(i -> Map.entry(i, (AssignmentStatement) s)))
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> {
                                throw new TunnelException(Level.INFO, true, "Duplicate identifier");
                            }, IdentityHashMap::new));
            var usedIdentifiers = notEvaluated.stream().flatMap(s -> s.getUsedIdentifiers().stream())
                    .collect(Collectors.toSet());
            List<AssignmentStatement> required = Stream.concat(
                            (causeCache.get() != null ?
                                    Stream.of(p(((AssignmentStatement) program.getBody().get(0)).getVariable(),
                                            causeCache.get())) : Stream.empty()),
                            removedVars.entrySet().stream()
                                    .filter(e -> usedIdentifiers.contains(e.getKey()))
                                    .sorted(Comparator.comparing(e -> e.getValue().getHashes().get(e.getValue()).hash()))
                                    .map(e -> p(e.getKey(),
                                            cachedRequests.get((RequestCall) e.getValue().getExpression())))
                                    .filter(p -> p.second != null))
                    .distinct()
                    .peek(p -> {
                        var rr = cachedRequests2.get((RequestCall)removedVars.get(p.first).getExpression());
                        if (rr == null) {
                            throw new TunnelException(Level.INFO, true, "Cannot find request for removed statement");
                        }
                        cached.put(rr.first, rr.second);
                    })
                    .map(e -> new AssignmentStatement(e.first, Functions.createWrappingFunctionCall(e.second)))
                    .collect(Collectors.toList());
            Program reduced = program.removeStatementsIgnoreCause(
                    new HashSet<>(toRemove), (List<Statement>) (List) required, false);
            LOG.debug("Reduced program {} to non-cached requests {} by removing {}",
                    program.toPrettyString(), reduced.toPrettyString(), toRemove);
            return new ReducedProgram(reduced, cached);
        } catch (Exception | AssertionError e) {
            e.printStackTrace();
            LOG.error("Failed to remove cached requests ({}) from program {}", program, toRemove, e);
        }
        return new ReducedProgram(program, Map.of());
    }

    public void onDispose() {
        tick();
        replyCache.close();
        if (LOG.isInfoEnabled()) {
            LOG.info("\n" + getReplyCache().getStatistics().toLongTable());
        }
        if (mode == CLIENT) {
            clientPartitioner.close();
            serverPartitioner.close();
            storeProgramCache();
        }
    }
}
