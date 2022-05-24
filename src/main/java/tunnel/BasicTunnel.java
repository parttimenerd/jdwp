package tunnel;

import ch.qos.logback.classic.Logger;
import jdwp.EventCmds.Events;
import jdwp.ReplyOrError;
import jdwp.Request;
import tunnel.cli.Main;
import tunnel.util.Either;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;

/**
 * Basic tunnel that works without threads.
 * Be aware that blocking in a state listener blocks everything
 */
public class BasicTunnel {

    private final static Logger LOG = Main.LOG;

    private final State state;
    private final InetSocketAddress ownAddress;
    private final InetSocketAddress jvmAddress;

    BasicTunnel(State state, InetSocketAddress ownAddress, InetSocketAddress jvmAddress) {
        this.state = state;
        this.ownAddress = ownAddress;
        this.jvmAddress = jvmAddress;
    }

    public BasicTunnel(InetSocketAddress ownAddress, InetSocketAddress jvmAddress) {
        this(new State(), ownAddress, jvmAddress);
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
            var clientRequest = readClientRequest(clientInputStream);
            if (clientRequest.isPresent()) {
                writeJvmRequest(jvmOutputStream, clientRequest.get());
            }
            var reply = readJvmReply(jvmInputStream);
            if (reply.isPresent()) {
                writeClientReply(clientOutputStream, reply.get());
            }
            while (!hasDataAvailable(clientInputStream) && !hasDataAvailable(jvmInputStream)) {
                Thread.yield(); // hint to the scheduler that other work could be done
                state.tick();
            }
        }
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

    private Optional<Either<Events, ReplyOrError<?>>> readJvmReply(InputStream jvmInputStream) throws IOException {
        if (hasDataAvailable(jvmInputStream)) {
            return Optional.of(state.readReply(jvmInputStream));
        }
        return Optional.empty();
    }

    private void writeClientReply(OutputStream clientOutputStream, Either<Events, ReplyOrError<?>> reply) throws IOException {
        state.writeReply(clientOutputStream, reply);
    }

    private void writeJvmRequest(OutputStream jvmOutputStream, Request<?> request) throws IOException {
        state.writeRequest(jvmOutputStream, request);
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
}
