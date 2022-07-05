package jdwp.util;

import ch.qos.logback.classic.Logger;
import jdwp.EventCmds.Events;
import jdwp.*;
import jdwp.EventCmds.Events.EventCommon;
import lombok.SneakyThrows;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Mocks a JDWP client */
public class MockClient implements Closeable {

    private final static Logger LOG = (Logger) LoggerFactory.getLogger("MockClient");

    private final VM vm;
    private final Socket serverSocket;
    private InputStream serverInputStream;
    private OutputStream serverOutputStream;
    private int id = 0;

    @SneakyThrows
    public MockClient(InetSocketAddress serverAddress) {
        this.vm = new VM(0);
        this.serverSocket = new Socket(serverAddress.getHostName(), serverAddress.getPort());
        handShake();
    }

    @SneakyThrows
    private void handShake() {
        serverInputStream = serverSocket.getInputStream();
        serverOutputStream = serverSocket.getOutputStream();
        LOG.info("Attempt JDWP-Handshake");
        String handshake = "JDWP-Handshake";
        serverOutputStream.write(handshake.getBytes());
        byte[] hsBytes = serverInputStream.readNBytes(handshake.length());
        String hsStr = new String(hsBytes);
        if (!hsStr.equals(handshake)) {
            LOG.error("Expected \"JDWP-Handshake\" from client, but got \"{}\"", hsStr);
            throw new IOException();
        }
        LOG.info("JDWP-Handshake was successful");
    }

    @SneakyThrows
    public <R extends Value & Reply> ReplyOrError<R> queryWithError(Request<R> request) {
        request = request.withNewId(id++);
        request.toPacket(vm).write(serverOutputStream);
        while (!hasDataAvailable()) {}
        return request.parseReply(PacketInputStream.read(vm, serverInputStream));
    }

    @SneakyThrows
    public EventCommon queryEvent(Request<?> request) {
        request = request.withNewId(id++);
        request.toPacket(vm).write(serverOutputStream);
        while (!hasDataAvailable()) {}
        return readEvents().events.get(0);
    }

    @SneakyThrows
    public <R extends Value & Reply> R query(Request<R> request) {
        return queryWithError(request).getReply();
    }

    @SneakyThrows
    public Events readEvents() {
        while (!hasDataAvailable()) {}
        return Events.parse(PacketInputStream.read(vm, serverInputStream));
    }

    @SneakyThrows
    private boolean hasDataAvailable() {
        return serverInputStream.available() > 3;
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
