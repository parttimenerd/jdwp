package tunnel.synth;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.ClassUnload;
import jdwp.EventCmds.Events.ThreadDeath;
import jdwp.EventCmds.Events.VMDeath;
import jdwp.EventRequestCmds.SetReply;
import jdwp.EventRequestCmds.SetRequest;
import jdwp.JDWP.Error;
import jdwp.JDWP.ReturningRequestVisitor;
import jdwp.PacketError.SupplierWithError;
import jdwp.Reference;
import jdwp.ReferenceTypeCmds.SourceDebugExtensionRequest;
import jdwp.Reply;
import jdwp.ReplyOrError;
import jdwp.Request;
import jdwp.ThreadGroupReferenceCmds.NameRequest;
import jdwp.ThreadReferenceCmds.NameReply;
import jdwp.TunnelCmds.EvaluateProgramReply;
import jdwp.TunnelCmds.EvaluateProgramReply.RequestReply;
import jdwp.TunnelCmds.EvaluateProgramRequest;
import jdwp.Value.ByteList;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.*;
import jdwp.util.MockClient;
import jdwp.util.MockVM;
import jdwp.util.MockVM.ErrorCodeException;
import jdwp.util.MockVM.MockVMThreaded;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import tunnel.BasicTunnel;
import tunnel.Listener;
import tunnel.ReplyCache;
import tunnel.State.Mode;
import tunnel.synth.program.AST;
import tunnel.synth.program.AST.AssignmentStatement;
import tunnel.synth.program.AST.EventsCall;
import tunnel.synth.program.AST.RequestCall;
import tunnel.synth.program.Program;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.Reference.klass;
import static jdwp.Reference.threadGroup;
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
        var redefineClassesRequest = new RedefineClassesRequest(0, new ListValue<>(Type.OBJECT));
        var redefineClassesReply = new RedefineClassesReply(0);
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
            @Override
            public Reply visit(ClassesBySignatureRequest classesBySignatureRequest) {
                return classesReply;
            }

            @Override
            public Reply visit(ResumeRequest resume) {
                return new ResumeReply(0);
            }

            @Override
            public Reply visit(RedefineClassesRequest redefineClassesRequest) {
                return redefineClassesReply;
            }
        })) {
            var idSizesRequest = new IDSizesRequest(0);
            var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
            // IdSizes and Classes request
            assertEquals(tp.vm.getIdSizesReply(), tp.client.query(idSizesRequest).withNewId(0));
            assertEquals(1, tp.clientTunnel.getReplyCacheSize());

            assertEquals(classesReply, tp.client.query(classesRequest.withNewId(1)));
            assertEquals(2, tp.clientTunnel.getReplyCacheSize());

            // RedefineClasses request, should break the partition

            assertEquals(redefineClassesReply, tp.client.query(redefineClassesRequest));
            assertFalse(tp.serverTunnel.getState().hasUnfinishedRequests());
            assertFalse(tp.clientTunnel.getState().hasUnfinishedRequests());
            assertEqualsTimeout(1, tp.clientTunnel::getProgramCacheSize, Duration.ofSeconds(1));
            assertEquals(1, tp.clientTunnel.getReplyCacheSize());
            assertEquals(0, tp.serverTunnel.getReplyCacheSize()); // just to for regression testing
            // check the program
            assertEquals(1, tp.clientTunnel.getState().getProgramCache().size());
            assertEquals("((= cause (request VirtualMachine IDSizes)) (= var0 (request VirtualMachine IDSizes)) (= " +
                            "var1 (request VirtualMachine " +
                            "ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))",
                    tp.clientTunnel.getState().getProgramCache().get(idSizesRequest).get().toString());
            assertEquals(0, tp.serverTunnel.getState().getProgramCache().size());
            // assumption is that calling idSizes triggers classes request
            assertEquals(List.of(idSizesRequest, classesRequest, redefineClassesRequest), tp.vm.getReceivedRequests());
            var idSizesReply = tp.client.query(idSizesRequest.withNewId(3));
            assertEquals(tp.vm.getIdSizesReply(), idSizesReply);
            // but idSizesRequest is still cached
            // check that the vm received the classesRequest
            assertEqualsTimeout(4, () -> tp.vm.getReceivedRequests().size(), Duration.ofMillis(1000));
            assertEquals(List.of(idSizesRequest, classesRequest, redefineClassesRequest, classesRequest),
                    tp.vm.getReceivedRequests());
            // check that the client received the classesReply and put into its cache
            assertEqualsTimeout(2, tp.clientTunnel::getReplyCacheSize, Duration.ofMillis(1000000));
            // one of these replies is prefetched
            assertEquals(1, tp.clientTunnel.getState().getReplyCache().getPrefetchedStatistics().size());
            assertEquals(2, tp.clientTunnel.getState().getReplyCache().getNonPrefetchedStatistics().size());
            assertEquals(3, tp.clientTunnel.getState().getReplyCache().getStatistics().size());
            // the next Classes request should not trickle down to the VM
            assertEquals(classesReply, tp.client.query(classesRequest));
            assertEquals(4, tp.vm.getReceivedRequests().size());

            System.out.println(tp.clientTunnel.getState().getReplyCache().getPrefetchedStatistics().toLongTable());
            System.out.println(tp.clientTunnel.getState().getReplyCache().getNonPrefetchedStatistics().toLongTable());
            System.out.println(tp.clientTunnel.getState().getReplyCache().getStatistics().toLongTable());
        }
    }

    @Test
    @SneakyThrows
    public void testEvaluateBasicProgramTunnelTunnelWithError() {
        var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
        var classesReply = new ClassesBySignatureReply(0, new ListValue<>(Type.OBJECT));
        var redefineClassesRequest = new RedefineClassesRequest(0, new ListValue<>(Type.OBJECT));
        var redefineClassesReply = new RedefineClassesReply(0);
        var sourceDebugExtensionRequest = new SourceDebugExtensionRequest(0, klass(10));
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
            @Override
            public Reply visit(ClassesBySignatureRequest classesBySignatureRequest) {
                return classesReply;
            }

            @Override
            public Reply visit(ResumeRequest resume) {
                return new ResumeReply(0);
            }

            @Override
            public Reply visit(RedefineClassesRequest redefineClassesRequest) {
                return redefineClassesReply;
            }

            @Override
            public ReplyOrError<?> visit(SourceDebugExtensionRequest sourceDebugExtensionRequest) {
                return null;
            }
        })) {
            var idSizesRequest = new IDSizesRequest(0);

            tp.clientTunnel.getState().getProgramCache().accept(Program.parse("((= cause (request VirtualMachine IDSizes)) " +
                    "(= var0 (request VirtualMachine IDSizes)) " +
                    "(= var10 (request ReferenceType SourceDebugExtension (\"refType\")=(wrap \"class-reference\" 10)))" +
                    "(= var1 (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))"));

            // IdSizes and Classes request
            assertEquals(tp.vm.getIdSizesReply(), tp.client.query(idSizesRequest).withNewId(0));
            assertEquals(2, tp.clientTunnel.getReplyCacheSize());

            assertEquals(classesReply, tp.client.query(classesRequest.withNewId(1)));
            assertEquals(2, tp.clientTunnel.getReplyCacheSize());
        }
    }

    @Test
    @SneakyThrows
    public void testEvaluateBasicProgramTunnelTunnelWithErrorAndDepending() {
        var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
        var classesReply = new ClassesBySignatureReply(0, new ListValue<>(Type.OBJECT));
        var redefineClassesRequest = new RedefineClassesRequest(0, new ListValue<>(Type.OBJECT));
        var redefineClassesReply = new RedefineClassesReply(0);
        var sourceDebugExtensionRequest = new SourceDebugExtensionRequest(0, klass(10));
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
            @Override
            public Reply visit(ClassesBySignatureRequest classesBySignatureRequest) {
                return classesReply;
            }

            @Override
            public Reply visit(ResumeRequest resume) {
                return new ResumeReply(0);
            }

            @Override
            public Reply visit(RedefineClassesRequest redefineClassesRequest) {
                return redefineClassesReply;
            }

            @Override
            public ReplyOrError<?> visit(SourceDebugExtensionRequest sourceDebugExtensionRequest) {
                return null;
            }
        })) {
            var idSizesRequest = new IDSizesRequest(0);

            tp.clientTunnel.getState().getProgramCache().accept(Program.parse("((= cause (request VirtualMachine IDSizes)) " +
                    "(= var0 (request VirtualMachine IDSizes)) " +
                    "(= var10 (request ReferenceType SourceDebugExtension (\"refType\")=(wrap \"klass\" 10)))" +
                    "(= var1 (request VirtualMachine ClassesBySignature (\"signature\")=(get var10 \"extension\"))))"));

            // IdSizes and Classes request
            assertEquals(tp.vm.getIdSizesReply(), tp.client.query(idSizesRequest).withNewId(0));
            assertEquals(1, tp.clientTunnel.getReplyCacheSize());

            assertEquals(classesReply, tp.client.query(classesRequest.withNewId(1)));
            assertEquals(2, tp.clientTunnel.getReplyCacheSize());
        }
    }

    @Test
    @SneakyThrows
    public void testEvaluateWithEventTunnelTunnel() {
        var classesReply = new ClassesBySignatureReply(0, new ListValue<>(Type.OBJECT));
        var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
        var events = new Events(0, wrap((byte) 2), new ListValue<>(new ClassUnload(wrap(0), wrap("sig"))));
        var death = new VMDeath(wrap(0));
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
            tp.vm.sendEvent(100, death); // should break the partition at the client tunnel
            assertEquals(death, tp.client.readEvents().events.get(0));
            assertEqualsTimeout(1, () -> tp.serverTunnel.getState().getProgramCache().size());
            tp.vm.sendEvent(events);
            assertEquals(events, tp.client.readEvents());
            assertEquals(0, tp.clientTunnel.getState().getProgramCache().getClientPrograms().size());
            //assertFalse(tp.serverTunnel.getState().hasUnfinishedRequests());
            assertFalse(tp.clientTunnel.getState().hasUnfinishedRequests());
            assertEqualsTimeout(1, tp.serverTunnel::getProgramCacheSize, Duration.ofSeconds(1));
            assertEquals("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                            "\"kind\")=(wrap \"string\" \"ClassUnload\") (\"events\" 0 \"requestID\")=(wrap \"int\" " +
                            "0) (\"events\" 0 \"signature\")=(wrap \"string\" \"sig\"))) (= var0 (request " +
                            "VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" \"test\"))))",
                    tp.serverTunnel.getState().getCachedProgram(events).toString());
            // the event should have caused the usage of this program, resulting in another Classes request to the vm
            assertEquals(List.of(classesRequest, classesRequest), tp.vm.getReceivedRequests());
            assertEquals(new ReplyCache(tp.vm.vm), tp.serverTunnel.getState().getReplyCache());
            assertEquals(new ReplyCache(tp.vm.vm, List.of(p(classesRequest, classesReply))),
                    tp.clientTunnel.getState().getReplyCache());
            assertEquals(classesReply, tp.client.query(classesRequest));
            assertEquals(List.of(classesRequest, classesRequest), tp.vm.getReceivedRequests());
            var classUnloadEvent = new ThreadDeath(wrap(1), Reference.thread(1));
            tp.vm.sendEvent(0, classUnloadEvent); // invalidate the caches
            assertEqualsTimeout(0, () -> tp.serverTunnel.getState().getReplyCache().size(), Duration.ofSeconds(1));
            assertEqualsTimeout(new ReplyCache(tp.vm.vm), () -> tp.clientTunnel.getState().getReplyCache());
            assertEquals(new Events(0, wrap((byte) 2), new ListValue<>(classUnloadEvent)), tp.client.readEvents());
        }
    }

    @Test
    @SneakyThrows
    public void testTunnelEventsHandlingInPartition() {
        var classesReply = new ClassesBySignatureReply(0, new ListValue<>(Type.OBJECT));
        var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
        var events = new Events(0, wrap((byte) 2), new ListValue<>(new ClassUnload(wrap(0), wrap("sig"))));
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
            @Override
            public Reply visit(ClassesBySignatureRequest classesBySignatureRequest) {
                return classesReply;
            }
        })) {
            var program = new Program(EventsCall.create(events), List.of(new AssignmentStatement(AST.ident("var0"),
                    RequestCall.create(classesRequest))));
            // store an artificial program in the program cache
            tp.serverTunnel.getState().getProgramCache().accept(program);
            tp.vm.sendEvent(events);
            assertEquals(events, tp.client.readEvents());
            // we got the event and stored the additional packets in the cache
            // we now run the request
            assertEquals(classesReply, tp.client.query(classesRequest));
            // request should be queried from the cache
            assertEquals(List.of(classesRequest), tp.vm.getReceivedRequests());
            // we now do another request
            assertEquals(tp.vm.getIdSizesReply(), tp.client.query(new IDSizesRequest(0)));
            // we have to abort the partition on client and server tunnel
            tp.vm.sendEvent(10, new VMDeath(wrap(1)));
            tp.client.readEvents();
            // the old program has been overridden by the new one in the server cache
            assertEquals(1, tp.serverTunnel.getState().getProgramCache().size());

            assertEqualsTimeout(new Program(EventsCall.create(events), List.of(new AssignmentStatement(AST.ident(
                    "var0"),
                            RequestCall.create(classesRequest)), new AssignmentStatement(AST.ident("var1"),
                            RequestCall.create(new IDSizesRequest(0))))),
                    () -> tp.serverTunnel.getState().getProgramCache().get(events).get());
            assertEquals(1, tp.clientTunnel.getState().getProgramCache().size());
        }
    }

    @Test
    @SneakyThrows
    public void testTunnelBasicProgramAndPartition() {
        var classesReply = new ClassesBySignatureReply(0, new ListValue<>(Type.OBJECT));
        var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
        var events = new Events(0, wrap((byte) 2), new ListValue<>(new ClassUnload(wrap(0), wrap("sig"))));
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {
            @Override
            public Reply visit(ClassesBySignatureRequest classesBySignatureRequest) {
                return classesReply;
            }

            @Override
            public Reply visit(SetRequest setRequest) {
                return new SetReply(0, wrap(1));
            }

            @Override
            public Reply visit(VersionRequest versionRequest) {
                return new VersionReply(0, wrap(""), wrap(0), wrap(1), wrap(""), wrap(""));
            }
        })) {
            // store an artificial program in the program cache
            tp.clientTunnel.getState().getProgramCache().accept(Program.parse(
                    "((= cause (request EventRequest Set (\"eventKind\")" +
                            "=(wrap \"byte\" 9) (\"suspendPolicy\")=(wrap \"byte\" 0))) " +
                            "(= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 9) " +
                            "(\"suspendPolicy\")=(wrap \"byte\" 0))) (= var1 (request VirtualMachine Version)))"));
            // send the set request and trigger the program execution
            assertEquals(new SetReply(0, wrap(1)),
                    tp.client.query(new SetRequest(0, wrap((byte) 9), wrap((byte) 0), new ListValue<>(Type.OBJECT))));
            assertEquals(tp.vm.getIdSizesReply(), tp.client.query(new IDSizesRequest(0)));
            tp.vm.sendEvent(10, new VMDeath(wrap(2))); // trigger the partition
            assertEqualsTimeout(2,
                    () -> tp.clientTunnel.getState().getClientPartitioner().getCurrentPartition().size());
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    @SneakyThrows
    public void testTunnelBasicProgramAndRequestProgramEvaluationFail(boolean sendEventMidway) {
        var classesReply = new ClassesBySignatureReply(0, new ListValue<>(Type.OBJECT));
        var classesRequest = new ClassesBySignatureRequest(0, wrap("test"));
        var events = new Events(0, wrap((byte) 2), new ListValue<>(new ClassUnload(wrap(0), wrap("sig"))));
        var versionReply = new VersionReply(0, wrap(""), wrap(0), wrap(1), wrap(""), wrap(""));
        var sourceDebugErrorCode = Error.ABSENT_INFORMATION;
        var event = new ClassUnload(wrap(2), wrap("x"));
        VMTunnelTunnelClientTuple[] tp2 = new VMTunnelTunnelClientTuple[]{null};
        try (var tp = VMTunnelTunnelClientTuple.create(new ReturningRequestVisitor<>() {

            @Override
            public Reply visit(ClassesBySignatureRequest classesBySignatureRequest) {
                return classesReply;
            }

            @Override
            public Reply visit(SetRequest setRequest) {
                return new SetReply(0, wrap(1));
            }

            @Override
            public Reply visit(VersionRequest versionRequest) {
                return versionReply;
            }

            @Override
            public Reply visit(NameRequest nameRequest) {
                return new NameReply(0, wrap(""));
            }

            // only send the event on the first request (during the program evaluation)
            boolean first = true;

            @Override
            public Reply visit(SourceDebugExtensionRequest sourceDebugExtensionRequest) {
                if (sendEventMidway && first) {
                    tp2[0].vm.sendEvent(10, event);
                    first = false;
                }
                throw new ErrorCodeException(sourceDebugErrorCode);
            }
        })) {
            tp2[0] = tp;
            // store an artificial program in the program cache
            // the program cannot be merged with synthesized programs, as it does not conform to certain invariants
            // but this is not a problem for the test
            tp.clientTunnel.getState().getProgramCache().accept(Program.parse(
                    "((= cause (request EventRequest Set (\"eventKind\")" +
                            "=(wrap \"byte\" 9) (\"suspendPolicy\")=(wrap \"byte\" 0))) " +
                            "(= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 9) " +
                            "(\"suspendPolicy\")=(wrap \"byte\" 0))) " +
                            "(= var1 (request VirtualMachine Version))" +
                            "(= var2 (request ReferenceType SourceDebugExtension (\"refType\")=(wrap " +
                            "\"klass\" 10)))" +
                            "(= var3 (request VirtualMachine ClassesBySignature (\"signature\")=(get var2 " +
                            "\"extension\")))" +
                            "(= var3 (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" " +
                            "\"s\")))" +
                            ")"));
            assertEquals(1, tp.clientTunnel.getState().getProgramCache().size());
            var nameRequest = new NameRequest(0, threadGroup(1));
            tp.client.query(nameRequest);
            // partition on client now should consist of this request
            assertEquals(List.of(nameRequest), tp.vm.getReceivedRequests());
            assertEquals(1, tp.clientTunnel.getState().getClientPartitioner().getCurrentPartition().size());
            var setRequest = new SetRequest(0, wrap((byte) 9), wrap((byte) 0), new ListValue<>(Type.OBJECT));
            Thread.sleep(10);
            // send the set request and trigger the program execution
            if (sendEventMidway) {
                assertEquals(event, tp.client.queryEvent(setRequest));
            } else {
                assertEquals(new SetReply(0, wrap(1)), tp.client.query(setRequest));
            }
            // we expect that the VM receives the SetRequest, the VersionRequest,
            // the SourceDebugExtensionRequest and the last ClassBySignatureRequest
            assertEqualsTimeout(5, () -> {
                System.out.println(tp.vm.getAllReceivedRequests());
                return tp.vm.getAllReceivedRequests().size();
            }, Duration.ofHours(1));
            // we expect than that this is propagated into the reply cache
            // this cache already contains the initial name request
            assertEqualsTimeout(sendEventMidway ? 1 : 4, () -> {
                System.out.println(tp.clientTunnel.getState().getReplyCache().getCachedRequests());
                return tp.clientTunnel.getState().getReplyCache().size();
            }, Duration.ofHours(1));
            // we expect that the current partition only of the set request
            assertEqualsTimeout(1, () ->
                            tp.clientTunnel.getState().getClientPartitioner().getCurrentPartition() != null ?
                                    tp.clientTunnel.getState().getClientPartitioner().getCurrentPartition().size() : -1,
                    Duration.ofHours(1));
            assertEquals(versionReply, tp.client.query(new VersionRequest(10)));
            assertEquals(5, tp.vm.getAllReceivedRequests().size());
            var sourceDebugRequest = new SourceDebugExtensionRequest(11, klass(10));
            assertEquals(new ReplyOrError<>(11, SourceDebugExtensionRequest.METADATA, sourceDebugErrorCode),
                    tp.client.queryWithError(sourceDebugRequest));
            // this should have been cached for non events, but with events, it should not be
            assertEquals(sendEventMidway ? 6 : 5, tp.vm.getAllReceivedRequests().size());
            var classesBySignatureRequest = new ClassesBySignatureRequest(12, wrap("s"));
            tp.client.query(classesBySignatureRequest);
            assertEquals(sendEventMidway ? 7 : 5, tp.vm.getAllReceivedRequests().size());
            var classesBySignatureRequest2 = new ClassesBySignatureRequest(13, wrap("sig"));
            tp.client.query(classesBySignatureRequest2); // this one should not be cached
            assertEquals(sendEventMidway ? 8 : 6, tp.vm.getAllReceivedRequests().size());
            assertEquals(5, tp.clientTunnel.getState().getClientPartitioner().getCurrentPartition().size());
            assertEquals(List.of(setRequest, new VersionRequest(10), sourceDebugRequest,
                            classesBySignatureRequest, classesBySignatureRequest2),
                    tp.clientTunnel.getState().getClientPartitioner().getCurrentPartition().getRequests());

            tp.vm.sendEvent(10, new VMDeath(wrap(2))); // trigger the partition
            assertEqualsTimeout("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 9) " +
                            "(\"suspendPolicy\")=(wrap \"byte\" 0)))\n" +
                            "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 9) " +
                            "(\"suspendPolicy\")=(wrap \"byte\" 0)))\n" +
                            "  (= var1 (request VirtualMachine Version))\n" +
                            "  (= var2 (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" " +
                            "\"sig\")))\n" +
                            "  (= var3 (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" " +
                            "\"s\")))\n" +
                            "  (= var4 (request ReferenceType SourceDebugExtension (\"refType\")=(wrap \"klass\" 10))" +
                            "))",
                    () -> tp.clientTunnel.getState().getProgramCache().get(setRequest).get().toPrettyString(),
                    Duration.ofSeconds(1));
        }
    }

    private void assertEqualsTimeout(Object expected, SupplierWithError<Object> actual) {
        assertEqualsTimeout(expected, actual, Duration.ofMillis(100));
    }

    private void assertEqualsTimeout(Object expected, SupplierWithError<Object> actual, Duration timeout) {
        Assertions.assertTimeoutPreemptively(timeout, () -> {
            while (!expected.equals(actual.call())) Thread.yield();
        }, () -> "expected: " + expected + ", actual: " + actual.call());
    }
}
