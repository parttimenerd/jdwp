package tunnel;

import ch.qos.logback.classic.Logger;
import com.spencerwi.either.Either;
import jdwp.*;
import jdwp.EventCmds.Events;
import jdwp.VirtualMachineCmds.IDSizesRequest;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import tunnel.State.LoggingListener;
import tunnel.agent.TunnelStartingAgent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This is the most basic endpoint that just logs a packets that go through it and
 * tries to parse them.
 */
@Command(name = "logger", mixinStandardHelpOptions = true,
        description = "Log all packets that go through this tunnel.")
public class PacketLogger implements Runnable {

    private final static Logger LOG = Main.LOG;

    @ParentCommand
    private Main mainConfig;

    private final State state = new State();

    private final AtomicInteger startedThreadCount = new AtomicInteger(0);

    public static PacketLogger create(Main mainConfig) {
        var pl = new PacketLogger();
        pl.mainConfig = mainConfig;
        return pl;
    }

    @Override
    public void run() {
        mainConfig.setDefaultLogLevel();
        LOG.info("Starting tunnel from {} to {}", mainConfig.getJvmAddress(), mainConfig.getOwnAddress());
        startServer();
    }

    public void startServer() {
        try {
            state.addListener(new LoggingListener());

            var ownServer = new ServerSocket(mainConfig.getOwnAddress().getPort());
            while (true) {
                LOG.info("Try to accept");
                Socket clientSocket = ownServer.accept();
                LOG.info("Accepted");
                var clientInputStream = clientSocket.getInputStream();
                var clientOutputStream = clientSocket.getOutputStream();
                LOG.info("try to connect to JVM");
                var jvmSocket = new Socket((String)null,
                        mainConfig.getJvmAddress().getPort());
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

    /** JDWP handshake, see spec */
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

    private void readWriteLoop(InputStream clientInputStream, OutputStream clientOutputStream, InputStream jvmInputStream, OutputStream jvmOutputStream) throws IOException {
        while (true) {
            var clientRequest = readClientRequest(clientInputStream);
            if (clientRequest.isPresent()) {
                //LOG.info("got request " + clientRequest.get());
                writeJvmRequest(jvmOutputStream, clientRequest.get());
            }
            var reply = readJvmReply(jvmInputStream);
            if (reply.isPresent()) {
                //LOG.info("got reply " + (reply.get().isLeft() ? reply.get().getLeft().toString() : reply.get().getRight().toString()));
                writeClientReply(clientOutputStream, reply.get());
            }
            Thread.yield();
        }
    }

    private Optional<Request<?>> readClientRequest(InputStream clientInputStream) throws IOException {
        if (clientInputStream.available() > 0) {
            return Optional.of(state.readRequest(clientInputStream));
        }
        return Optional.empty();
    }

    private Optional<Either<Events, ReplyOrError<?>>> readJvmReply(InputStream jvmInputStream) throws IOException {
        if (jvmInputStream.available() > 0) {
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

    static void printStream(String prefix, InputStream inputStream) throws IOException {
        int r;
        while ((r = inputStream.read()) != -1) {
            System.out.println(prefix + ": " + r);
        }
    }
}
