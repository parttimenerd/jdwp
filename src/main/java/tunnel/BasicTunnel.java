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
import jdwp.util.Pair;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.slf4j.LoggerFactory;
import tunnel.State.Formatter;
import tunnel.State.Mode;
import tunnel.State.ReadReplyResult;
import tunnel.State.WrappedPacket;
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
                handshake(clientInputStream, clientOutputStream, jvmInputStream, jvmOutputStream);
                readWriteLoop(clientInputStream, clientOutputStream, jvmInputStream, jvmOutputStream);
            }
        } catch (IOException ex) {
            LOG.error("Problems with TCP streams", ex);
            System.exit(1);
        }
    }

    /**
     * JDWP handshake, see spec
     */
    private void handshake(InputStream clientInputStream, OutputStream clientOutputStream,
                           InputStream jvmInputStream, OutputStream jvmOutputStream) throws IOException {
        LOG.info("Attempt JDWP-Handshake");
        String handshake = "JDWP-Handshake";
        byte[] hsBytes = clientInputStream.readNBytes(handshake.length());
        String hsStr = new String(hsBytes);
        if (!hsStr.equals(handshake)) {
            LOG.error("Expected \"JDWP-Handshake\" from client, but got \"{}\"", hsStr);
            throw new IOException();
        }
        jvmOutputStream.write(hsBytes);
        byte[] hsBytes2 = jvmInputStream.readNBytes(handshake.length());
        String hsStr2 = new String(hsBytes2);
        if (!hsStr2.equals(handshake)) {
            LOG.error("Expected \"JDWP-Handshake\" from jvm, but got \"{}\"", hsStr);
            throw new IOException();
        }
        clientOutputStream.write(hsBytes);
        LOG.info("JDWP-Handshake was successful");
    }

    /**
     * Loops over the client and jvm input streams, processing and propagating the incoming and outgoing packets.
     */
    private void readWriteLoop(InputStream clientInputStream, OutputStream clientOutputStream,
                               InputStream jvmInputStream, OutputStream jvmOutputStream) throws IOException {
        while (true) {
            Optional<Request<?>> clientRequest = Optional.empty();
            try {
                clientRequest = readClientRequest(clientInputStream);
                if (clientRequest.isPresent()) {
                    var request = clientRequest.get();
                    currentId = request.getId();
                    Program program;
                    if (request instanceof EvaluateProgramRequest) { // handle evaluation requests
                        handleEvaluateProgramRequest(jvmInputStream, jvmOutputStream,
                                clientOutputStream, (EvaluateProgramRequest) request);
                    } else if (request instanceof UpdateCacheRequest) { // handle program cache update requests
                        state.updateProgramCache(((UpdateCacheRequest) request).programs
                                .asList().stream().map(StringValue::getValue).collect(Collectors.toList()));
                        state.getUnfinished().remove(request.getId());
                    } else if ((program = state.getCachedProgram(request)) != null) {
                        var reducedProgram = state.reduceProgramToNonCachedRequests(program);
                        if (state.hasCachedReply(request)) {
                            // the found program is fully cached
                            // we therefore just use the cached program
                            var reply = state.getCachedReply(request);
                            assert reply != null;
                            LOG.info("Cached reply for  {}: {}", formatter.format(request), formatter.format(reply));
                            state.addReply(new WrappedPacket<>(reply));
                            writeClientReply(clientOutputStream, Either.right(reply));
                        }
                        if (!reducedProgram.isEmpty()) {
                            LOG.info("Using cached program for request  {}:\n {}", formatter.format(request),
                                    reducedProgram);
                            var evaluateRequest = new EvaluateProgramRequest(reducedProgram.hasCause() ? request.getId()
                                    : request.getId() + 10000,
                                    wrap(reducedProgram.toPrettyString()));
                            state.addUnfinishedEvaluateRequest(evaluateRequest);
                            state.writeRequest(jvmOutputStream, evaluateRequest);
                        }
                    } else if (state.hasCachedReply(request)) {
                        var reply = state.getCachedReply(request);
                        assert reply != null;
                        LOG.info("Cached reply for  {}: {}", formatter.format(request), formatter.format(reply));
                        state.addReply(new WrappedPacket<>(reply));
                        writeClientReply(clientOutputStream, Either.right(reply));
                    } else {
                        state.writeRequest(jvmOutputStream, request);
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
                        if (packet.getCmd() == TunnelCmds.COMMAND_SET) {
                            LOG.error("packet error: ", e);
                            state.addAndWriteError(clientOutputStream,new ReplyOrError<>(
                                    clientRequest.get().getId(), EvaluateProgramRequest.METADATA,
                                    (short) CANNOT_EVALUATE_PROGRAM));
                            continue; // tunnel commands cannot be sent directly to the VM
                        }
                        LOG.error("packet error: ", e);
                    } catch (Exception ex) {
                    }
                    jvmOutputStream.write(e.getContent());
                } else {
                    LOG.error("packet error during request handling", e);
                }
            } catch (Exception ex) {
                if (clientRequest.isPresent()) {
                    if (clientRequest.get() instanceof EvaluateProgramRequest) {
                        LOG.error("packet error: ", ex);
                        state.addAndWriteError(clientOutputStream, new ReplyOrError<>(
                                clientRequest.get().getId(), EvaluateProgramRequest.METADATA,
                                (short) CANNOT_EVALUATE_PROGRAM));
                        continue;
                    }
                }
                LOG.error("error during request handling", ex);
            }
            Optional<Either<Events, ReplyOrError<?>>> reply = Optional.empty();
            try {
                reply = readJvmReply(jvmInputStream, clientOutputStream, jvmOutputStream, false);
                if (reply.isPresent() && reply.get().isLeft()) {
                    currentId = reply.get().getLeft().getId();
                    var events = reply.get().getLeft();
                    var program = state.getCachedProgram(events);
                    if (state.isServer() && program != null) {
                        LOG.info("Using cached program for events  {}:\n {}", formatter.format(events),
                                program.toPrettyString());
                        handleEvaluateProgramEvent(jvmInputStream, jvmOutputStream,
                                clientOutputStream, events, program);
                        LOG.debug("Finished handling program");
                        reply = Optional.empty();
                    }
                }
                if (reply.isPresent() && reply.get().isRight() &&
                        reply.get().getRight().isReply() &&
                        reply.get().getRight().getReply() instanceof UpdateCacheReply) {
                    continue; // skip the reply to the update cache request
                }
            } catch (ClosedStreamException e) {
                return;
            } catch (PacketError e) {
                LOG.error("packet error: ", e);
                if (e.hasContent()) {
                    clientOutputStream.write(e.getContent());
                    reply = Optional.empty();
                }
            }
            reply.ifPresent(eventsReplyOrErrorEither -> {
                writeClientReply(clientOutputStream, eventsReplyOrErrorEither);
            });
            int yieldCount = 0;
            while (!hasDataAvailable(clientInputStream) && !hasDataAvailable(jvmInputStream)) {
                state.tick();
                if (state.isClient() && state.hasProgramsToSendToServer()) {
                    sendProgramsToServer(jvmInputStream, jvmOutputStream);
                    yieldCount = 0;
                } else {
                    assert state.getProgramsToSendToServer().isEmpty();
                }
                yield(yieldCount++); // hint to the scheduler that other work could be done
            }
        }
    }

    /** yields the thread after each check, returns true if data is really available or false it just ran into a timeout */
    private boolean waitTillDataAvailable(InputStream inputStream, Duration maxDuration) throws IOException {
        var start = System.currentTimeMillis();
        int yieldCount = 0;
        while (!hasDataAvailable(inputStream) && System.currentTimeMillis() - start < maxDuration.toMillis()) {
            yield(yieldCount++);
        }
        return hasDataAvailable(inputStream);
    }

    /**
     *
     * @param yieldCount number of yields without any change
     */
    private void yield(int yieldCount) {
        if (yieldCount > 1000) {
            Thread.yield(); // free resources
        }
    }

    @SneakyThrows
    private void sendProgramsToServer(InputStream jvmInputStream, OutputStream jvmOutputStream) {
        jvmOutputStream.write(new UpdateCacheRequest(currentId + 100000,
                new ListValue<>(Type.STRING,
                        state.drainProgramsToSendToServer().stream().map(Statement::toPrettyString)
                                .map(PrimitiveValue::wrap).collect(Collectors.toList())))
                .toPacket(state.vm()).toByteArray());
    }

    private boolean hasDataAvailable(InputStream inputStream) throws IOException {
        return inputStream.available() >= 11; // 11 bytes is the minimum size of a JDWP packet
    }

    private Optional<Request<?>> readClientRequest(InputStream clientInputStream) throws IOException {
        if (hasDataAvailable(clientInputStream)) {
            return Optional.of(state.readRequest(clientInputStream));
        }
        return Optional.empty();
    }

    private Optional<ReadReplyResult> readJvmReplyDoNotIgnoreIgnored(InputStream jvmInputStream,
                                                   OutputStream clientOutputStream,
                                                   OutputStream jvmOutputStream) throws IOException {
        if (hasDataAvailable(jvmInputStream)) {
            var reply =
                    state.readReply(jvmInputStream, clientOutputStream, jvmOutputStream);
            return Optional.ofNullable(reply);
        }
        return Optional.empty();
    }

    /**
     * ignores ignored statements and reads again if encountered and loop == true
     *
     * @param loop wait for {@link BasicTunnel#waitTillNextReply} for data to be available and than ignore
     *             ignored replies till a real reply is encountered
     */
    private Optional<Either<Events, ReplyOrError<?>>> readJvmReply(InputStream jvmInputStream,
                                                   OutputStream clientOutputStream,
                                                   OutputStream jvmOutputStream, boolean loop) throws IOException {
        if (!loop) {
            return readJvmReplyDoNotIgnoreIgnored(jvmInputStream, clientOutputStream, jvmOutputStream)
                    .flatMap(ReadReplyResult::getEither);
        }
        Optional<ReadReplyResult> reply;
        do {
            // first time in a while that I used a do-while loop
            waitTillDataAvailable(jvmInputStream, waitTillNextReply);
        } while ((reply = readJvmReplyDoNotIgnoreIgnored(jvmInputStream, clientOutputStream, jvmOutputStream))
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

    private void handleEvaluateProgramRequest(InputStream jvmInputStream, OutputStream jvmOutputStream,
                                              OutputStream clientOutputStream, EvaluateProgramRequest request) {
        var program = Program.parse(request.program.value);
        List<Pair<Request<?>, ReplyOrError<?>>> requestReplies = new ArrayList<>();
        try {
            requestReplies = handleEvaluateProgramRequest(jvmInputStream, jvmOutputStream, clientOutputStream,
                    request.id, program);
        } catch (EvaluationAbortException e) {
            LOG.error("Evaluation aborted: ", e);
        }
        if (requestReplies.isEmpty()) {
            return;
        }
        // if it all worked out, then request replies contains all request replies
        var reply = new ReplyOrError<>(request.getId(),
                new EvaluateProgramReply(request.getId(), new ListValue<>(Type.OBJECT,
                        requestReplies.stream().map(rr -> new RequestReply(
                                new ByteList(rr.first.withNewId(0).toPacket(state.vm()).toByteArray()),
                                new ByteList(rr.second.withNewId(0).toPacket(state.vm()).toByteArray()))).collect(Collectors.toList()))));
        state.addReply(new WrappedPacket<>(reply));
        state.getUnfinished().remove(request.id);
        state.getUnfinishedEvaluateRequests().remove(request.id);
        state.writeReply(clientOutputStream, Either.right(reply));
    }

    private void handleEvaluateProgramEvent(InputStream jvmInputStream, OutputStream jvmOutputStream,
                                            OutputStream clientOutputStream, Events events, Program program) {
        List<Pair<Request<?>, ReplyOrError<?>>> requestReplies;
        try {
            requestReplies = handleEvaluateProgramRequest(jvmInputStream, jvmOutputStream, clientOutputStream,
                    events.id, program);
        } catch (EvaluationAbortException e) {
            state.removeCachedProgram(program);
            LOG.error(String.format("Evaluated %s but got error", program), e);
            return;
        }
        if (requestReplies.isEmpty()) {
            state.addAndWriteReply(clientOutputStream,
                    Either.right(new ReplyOrError<>(events.getId(), EvaluateProgramRequest.METADATA,
                            (short) CANNOT_EVALUATE_PROGRAM)));
            return;
        }
        // if it all worked out, then request replies contains all request replies
        var newEvents = createTRREvents(events, requestReplies, -1);
        state.addEvent(new WrappedPacket<>(events));
        state.writeReply(clientOutputStream, Either.left(newEvents));
    }

    private Events createTRREvents(Events events, List<Pair<Request<?>, ReplyOrError<?>>> requestReplies,
                                   int abortedRequest) {
        var trrEvent = createTRR(events, requestReplies, abortedRequest);
        return new Events(events.id, events.suspendPolicy, new ListValue<>(trrEvent));
    }

    private TunnelRequestReplies createTRR(Events events, List<Pair<Request<?>, ReplyOrError<?>>> requestReplies,
                                           int abortedRequest) {
        return new TunnelRequestReplies(events.getEvents().get(0).requestID,
                new ByteList(events.toPacket(state.vm()).toByteArray()),
                new ListValue<>(Type.OBJECT, requestReplies.stream()
                        .map(rr -> new Events.RequestReply(
                                new ByteList(rr.first.withNewId(0).toPacket(state.vm()).toByteArray()),
                                new ByteList(rr.second.withNewId(0).toPacket(state.vm()).toByteArray()))).collect(Collectors.toList())),
                wrap(abortedRequest));
    }

    private List<Pair<Request<?>, ReplyOrError<?>>> handleEvaluateProgramRequest(InputStream jvmInputStream,
                                                                                 OutputStream jvmOutputStream,
                                                                                 OutputStream clientOutputStream,
                                                                                 int initialId, Program program) {
        readRepliesForAllUnfinishedRequests(initialId, jvmInputStream, clientOutputStream, jvmOutputStream,
                e -> writeClientReply(clientOutputStream, e));
        List<Pair<Request<?>, ReplyOrError<?>>> requestReplies = new ArrayList<>();
        new Evaluator(state.vm(), new Functions() {

            int id = initialId + 10000;

            @Override
            @SneakyThrows
            protected Optional<Value> processRequest(Request<?> request) {
                var requestId = id++;
                var usedRequest = request.withNewId(requestId);
                writeJvmRequest(jvmOutputStream, usedRequest);
                var optReply =
                        readJvmReply(jvmInputStream, clientOutputStream, jvmOutputStream, true);
                if (optReply.isPresent()) {
                    var reply = optReply.get();
                    if (reply.isLeft()) { // abort the evaluation if an event happened
                        state.addEvent(new WrappedPacket<>(reply.getLeft()));
                        writeClientReply(clientOutputStream, Either.left(createTRREvents(reply.getLeft(),
                                requestReplies, initialId)));
                        state.ignoreUnfinished();
                        System.out.println("#################### wrote client reply");
                        throw new EvaluationAbortException(true, String.format("Event %s happened", reply.getLeft()));
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
                            throw new EvaluationAbortException(true, "got packet with unexpected id");
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
                    LOG.error("Assumed that there is data but there is no data");
                    throw new EvaluationAbortException(true, "Assumed data but there is no data");
                }
            }
        }, e -> LOG.error("Caught error and ignored some program statements", e)).evaluate(program);
        return requestReplies;
    }

    /** read the replies till there are no unfinished more */
    @SneakyThrows
    private void readRepliesForAllUnfinishedRequests(int ignoreId,
                                                     InputStream jvmInputStream, OutputStream clientOutputStream,
                                                     OutputStream jvmOutputStream,
                                                     Consumer<Either<Events, ReplyOrError<?>>> replyConsumer) {
        while (state.hasUnfinishedRequests(ignoreId)) {
            readJvmReply(jvmInputStream, clientOutputStream, jvmOutputStream, true).ifPresent(replyConsumer);
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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Pair<Events, List<Pair<Request<?>, ReplyOrError<?>>>> parseTunnelRequestReplyEvent(VM vm,
                                                                                           TunnelRequestReplies reply) {
        return p(Events.parse(vm, Packet.fromByteArray(reply.events.bytes)), reply.replies.stream().map(p -> {
            Request<?> innerRequest = JDWP.parse(vm, Packet.fromByteArray(p.request.bytes));
            ReplyOrError<?> innerReply = innerRequest.parseReply(new PacketInputStream(vm, p.reply.bytes));
            return (Pair<Request<?>, ReplyOrError<?>>) (Pair) p(innerRequest, innerReply);
        }).collect(Collectors.toList()));
    }

    public int getReplyCacheSize() {
        return state.getReplyCache().size();
    }

    public int getProgramCacheSize() {
        return state.getProgramCache().size();
    }
}
