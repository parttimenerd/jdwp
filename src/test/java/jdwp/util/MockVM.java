package jdwp.util;

import ch.qos.logback.classic.Logger;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.EventCommon;
import jdwp.*;
import jdwp.JDWP.ReturningRequestVisitor;
import jdwp.Value.ListValue;
import jdwp.VirtualMachineCmds.IDSizesReply;
import jdwp.VirtualMachineCmds.IDSizesRequest;
import lombok.Getter;
import lombok.SneakyThrows;
import org.slf4j.LoggerFactory;
import tunnel.util.Either;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;

/**
 * A JDWP endpoint that mimics the real JVM.
 * It does only support the standard JDWP commands and records all requests, replies and events.
 * <p>
 * It implements IDSizesRequest and the handshake by default
 */
@Getter
public abstract class MockVM implements ReturningRequestVisitor<Reply>, Closeable {

    private final static Logger LOG = (Logger) LoggerFactory.getLogger("MockVM");

    private final InetSocketAddress ownAddress;
    private final List<Request<?>> receivedRequests;
    private final List<Pair<Request<?>, Reply>> requestReplies;
    private final List<Either<Events, Reply>> sentEventsAndReplies;

    private final ServerSocket server;
    private Socket clientSocket;
    private InputStream clientInputStream;
    private OutputStream clientOutputStream;
    public final VM vm;
    private final IDSizesReply idSizesReply;
    private int id = 0;
    private final BlockingQueue<Events> eventsToSend;

    @SneakyThrows
    public MockVM() {
        this.receivedRequests = Collections.synchronizedList(new ArrayList<>());
        this.requestReplies = Collections.synchronizedList(new ArrayList<>());
        this.sentEventsAndReplies = Collections.synchronizedList(new ArrayList<>());
        this.eventsToSend = new ArrayBlockingQueue<>(1000);
        this.server = new ServerSocket(0);
        this.ownAddress = new InetSocketAddress(server.getLocalPort());
        this.vm = new VM(0);
        this.idSizesReply = new IDSizesReply(0, wrap(8), wrap(8), wrap(8), wrap(8), wrap(8));
        vm.setSizes(idSizesReply);
    }

    @SneakyThrows
    private void handShake() {
        clientSocket = server.accept();
        clientInputStream = clientSocket.getInputStream();
        clientOutputStream = clientSocket.getOutputStream();
        LOG.info("Attempt JDWP-Handshake");
        String handshake = "JDWP-Handshake";
        byte[] hsBytes = clientInputStream.readNBytes(handshake.length());
        String hsStr = new String(hsBytes);
        if (!hsStr.equals(handshake)) {
            LOG.error("Expected \"JDWP-Handshake\" from client, but got \"{}\"", hsStr);
            throw new IOException();
        }
        clientOutputStream.write(hsBytes);
        LOG.info("JDWP-Handshake was successful");
    }

    @SneakyThrows
    private boolean hasDataAvailable() {
        return clientInputStream.available() > 3;
    }

    @SneakyThrows
    private void runIteration() {
        while (hasDataAvailable()) {
            var ps = PacketInputStream.read(vm, clientInputStream);
            handle(JDWP.parse(ps)).toPacket(vm).write(clientOutputStream);
        }
        while (eventsToSend.size() > 0) {
            var events = eventsToSend.take().withNewId(id++);
            sentEventsAndReplies.add(Either.left(events));
            events.toPacket(vm).write(clientOutputStream);
        }
    }

    public ReplyOrError<?> handle(Request<?> request) {
        id = request.getId() + 1;
        var reply = request.accept(this);
        if (reply == null) {
            reply = accept(request);
        }
        if (reply == null) {
            return new ReplyOrError<>(request.getId(), (short) 1);
        }
        reply = (Reply) reply.withNewId(request.getId());
        receivedRequests.add(request);
        requestReplies.add(p(request, reply));
        sentEventsAndReplies.add(Either.right(reply));
        return new ReplyOrError<>(reply);
    }

    public Reply accept(Request<?> request) {
        return request.accept(this);
    }

    @Override
    public Reply visit(IDSizesRequest iDSizesRequest) {
        return idSizesReply;
    }

    public void sendEvent(int id, EventCommon event) {
        sendEvent(new Events(id, wrap((byte) 2), new ListValue<>(event)));
    }

    @SneakyThrows
    public void sendEvent(Events events) {
        eventsToSend.put(events);
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    public static MockVM create(ReturningRequestVisitor<Reply> provider) {
        return new MockVM() {
            @Override
            public Reply accept(Request<?> request) {
                return request.accept(provider);
            }
        };
    }

    @Getter
    public static class MockVMThreaded extends Thread implements Closeable {
        public final MockVM vm;
        private volatile boolean shouldClose = false;

        public MockVMThreaded(MockVM vm) {
            this.vm = vm;
            this.start();
        }

        @Override
        @SneakyThrows
        public void run() {
            vm.handShake();
            while (!shouldClose) {
                vm.runIteration();
            }
            vm.close();
        }

        @Override
        public void close() {
            this.shouldClose = true;
        }

        public static MockVMThreaded create(ReturningRequestVisitor<Reply> provider) {
            return new MockVMThreaded(MockVM.create(provider));
        }
    }
}
