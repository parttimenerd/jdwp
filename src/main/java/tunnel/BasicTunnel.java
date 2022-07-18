package tunnel;

import ch.qos.logback.classic.Logger;
import jdwp.*;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.TunnelRequestReplies;
import jdwp.JDWP.Error;
import jdwp.PrimitiveValue.StringValue;
import jdwp.TunnelCmds.EvaluateProgramReply;
import jdwp.TunnelCmds.EvaluateProgramReply.RequestReply;
import jdwp.TunnelCmds.EvaluateProgramRequest;
import jdwp.TunnelCmds.UpdateCacheReply;
import jdwp.TunnelCmds.UpdateCacheRequest;
import jdwp.Value.ByteList;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.DisposeReply;
import jdwp.exception.PacketError;
import jdwp.exception.TunnelException;
import jdwp.util.Pair;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.apache.commons.lang3.tuple.Triple;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import tunnel.State.*;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.Evaluator;
import tunnel.synth.program.Evaluator.EvaluationAbortException;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Program;
import tunnel.util.Either;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static jdwp.JDWP.Error.CANNOT_EVALUATE_PROGRAM;
import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static tunnel.State.Mode.NONE;
import static tunnel.util.ToStringMode.CODE;

/**
 * Basic tunnel that works without threads.
 * Be aware that blocking in a state listener blocks everything
 */
@Getter
public class BasicTunnel {

    public final Logger LOG;

    private final State state;
    private final InetSocketAddress ownAddress;
    private final InetSocketAddress jvmAddress;
    @Setter
    private Formatter formatter = new Formatter(CODE, CODE);
    @Setter
    private int logCacheInterval = Integer.MAX_VALUE;
    private int requestsSinceLastCacheLog = 0;
    private int currentId = 0;
    private final Duration waitTillNextReply = Duration.ofSeconds(1);
    /**
     * min size of a reduced program to be preferred over just plain caching
     */
    @Setter
    private int minSizeOfPreferredCachedProgram = 2;
    @Setter
    private boolean preferCachedProgram = false;
    /**
     * maximum number of handleEvaluateProgramRequest on call stack
     */
    @Setter
    private int maxEvaluationNesting = 1;

    public BasicTunnel(State state, InetSocketAddress ownAddress, InetSocketAddress jvmAddress) {
        this.state = state;
        this.ownAddress = ownAddress;
        this.jvmAddress = jvmAddress;
        LOG = (Logger) LoggerFactory.getLogger(
                (state.getMode() == NONE ? "" : state.getMode().name().toLowerCase() + "-") + "tunnel");
    }

    public BasicTunnel(InetSocketAddress ownAddress, InetSocketAddress jvmAddress, Mode mode) {
        this(new State(mode), ownAddress, jvmAddress);
    }

    public BasicTunnel(InetSocketAddress ownAddress, InetSocketAddress jvmAddress) {
        this(ownAddress, jvmAddress, NONE);
    }

    public BasicTunnel addListener(Listener listener) {
        this.state.addListener(listener);
        return this;
    }

    public void run() {
        try (var ownServer = new ServerSocket(ownAddress.getPort())) {
            LOG.info("Try to accept");
            Socket clientSocket = ownServer.accept();
            LOG.info("Accepted");
            var clientInputStream = clientSocket.getInputStream();
            var clientOutputStream = clientSocket.getOutputStream();
            LOG.info("try to connect to JVM");
            try (var jvmSocket = new Socket((String) null, jvmAddress.getPort())) {
                LOG.info("connected jvm");
                var jvmInputStream = jvmSocket.getInputStream();
                var jvmOutputStream = jvmSocket.getOutputStream();
                var io = new IO(clientInputStream, clientOutputStream, jvmInputStream, jvmOutputStream);
                handshake(io);
                readWriteLoop(io);
            }
        } catch (IOException ex) {
            LOG.error("Problems with TCP streams", ex);
            System.exit(1);
        }
    }

    /**
     * JDWP handshake, see spec
     */
    private void handshake(IO io) throws IOException {
        LOG.info("Attempt JDWP-Handshake");
        String handshake = "JDWP-Handshake";
        byte[] hsBytes = io.clientInputStream.readNBytes(handshake.length());
        String hsStr = new String(hsBytes);
        if (!hsStr.equals(handshake)) {
            throw new TunnelException(Level.ERROR, false, String.format("Expected \"JDWP-Handshake\" from client, but" +
                    " got \"%s\"", hsStr));
        }
        io.jvmOutputStream.write(hsBytes);
        byte[] hsBytes2 = io.jvmInputStream.readNBytes(handshake.length());
        String hsStr2 = new String(hsBytes2);
        if (!hsStr2.equals(handshake)) {
            throw new TunnelException(Level.ERROR, false, String.format("Expected \"JDWP-Handshake\" from jvm, but" +
                    " got \"%s\"", hsStr));
        }
        io.clientOutputStream.write(hsBytes);
        LOG.info("JDWP-Handshake was successful");
    }

    /**
     * Loops over the client and jvm input streams, processing and propagating the incoming and outgoing packets.
     */
    private void readWriteLoop(IO io) throws IOException {
        while (true) {
            Optional<Request<?>> clientRequest = Optional.empty();
            try {
                clientRequest = readClientRequest(io.clientInputStream);
                if (clientRequest.isPresent()) {
                    var request = clientRequest.get();
                    currentId = request.getId();
                    ReducedProgram reducedProgram = null;
                    if (request instanceof EvaluateProgramRequest) { // handle evaluation requests
                        handleEvaluateProgramRequest(io, (EvaluateProgramRequest) request);
                    } else if (request instanceof UpdateCacheRequest) { // handle program cache update requests
                        state.updateProgramCache(((UpdateCacheRequest) request).programs
                                .asList().stream().map(StringValue::getValue).collect(Collectors.toList()));
                        state.getUnfinished().remove(request.getId());
                    } else if (preferCachedProgram && (reducedProgram = getReducedCachedProgram(request)) != null &&
                            reducedProgram.getProgram().getBodySize() >= minSizeOfPreferredCachedProgram) {
                        handleReducedProgramForRequest(io, request, reducedProgram);
                    } else if (state.hasCachedReply(request)) {
                        handleRequestWithCachedReply(io, request);
                    } else if ((reducedProgram = reducedProgram == null ? getReducedCachedProgram(request) :
                            reducedProgram) != null) {
                        handleReducedProgramForRequest(io, request, reducedProgram);
                    } else {
                        state.writeRequest(io.jvmOutputStream, request);
                    }
                    if (requestsSinceLastCacheLog++ % logCacheInterval == 0) {
                        System.out.println(state.getReplyCache().getStatistics().toLongTable());
                    }
                }
            } catch (ClosedStreamException e) {
              return;
            } catch (PacketError e) {
                if (e.hasContent()) {
                    try {
                        var packet = Packet.fromByteArray(e.getContent());
                        e.log(LOG);
                        if (packet.getCmd() == TunnelCmds.COMMAND_SET) {
                            state.addAndWriteError(io.clientOutputStream, new ReplyOrError<>(
                                    clientRequest.get().getId(), EvaluateProgramRequest.METADATA,
                                    (short) CANNOT_EVALUATE_PROGRAM));
                            continue; // tunnel commands cannot be sent directly to the VM
                        }
                    } catch (PacketError ex) {
                        ex.log(LOG);
                    } catch (Exception ex) {
                        LOG.error("Unknown error", ex);
                    }
                    io.jvmOutputStream.write(e.getContent());
                } else {
                    LOG.error("packet error during request handling", e);
                }
            } catch (Exception ex) {
                if (clientRequest.isPresent()) {
                    if (clientRequest.get() instanceof EvaluateProgramRequest) {
                        LOG.error("packet error: ", ex);
                        state.addAndWriteError(io.clientOutputStream, new ReplyOrError<>(
                                clientRequest.get().getId(), EvaluateProgramRequest.METADATA,
                                (short) CANNOT_EVALUATE_PROGRAM));
                        continue;
                    }
                }
                LOG.error("error during request handling", ex);
            }
            Optional<Either<Events, ReplyOrError<?>>> reply = Optional.empty();
            try {
                reply = readJvmReply(io, false);
                if (reply.isPresent() && reply.get().isLeft()) {
                    currentId = reply.get().getLeft().getId();
                    writeEvents(io, reply.get().getLeft(), 1);
                    reply = Optional.empty();
                } else if (reply.isPresent() && reply.get().isRight() &&
                        reply.get().getRight().isReply() &&
                        reply.get().getRight().getReply() instanceof UpdateCacheReply) {
                    continue; // skip the reply to the update cache request
                }
            } catch (ClosedStreamException e) {
                return;
            } catch (PacketError e) {
                e.log(LOG);
                if (e.hasContent()) {
                    io.clientOutputStream.write(e.getContent());
                    reply = Optional.empty();
                }
            }
            if (reply.isPresent()) {
                if (reply.get().isRight() && reply.get().getRight().isReply() &&
                        reply.get().getRight().getReply() instanceof DisposeReply) {
                    // dispose / terminate the loop
                    state.onDispose();
                    writeClientReply(io.clientOutputStream, reply.get());
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        throw new TunnelException(Level.ERROR, false, "Got interrupted");
                    }
                    return;
                }
                writeClientReply(io.clientOutputStream, reply.get());
            }
            int yieldCount = 0;
            while (!io.hasAnyDataAvailable()) {
                state.tick();
                if (state.isClient() && state.hasProgramsToSendToServer()) {
                    sendProgramsToServer(io.jvmInputStream, io.jvmOutputStream);
                    yieldCount = 0;
                } else {
                    assert state.getProgramsToSendToServer().isEmpty();
                }
                this.yield(yieldCount++); // hint to the scheduler that other work could be done
            }
        }
    }

    private void handleRequestWithCachedReply(IO io, Request<?> request) {
        var reply = state.getCachedReply(request);
        assert reply != null;
        LOG.info("Using cached reply for  {}: {}", formatter.format(request), formatter.format(reply));
        state.addReply(new WrappedPacket<>(reply), false);
        writeClientReply(io.clientOutputStream, Either.right(reply));
    }

    private void handleReducedProgramForRequest(IO io, Request<?> request, ReducedProgram reducedProgram) {
        LOG.info("Using cached program for request  {}:\n {}", formatter.format(request),
                reducedProgram.getProgram().toPrettyString());
        var evaluateRequest = new EvaluateProgramRequest(reducedProgram.getProgram().hasCause() ? request.getId()
                : getStartIdForIntermediateRequest(),
                wrap(reducedProgram.getProgram().toPrettyString()));
        state.addUnfinishedEvaluateRequest(evaluateRequest, reducedProgram);
        state.writeRequest(io.jvmOutputStream, evaluateRequest);
    }

    @Nullable State.ReducedProgram getReducedCachedProgram(Request<?> request) {
        var program = state.getCachedProgram(request);
        if (program != null) {
            var reducedProgram = state.reduceProgramToNonCachedRequests(program);
            if (reducedProgram.getProgram().getBodySize() > 0) {
                return reducedProgram;
            }
        }
        return null;
    }

    /**
     * yields the thread after each check, returns true if data is really available or false it just ran into a timeout,
     * checks JVM and client input streams for data availability.
     */
    public boolean waitTillDataAvailable(List<InputStream> inputStreams, Duration maxDuration) {
        var start = System.currentTimeMillis();
        int yieldCount = 0;
        while (!hasDataAvailable(inputStreams) && System.currentTimeMillis() - start < maxDuration.toMillis()) {
            this.yield(yieldCount++);
        }
        return hasDataAvailable(inputStreams);
    }

    /**
     *
     * @param yieldCount number of yields without any change
     */
    private void yield(int yieldCount) {
        if (yieldCount % 10000 == 9999 && state.getUnfinished().size() > 0) {
            LOG.warn("Unfinished {}",
                    state.getUnfinished().values().stream().map(requestWrappedPacket -> requestWrappedPacket.packet.toShortString()).collect(Collectors.joining(", ")));
        }
        if (yieldCount > 1000) {
            Thread.yield(); // free resources
        }
    }

    @SneakyThrows
    private void sendProgramsToServer(InputStream jvmInputStream, OutputStream jvmOutputStream) {
        jvmOutputStream.write(new UpdateCacheRequest(getStartIdForIntermediateRequest(),
                new ListValue<>(Type.STRING,
                        state.drainProgramsToSendToServer().stream().map(Statement::toPrettyString)
                                .map(PrimitiveValue::wrap).collect(Collectors.toList())))
                .toPacket(state.vm()).toByteArray());
    }

    private Optional<Request<?>> readClientRequest(InputStream clientInputStream) throws IOException {
        if (hasDataAvailable(clientInputStream)) {
            return Optional.of(state.readRequest(clientInputStream));
        }
        return Optional.empty();
    }

    private Optional<ReadReplyResult> readJvmReplyDoNotIgnoreIgnored(IO io) throws IOException {
        if (hasDataAvailable(io.jvmInputStream)) {
            var reply =
                    state.readReply(io.jvmInputStream, io.clientOutputStream, io.jvmOutputStream);
            return Optional.ofNullable(reply);
        }
        return Optional.empty();
    }

    /**
     * ignores ignored statements and reads again if encountered and loop == true
     *
     * @param loop wait for {@link BasicTunnel#waitTillNextReply} for data to be available and then ignore
     *             ignored replies till a real reply is encountered, but also stop if there is input
     *             on the client side
     */
    private Optional<Either<Events, ReplyOrError<?>>> readJvmReply(IO io, boolean loop) throws IOException {
        if (!loop) {
            return readJvmReplyDoNotIgnoreIgnored(io).flatMap(ReadReplyResult::getEither);
        }
        Optional<ReadReplyResult> reply;
        do {
            // first time in a while that I used a do-while loop
            waitTillDataAvailable(List.of(io.jvmInputStream), waitTillNextReply);
            if (io.hasClientDataAvailable() && !io.hasJVMDataAvailable()) {
                return Optional.empty(); // process the client request first and don't stall here
            }
        } while ((reply = readJvmReplyDoNotIgnoreIgnored(io))
                .map(r -> r.ignored).orElse(false));
        return reply.flatMap(ReadReplyResult::getEither);
    }

    private void writeClientReply(OutputStream clientOutputStream, Either<Events, ReplyOrError<?>> reply) {
        state.writeReply(clientOutputStream, reply);
    }

    private void writeJvmRequest(OutputStream jvmOutputStream, Request<?> request) {
        state.addRequest(new WrappedPacket<>(request));
        state.writeRequest(jvmOutputStream, request);
    }

    private void handleEvaluateProgramRequest(IO io, EvaluateProgramRequest request) {
        var program = Program.parse(request.program.value);
        List<Pair<Request<?>, ReplyOrError<?>>> requestReplies = new ArrayList<>();
        try {
            requestReplies = handleEvaluateProgramRequest(io, request.id, program, 1);
        } catch (EvaluationAbortException e) {
            e.log(LOG, "Evaluation aborted");
        }
        if (requestReplies.isEmpty()) {
            if (getState().hasUnfinishedRequests()) {
                LOG.warn("Unfinished requests: {}",
                        getState().getUnfinished().values().stream().map(requestWrappedPacket -> requestWrappedPacket.packet.toShortString()).collect(Collectors.joining(", ")));
            }
            //return;
        }
        // if it all worked out, then request replies contains all request replies
        var reply = new ReplyOrError<>(request.getId(),
                new EvaluateProgramReply(request.getId(), new ListValue<>(Type.OBJECT,
                        requestReplies.stream().map(rr -> new RequestReply(
                                new ByteList(rr.first.withNewId(0).toPacket(state.vm()).toByteArray()),
                                new ByteList(rr.second.withNewId(0).toPacket(state.vm()).toByteArray()))).collect(Collectors.toList()))));
        if (!state.hasUnfinishedRequest(request.getId())) {
            return;
        }
        state.addReply(new WrappedPacket<>(reply));
        state.getUnfinished().remove(request.id);
        state.getUnfinishedEvaluateRequests().remove(request.id);
        state.writeReply(io.clientOutputStream, Either.right(reply));
        if (getState().hasUnfinishedRequests()) {
            LOG.warn("Unfinished requests: {}",
                    getState().getUnfinished().values().stream().map(requestWrappedPacket -> requestWrappedPacket.packet.toShortString()).collect(Collectors.joining(", ")));
        }
    }

    private void writeEvents(IO io, Events events, int depth) {
        writeEvents(io, events, List.of(), -1, true, depth);
    }

    /**
     * check for a stored program for the passed event and execute it, send the event either with the
     * additional request-reply-pairs or on its own if there aren't any
     */
    private void writeEvents(IO io,
                             Events events, List<Pair<Request<?>, ReplyOrError<?>>> requestRepliesBefore,
                             int abortedRequest, boolean writeEvents, int depth) {
        try {
            var program = depth > maxEvaluationNesting ? null : state.getCachedProgram(events);
            if (state.isServer() && program != null) {
                LOG.info("Using cached program for events  {}:\n {}", formatter.format(events),
                        program.toPrettyString());
                handleEvaluateProgramEvent(io, events, program,
                        requestRepliesBefore, abortedRequest, writeEvents, depth);
                LOG.debug("Finished handling program");
            } else if (state.isServer() && abortedRequest != -1 || requestRepliesBefore.size() > 0) {
                writeClientReply(io.clientOutputStream,
                        Either.left(createTRREvents(events, requestRepliesBefore, List.of(), abortedRequest)));
            } else {
                writeClientReply(io.clientOutputStream, Either.left(events));
            }
        } catch (Exception e) {
            if (e instanceof PacketError) {
                ((PacketError) e).log(LOG, "error handling events");
            } else {
                LOG.error("error handling events", e);
            }
            state.ignoreUnfinished();
            writeClientReply(io.clientOutputStream, Either.left(events));
        }
    }

    private void handleEvaluateProgramEvent(IO io, Events events, Program program,
                                            List<Pair<Request<?>, ReplyOrError<?>>> requestRepliesBefore,
                                            int abortedRequest,
                                            boolean writeEvents, int depth) {
        List<Pair<Request<?>, ReplyOrError<?>>> requestReplies;
        try {
            requestReplies = handleEvaluateProgramRequest(io, events.id, program, depth);
        } catch (EvaluationAbortException e) {
            state.removeCachedProgram(program);
            e.log(LOG, String.format("Evaluated %s but got error", program.toPrettyString()));
            return;
        } catch (Exception e) {
            LOG.error("error handling events", e);
            return;
        }
        if (requestReplies.isEmpty()) {
            if (writeEvents) {
                state.addAndWriteReply(io.clientOutputStream, Either.left(events));
            } else {
                state.addAndWriteReply(io.clientOutputStream,
                        Either.right(new ReplyOrError<>(events.getId(), EvaluateProgramRequest.METADATA,
                                (short) CANNOT_EVALUATE_PROGRAM)));
            }
            return;
        }
        // if it all worked out, then request replies contains all request replies
        var newEvents = createTRREvents(events, requestRepliesBefore, requestReplies, abortedRequest);
        state.addEvent(new WrappedPacket<>(events));
        state.writeReply(io.clientOutputStream, Either.left(newEvents));
    }

    private Events createTRREvents(Events events, List<Pair<Request<?>, ReplyOrError<?>>> requestRepliesBefore,
                                   List<Pair<Request<?>, ReplyOrError<?>>> requestRepliesAfter,
                                   int abortedRequest) {
        var trrEvent = createTRR(events, requestRepliesBefore, requestRepliesAfter, abortedRequest);
        return new Events(events.id, events.suspendPolicy, new ListValue<>(trrEvent));
    }

    private TunnelRequestReplies createTRR(Events events,
                                           List<Pair<Request<?>, ReplyOrError<?>>> requestRepliesBefore,
                                           List<Pair<Request<?>, ReplyOrError<?>>> requestRepliesAfter,
                                           int abortedRequest) {
        return new TunnelRequestReplies(events.getEvents().get(0).requestID,
                createTRRList(requestRepliesBefore),
                new ByteList(events.toPacket(state.vm()).toByteArray()),
                createTRRList(requestRepliesAfter),
                wrap(abortedRequest));
    }

    private ListValue<Events.RequestReply> createTRRList(List<Pair<Request<?>, ReplyOrError<?>>> requestReplies) {
        return new ListValue<>(Type.OBJECT, requestReplies.stream()
                .map(rr -> new Events.RequestReply(
                        new ByteList(rr.first.withNewId(0).toPacket(state.vm()).toByteArray()),
                        new ByteList(rr.second.withNewId(0).toPacket(state.vm()).toByteArray()))).collect(Collectors.toList()));
    }

    @AllArgsConstructor
    @Getter
    public static class IO {
        public final InputStream clientInputStream;
        public final OutputStream clientOutputStream;
        public final InputStream jvmInputStream;
        public final OutputStream jvmOutputStream;

        public List<InputStream> inputStreams() {
            return List.of(clientInputStream, jvmInputStream);
        }

        public boolean hasAnyDataAvailable() {
            return hasDataAvailable(clientInputStream) || hasDataAvailable(jvmInputStream);
        }

        public boolean hasJVMDataAvailable() {
            return hasDataAvailable(jvmInputStream);
        }

        public boolean hasClientDataAvailable() {
            return hasDataAvailable(clientInputStream);
        }
    }

    @SneakyThrows
    public static boolean hasDataAvailable(InputStream inputStream) {
        return inputStream.available() >= 11; // 11 bytes is the minimum size of a JDWP packet
    }

    public static boolean hasDataAvailable(List<InputStream> inputStream) {
        return inputStream.stream().anyMatch(BasicTunnel::hasDataAvailable);
    }

    private List<Pair<Request<?>, ReplyOrError<?>>> handleEvaluateProgramRequest(IO io,
                                                                                 int initialId, Program program,
                                                                                 int depth) {
        LOG.debug("evaluate program depth: {} (number of handleEvaluateProgramRequest on call stack)", depth);
        readRepliesForAllUnfinishedRequests(initialId, io,
                e -> writeClientReply(io.clientOutputStream, e));
        List<Pair<Request<?>, ReplyOrError<?>>> requestReplies = new ArrayList<>();
        new Evaluator(state.vm(), new Functions() {

            int id = getStartIdForIntermediateRequest();

            @Override
            @SneakyThrows
            protected Optional<Value> processRequest(Request<?> request) {
                var requestId = id++;
                var usedRequest = request.withNewId(requestId);
                if (request.hasNullReference()) {
                    return Optional.empty();
                }
                writeJvmRequest(io.jvmOutputStream, usedRequest);
                var optReply =
                        readJvmReply(io, true);
                if (optReply.isPresent()) {
                    var reply = optReply.get();
                    if (reply.isLeft()) { // abort the evaluation if an event happened
                        // state.addEvent(new WrappedPacket<>(reply.getLeft()));
                        LOG.debug("event {} causes the descend into another handleEvaluateProgramRequest",
                                reply.getLeft());
                        if (!request.onlyReads()) {
                            LOG.error("request {} is not read-only but got aborted by event {}", request,
                                    reply.getLeft());
                        } else {
                            state.ignoreUnfinished();
                        }
                        writeEvents(io, reply.getLeft(), requestReplies, initialId, false, depth + 1);
                        throw new EvaluationAbortException(Level.INFO, true, String.format("Event %s happened",
                                reply.getLeft()));
                    }
                    if (reply.getRight().isReply()) { // the good case, where we have a reply
                        var replOrErr = reply.getRight();
                        if (replOrErr.getId() == requestId && replOrErr.isReply()) { // the packet appeared as expected
                            requestReplies.add(p(usedRequest, replOrErr));
                            state.captureInformation(usedRequest, replOrErr.getReply());
                            return Optional.of((Value) replOrErr.getReply()); // common case
                        } else {
                            LOG.error("got packet {} with id {} but expected reply for packet {} with id {}",
                                    replOrErr, replOrErr.getId(), request, requestId);
                            throw new EvaluationAbortException(true, "got packet with unexpected id").log(LOG);
                        }
                    } else {
                        var err = reply.getRight().getErrorCode();
                        LOG.debug(String.format("we got an error reply with error code %s(%d, %s) for request %s%s",
                                Error.getConstantName(err), err, Error.getConstantDescription(err),
                                formatter.format(request), reply.getRight().isReplyLikeError() ? " (reply like error)"
                                        : ""));
                        if (reply.getRight().isReplyLikeError()) {
                            requestReplies.add(p(usedRequest, reply.getRight()));
                            return Optional.empty();
                        }
                        throw new EvaluationAbortException(false,
                                String.format("we got an error reply with error code %s(%d, %s) for request %s",
                                        Error.getConstantName(err), err, Error.getConstantDescription(err),
                                        formatter.format(request)));
                    }
                } else {
                    throw new EvaluationAbortException(false, "Assumed data but there is no data").log(LOG);
                }
            }
        }, e -> e.log(LOG, "Caught error and ignored some program statements")).evaluate(program);
        return requestReplies;
    }

    /** read the replies till there are no unfinished more */
    @SneakyThrows
    private void readRepliesForAllUnfinishedRequests(int ignoreId,
                                                     IO io,
                                                     Consumer<Either<Events, ReplyOrError<?>>> replyConsumer) {
        while (state.hasUnfinishedRequests(ignoreId)) {
            readJvmReply(io, true).ifPresent(replyConsumer);
        }
    }

    /**
     * helpful for debugging
     */
    static void printStream(String prefix, InputStream inputStream) throws IOException {
        int r;
        while ((r = inputStream.read()) != -1) {
            System.out.println(prefix + ": " + r);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static List<Pair<Request<?>, ReplyOrError<?>>> parseEvaluateProgramReply(VM vm, EvaluateProgramReply reply) {
        return reply.replies.stream().map(p -> {
            Request<?> innerRequest = JDWP.parse(vm, Packet.fromByteArray(p.request.bytes));
            ReplyOrError<?> innerReply = innerRequest.parseReply(new PacketInputStream(vm, p.reply.bytes));
            return (Pair<Request<?>, ReplyOrError<?>>)(Pair)p(innerRequest, innerReply);
        }).collect(Collectors.toList());
    }

    public static Triple<List<Pair<Request<?>, ReplyOrError<?>>>, Events, List<Pair<Request<?>, ReplyOrError<?>>>>
    parseTunnelRequestReplyEvent(VM vm, TunnelRequestReplies reply) {
        return Triple.of(parseTunnelRequestReplies(vm, reply.repliesBefore),
                Events.parse(vm, Packet.fromByteArray(reply.events.bytes)),
                parseTunnelRequestReplies(vm, reply.repliesAfter));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<Pair<Request<?>, ReplyOrError<?>>> parseTunnelRequestReplies(VM vm,
                                                                                     ListValue<Events.RequestReply> replies) {
        return replies.stream().map(p -> {
            Request<?> innerRequest = JDWP.parse(vm, Packet.fromByteArray(p.request.bytes));
            ReplyOrError<?> innerReply = innerRequest.parseReply(new PacketInputStream(vm, p.reply.bytes));
            return (Pair<Request<?>, ReplyOrError<?>>) (Pair) p(innerRequest, innerReply);
        }).collect(Collectors.toList());
    }

    public int getReplyCacheSize() {
        return state.getReplyCache().size();
    }

    public int getProgramCacheSize() {
        return state.getProgramCache().size();
    }

    private static final int SLOT_SIZE = 10000;
    private int lastSlot = 0;

    /** get an id that is at least {@link BasicTunnel#SLOT_SIZE} in the future  */
    private int getStartIdForIntermediateRequest() {
        lastSlot = Math.max(lastSlot, this.currentId) + SLOT_SIZE;
        return lastSlot;
    }
}
