package tunnel.synth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.ClassUnload;
import jdwp.JDWP.ReturningRequestVisitor;
import jdwp.Reply;
import jdwp.Request;
import jdwp.TunnelCmds.EvaluateProgramReply;
import jdwp.TunnelCmds.EvaluateProgramReply.RequestReply;
import jdwp.TunnelCmds.EvaluateProgramRequest;
import jdwp.Value.ByteList;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.*;
import jdwp.util.MockClient;
import jdwp.util.MockVM;
import jdwp.util.MockVM.MockVMThreaded;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tunnel.BasicTunnel;
import tunnel.Listener;
import tunnel.ReplyCache;
import tunnel.State.Mode;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static tunnel.State.Mode.*;
import static tunnel.util.Util.findInetSocketAddress;
import static tunnel.util.Util.setDefaultLogLevel;

/**
 * Tests that combine {@link MockVM}, {@Link BasicTunnel} and {@link MockClient} to tests the
 * tunnel more realistically
 */
public class BasicMockVMTest {

    private final static Logger LOG = (Logger) LoggerFactory.getLogger("Test");

    static class BasicTunnelThreaded extends Thread implements Closeable {

        public final BasicTunnel tunnel;

        BasicTunnelThreaded(BasicTunnel tunnel) {
            this.tunnel = tunnel;
            this.start();
        }

        @Override
        public void run() {
            tunnel.run();
        }

        @Override
        public void close() {
            this.interrupt();
        }
    }

    /**
     * client -> tunnel -> MockVM
     */
    static class VMTunnelClientTuple implements Closeable {
        public final MockVMThreaded vmThreaded;
        public final MockVM vm;
        public final BasicTunnelThreaded tunnelThreaded;
        public final BasicTunnel tunnel;
        public final MockClient client;

        public VMTunnelClientTuple(MockVMThreaded vmThreaded, BasicTunnelThreaded tunnelThreaded, MockClient client) {
            this.vmThreaded = vmThreaded;
            this.vm = vmThreaded.vm;
            this.tunnelThreaded = tunnelThreaded;
            this.tunnel = tunnelThreaded.tunnel;
            this.client = client;
        }

        @Override
        public void close() throws IOException {
            this.vmThreaded.close();
            this.tunnelThreaded.close();
            this.client.close();
        }

        @SneakyThrows
        public static VMTunnelClientTuple create(Mode tunnelMode, ReturningRequestVisitor<Reply> provider) {
            var vmThreaded = MockVMThreaded.create(provider);
            var tunnelThreaded = new BasicTunnelThreaded(new BasicTunnel(findInetSocketAddress(),
                    vmThreaded.vm.getOwnAddress(), tunnelMode));
            return new VMTunnelClientTuple(vmThreaded, tunnelThreaded,
                    new MockClient((tunnelThreaded.tunnel.getOwnAddress())));
        }
    }

    /**
     * client -> clientTunnel -> serverTunnel -> MockVM, simulating the final goal
     */
    static class VMTunnelTunnelClientTuple implements Closeable {
        public final MockVMThreaded vmThreaded;
        public final MockVM vm;
        public final BasicTunnelThreaded clientTunnelThreaded;
        public final BasicTunnel clientTunnel;

        public final BasicTunnelThreaded serverTunnelThreaded;
        public final BasicTunnel serverTunnel;
        public final MockClient client;

        public VMTunnelTunnelClientTuple(MockVMThreaded vmThreaded, BasicTunnelThreaded clientTunnelThreaded,
                                         BasicTunnelThreaded serverTunnelThreaded, MockClient client) {
            this.vmThreaded = vmThreaded;
            this.vm = vmThreaded.vm;
            this.clientTunnelThreaded = clientTunnelThreaded;
            this.clientTunnel = clientTunnelThreaded.tunnel;
            this.serverTunnelThreaded = serverTunnelThreaded;
            this.serverTunnel = serverTunnelThreaded.tunnel;
            this.client = client;
        }

        @Override
        public void close() throws IOException {
            this.vmThreaded.close();
            this.clientTunnelThreaded.close();
            this.serverTunnelThreaded.close();
            this.client.close();
        }

        @SneakyThrows
        public static VMTunnelTunnelClientTuple create(ReturningRequestVisitor<Reply> provider) {
            var vmThreaded = MockVMThreaded.create(provider);
            var serverTunnelThreaded =
                    new BasicTunnelThreaded(new BasicTunnel(findInetSocketAddress(),
                            vmThreaded.vm.getOwnAddress(), SERVER)
                            .addListener(createLoggingListener("server-tunnel")));
            var clientTunnelThreaded = new BasicTunnelThreaded(
                    new BasicTunnel(findInetSocketAddress(),
                            serverTunnelThreaded.tunnel.getOwnAddress(), CLIENT)
                            .addListener(createLoggingListener("client-tunnel")));
            return new VMTunnelTunnelClientTuple(vmThreaded, clientTunnelThreaded, serverTunnelThreaded,
                    new MockClient((clientTunnelThreaded.tunnel.getOwnAddress())));
        }
    }

    @Test
    @SneakyThrows
    public void testBasicIdSizes() {
        setDefaultLogLevel(Level.INFO);
        try (var tp = VMTunnelClientTuple.create(NONE, new ReturningRequestVisitor<>() {
        })) {
            assertEquals(wrap(8), tp.client.query(new IDSizesRequest(0)).fieldIDSize);
        }
    }

    private static Listener createLoggingListener(String name) {
        return new Listener() {
            @Override
            public void onRequest(Request<?> request) {
                LOG.info("{}: request {}", name, request);
            }

            @Override
            public void onReply(Request<?> request, Reply reply) {
                LOG.info("{}: reply {} for request {}", name, reply, request);
            }
        };
    }

    @Test
    @SneakyThrows
    public void testEvaluateBasicProgram() {
        setDefaultLogLevel(Level.INFO);
        try (var tp = VMTunnelClientTuple.create(SERVER, new ReturningRequestVisitor<>() {
        })) {
            var request = new EvaluateProgramRequest(0, wrap("((= var0 (request VirtualMachine IDSizes)))"));
            assertEquals(new EvaluateProgramReply(0, new ListValue<>(new RequestReply(
                    new ByteList(new IDSizesRequest(0).toPacket(tp.vm.vm).toByteArray()),
                    new ByteList(tp.vm.getIdSizesReply().withNewId(0).toPacket(tp.vm.vm).toByteArray())
            ))), tp.client.query(request));
            assertFalse(tp.tunnel.getState().hasUnfinishedRequests());
        }
    }

    @Test
    @SneakyThrows
    public void testEvaluateBasicProgram2() {
        setDefaultLogLevel(Level.INFO);
        try (var tp = VMTunnelClientTuple.create(SERVER, new ReturningRequestVisitor<>() {
        })) {
            assertEquals(tp.vm.getIdSizesReply(), tp.client.query(new IDSizesRequest(0)));
            assertFalse(tp.tunnel.getState().hasUnfinishedRequests());
            var request = new EvaluateProgramRequest(1, wrap("((= var0 (request VirtualMachine IDSizes)))"));
            assertEquals(new EvaluateProgramReply(1, new ListValue<>(new RequestReply(
                    new ByteList(new IDSizesRequest(0).toPacket(tp.vm.vm).toByteArray()),
                    new ByteList(tp.vm.getIdSizesReply().withNewId(0).toPacket(tp.vm.vm).toByteArray())
            ))), tp.client.query(request));
            assertFalse(tp.tunnel.getState().hasUnfinishedRequests());
        }
    }

    @Test
    @SneakyThrows
    public void testEvaluateBasicProgram3() {
        setDefaultLogLevel(Level.INFO);
        try (var tp = VMTunnelClientTuple.create(SERVER, new ReturningRequestVisitor<>() {
        })) {
            var request = new EvaluateProgramRequest(1, wrap("((= var0 (request VirtualMachine IDSizes)))"));
            assertEquals(new EvaluateProgramReply(1, new ListValue<>(new RequestReply(
                    new ByteList(new IDSizesRequest(0).toPacket(tp.vm.vm).toByteArray()),
                    new ByteList(tp.vm.getIdSizesReply().withNewId(0).toPacket(tp.vm.vm).toByteArray())
            ))), tp.client.query(request));
            request = new EvaluateProgramRequest(2, wrap("((= var0 (request VirtualMachine IDSizes)))"));
            assertEquals(new EvaluateProgramReply(2, new ListValue<>(new RequestReply(
                    new ByteList(new IDSizesRequest(0).toPacket(tp.vm.vm).toByteArray()),
                    new ByteList(tp.vm.getIdSizesReply().withNewId(0).toPacket(tp.vm.vm).toByteArray())
            ))), tp.client.query(request));
        }
    }

    @Test
    @SneakyThrows
    public void testBasicIdSizesTunnelTunnel() {
        setDefaultLogLevel(Level.INFO);
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
        })) {
            assertEquals(wrap(8), tp.client.query(new IDSizesRequest(0)).fieldIDSize);
        }
    }

    @Test
    @SneakyThrows
    public void testEvaluateBasicProgramTunnelTunnel() {
        setDefaultLogLevel(Level.INFO);
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
        })) {
            var request = new EvaluateProgramRequest(0, wrap("((= var0 (request VirtualMachine IDSizes)))"));
            assertEquals(new EvaluateProgramReply(0, new ListValue<>(new RequestReply(
                    new ByteList(new IDSizesRequest(0).toPacket(tp.vm.vm).toByteArray()),
                    new ByteList(tp.vm.getIdSizesReply().withNewId(0).toPacket(tp.vm.vm).toByteArray())
            ))), tp.client.query(request));
            assertFalse(tp.serverTunnel.getState().hasUnfinishedRequests());
            assertFalse(tp.clientTunnel.getState().hasUnfinishedRequests());
        }
    }


    /**
     * Call IdSizes and ClassBySignature, then resume, and call IdSizes again, this should create a program
     * that is evaluated when the last IdSizes is called
     */
    @Test
    @SneakyThrows
    public void testEvaluateBasicProgramTunnelTunnel2() {
        var classesReply = new ClassesBySignatureReply(0, new ListValue<>(Type.OBJECT));
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
            @Override
            public Reply visit(ClassesBySignatureRequest classesBySignatureRequest) {
                return classesReply;
            }

            @Override
            public Reply visit(ResumeRequest resume) {
                return new ResumeReply(0);
            }
        })) {
            var idSizesRequest = new IDSizesRequest(0);
            var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
            // IdSizes and Classes request
            assertEquals(tp.vm.getIdSizesReply(), tp.client.query(idSizesRequest).withNewId(0));
            assertEquals(classesReply, tp.client.query(classesRequest.withNewId(1)));
            // Resume request, should break the partition
            assertEquals(new ResumeReply(0), tp.client.query(new ResumeRequest(2)));
            assertFalse(tp.serverTunnel.getState().hasUnfinishedRequests());
            assertFalse(tp.clientTunnel.getState().hasUnfinishedRequests());
            Thread.sleep(10);
            // check the program
            assertEquals(1, tp.clientTunnel.getState().getProgramCache().size());
            assertEquals("((= cause (request VirtualMachine IDSizes)) (= var0 (request VirtualMachine IDSizes)) (= " +
                    "var1 (request VirtualMachine " +
                    "ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))",
                    tp.clientTunnel.getState().getProgramCache().get(idSizesRequest).get().toString());
            assertEquals(0, tp.serverTunnel.getState().getProgramCache().size());
            // assumption is that calling idSizes triggers classes request
            assertEquals(List.of(idSizesRequest, classesRequest, new ResumeRequest(2)), tp.vm.getReceivedRequests());
            var idSizesReply = tp.client.query(idSizesRequest.withNewId(3));
            assertEquals(5, tp.vm.getReceivedRequests().size());
            assertEquals(List.of(idSizesRequest, classesRequest, new ResumeRequest(2), idSizesRequest,
                    classesRequest), tp.vm.getReceivedRequests());
            assertEquals(tp.vm.getIdSizesReply(), idSizesReply);
            // the next Classes request should not trickle down to the VM
            assertEquals(classesReply, tp.client.query(classesRequest));
            assertEquals(5, tp.vm.getReceivedRequests().size());

        }
    }

    @Test
    @SneakyThrows
    public void testEvaluateWithEventTunnelTunnel() {
        var classesReply = new ClassesBySignatureReply(0, new ListValue<>(Type.OBJECT));
        var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
        var events = new Events(0, wrap((byte) 2), new ListValue<>(new ClassUnload(wrap(0), wrap("sig"))));
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
            @Override
            public Reply visit(ClassesBySignatureRequest classesBySignatureRequest) {
                return classesReply;
            }
        })) {
            tp.vm.sendEvent(events);
            assertEquals(events, tp.client.readEvents());
            // send the classes request
            assertEquals(classesReply, tp.client.query(classesRequest));
            assertEquals(List.of(classesRequest), tp.vm.getReceivedRequests());
            tp.vm.sendEvent(events); // should break the partition at the client tunnel
            assertEquals(events, tp.client.readEvents());
            assertEquals(0, tp.clientTunnel.getState().getProgramCache().size());
            assertEquals(1, tp.serverTunnel.getState().getProgramCache().size());
            assertFalse(tp.serverTunnel.getState().hasUnfinishedRequests());
            assertFalse(tp.clientTunnel.getState().hasUnfinishedRequests());
            assertEquals("((= cause (events Event Composite (\"events\" 0 \"kind\")=(wrap \"string\" \"ClassUnload\")" +
                            " " +
                            "(\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                            "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"signature\")=(wrap \"string\" \"sig\")))" +
                            " (= var0" +
                            " (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))",
                    tp.serverTunnel.getState().getCachedProgram(events).toString());
            // the event should have caused the usage of this program, resulting in another Classes request to the vm
            assertEquals(List.of(classesRequest, classesRequest), tp.vm.getReceivedRequests());
            assertEquals(new ReplyCache(), tp.serverTunnel.getState().getReplyCache());
            assertEquals(new ReplyCache(List.of(p(classesRequest, classesReply))),
                    tp.clientTunnel.getState().getReplyCache());
            assertEquals(classesReply, tp.client.query(classesRequest));
            assertEquals(List.of(classesRequest, classesRequest), tp.vm.getReceivedRequests());
            var classUnloadEvent = new ClassUnload(wrap(1), wrap("bla"));
            tp.vm.sendEvent(0, classUnloadEvent); // invalidate the caches
            Thread.sleep(10);
            Assertions.assertTimeout(Duration.ofMillis(100), () -> {
                while (tp.clientTunnel.getState().getReplyCache().size() > 0) Thread.yield();
            });
            assertEquals(new ReplyCache(), tp.clientTunnel.getState().getReplyCache());
            assertEquals(new Events(0, wrap((byte) 2), new ListValue<>(classUnloadEvent)), tp.client.readEvents());
        }
    }
}
