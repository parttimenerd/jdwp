package jdwp;

import jdwp.JDWP.ThreadReference.NameReply;
import jdwp.JDWP.ThreadReference.NameRequest;
import jdwp.JDWP.VirtualMachine.*;
import jdwp.JDWP.VirtualMachine.ClassesBySignatureReply.ClassInfo;
import jdwp.PrimitiveValue.StringValue;
import jdwp.Value.*;
import jdwp.oracle.JDWP;
import jdwp.oracle.JDWP.ThreadReference.Name;
import jdwp.oracle.JDWP.VirtualMachine.*;
import jdwp.oracle.Packet;
import jdwp.oracle.PacketStream;
import jdwp.oracle.*;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.*;

class JDWPTest {

    private static final VirtualMachineImpl ovm = new VirtualMachineImpl(null, null, null, 1);
    private static final VM vm = new VM(0);

    @Test
    public void testVirtualMachine_VersionRequestParsing() throws IOException {
        // can we generate the same package as the oracle
        var oraclePacket = JDWP.VirtualMachine.Version.enqueueCommand(ovm).finishedPacket;
        var packet = new jdwp.JDWP.VirtualMachine.VersionRequest(0, (short) 0).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                jdwp.JDWP.VirtualMachine.VersionRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(0, readPkg.id);

        // test getKeys()
        assertEquals(0, readPkg.getKeys().size());

        // test JDWP.parse
        assertInstanceOf(VersionRequest.class, jdwp.JDWP.parse(vm, packet));
    }

    @Test
    public void testVirtualMachine_VersionReplyParsing() {
        testReplyParsing(Version::new,
                new jdwp.JDWP.VirtualMachine.VersionReply(0,
                        wrap("d"), wrap(1), wrap(2), wrap("v"), wrap("w")),
                (o, r) -> {
                    assertEquals("d", o.description);
                    assertEquals(1, o.jdwpMajor);
                    assertEquals(2, o.jdwpMinor);
                    assertEquals("v", o.vmVersion);
                    assertEquals("w", o.vmName);
                });
    }

    @Test
    public void testVirtualMachine_ClassBySignatureRequestParsing() throws IOException {
        // can we generate the same package as the oracle
        var oraclePacket = JDWP.VirtualMachine.ClassesBySignature.enqueueCommand(ovm, "sig").finishedPacket;
        var packet = new jdwp.JDWP.VirtualMachine.ClassesBySignatureRequest(0, wrap("sig")).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                jdwp.JDWP.VirtualMachine.ClassesBySignatureRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(0, readPkg.id);
        assertEquals("sig", readPkg.signature.value);

        // test getKeys()
        assertEquals(1, readPkg.getKeys().size());

        // test JDWP.parse
        assertInstanceOf(ClassesBySignatureRequest.class, jdwp.JDWP.parse(vm, packet));
    }

    @Test
    public void testVirtualMachine_ClassBySignatureReplyParsing() {
        // empty
        testReplyParsing(ClassesBySignature::new,
                new jdwp.JDWP.VirtualMachine.ClassesBySignatureReply(0,
                        new ListValue<>(Type.OBJECT, new ArrayList<>())),
                (o, r) -> {
                    assertEquals(0, r.classes.size());
                    assertEquals(0, o.classes.length);
                });
        // single
        testReplyParsing(ClassesBySignature::new,
                new jdwp.JDWP.VirtualMachine.ClassesBySignatureReply(0,
                        new ListValue<>(Type.OBJECT, List.of(new ClassInfo(wrap((byte) 1), Reference.klass(100), wrap(101))))),
                (o, r) -> {
                    assertEquals(1, r.classes.size());
                    assertEquals(1, o.classes.length);
                    assertEquals(1, o.classes[0].refTypeTag);
                    assertEquals(100, o.classes[0].typeID);
                    assertEquals(101, o.classes[0].status);
                });
        // several
        testReplyParsing(ClassesBySignature::new,
                new jdwp.JDWP.VirtualMachine.ClassesBySignatureReply(0,
                        new ListValue<>(Type.OBJECT, IntStream.range(0, 5).mapToObj(i -> new ClassInfo(wrap((byte) (1 + i)), Reference.klass(100 + i), wrap(101 + i))).collect(Collectors.toList()))),
                (o, r) -> {
                    assertEquals(5, r.classes.size());
                    assertEquals(5, o.classes.length);
                    for (int i = 0; i < 5; i++) {
                        assertEquals(1 + i, o.classes[i].refTypeTag);
                        assertEquals(100 + i, o.classes[i].typeID);
                        assertEquals(101 + i, o.classes[i].status);
                    }
                });
    }

    @Test
    public void testVirtualMachine_AllClassesRequestParsing() throws IOException {
        // can we generate the same package as the oracle
        var oraclePacket = JDWP.VirtualMachine.AllClasses.enqueueCommand(ovm).finishedPacket;
        var packet = new jdwp.JDWP.VirtualMachine.AllClassesRequest(0, (short) 0).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                jdwp.JDWP.VirtualMachine.AllClassesRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(0, readPkg.id);

        // test getKeys()
        assertEquals(0, readPkg.getKeys().size());

        // test JDWP.parse
        assertInstanceOf(AllClassesRequest.class, jdwp.JDWP.parse(vm, packet));
    }

    @Test
    public void testVirtualMachine_AllClassesReplyParsing() {
        // several
        testReplyParsing(AllClasses::new,
                new jdwp.JDWP.VirtualMachine.AllClassesReply(0,
                        new ListValue<>(Type.OBJECT, IntStream.range(0, 5).mapToObj(i -> new AllClassesReply.ClassInfo(wrap((byte) (1 + i)), Reference.klass(100 + i), wrap("blabla" + i), wrap(101 + i))).collect(Collectors.toList()))),
                (o, r) -> {
                    assertEquals(5, r.classes.size());
                    assertEquals(5, o.classes.length);
                    for (int i = 0; i < 5; i++) {
                        assertEquals2((byte)(1 + i), o.classes[i].refTypeTag, r.classes.get(i).refTypeTag);
                        assertEquals2(100L + i, o.classes[i].typeID, r.classes.get(i).typeID);
                        assertEquals2(101 + i, o.classes[i].status, r.classes.get(i).status);
                        assertEquals2("blabla" + i, o.classes[i].signature, r.classes.get(i).signature);
                    }
                });
    }

    @Test
    public void testVirtualMachine_AllThreadsRequestParsing() throws IOException {
        testBasicRequestParsing(AllThreads.enqueueCommand(ovm), new AllThreadsRequest(0));
    }

    @Test
    public void testVirtualMachine_AllThreadsReplyParsing() {
        testReplyParsing(AllThreads::new,
                new AllThreadsReply(0, new ListValue<>(Reference.thread(10))),
                (o, r) -> {
                    assertEquals(1, r.threads.size());
                    assertEquals(1, o.threads.length);
                    assertEquals(10, o.threads[0].ref);
                });
    }

    @Test
    public void testVirtualMachine_DisposeRequestParsing() throws IOException {
        testBasicRequestParsing(Dispose.enqueueCommand(ovm), new DisposeRequest(0));
        assertFalse(new DisposeRequest(0).onlyReads());
    }

    @Test
    public void testVirtualMachine_DisposeReplyParsing() {
        testReplyParsing(Dispose::new,
                new DisposeReply(0),
                (o, r) -> {
                    assertEquals(0, r.getKeys().size());
                });
    }

    @Test
    public void testVirtualMachine_TopLevelThreadGroupsRequestParsing() throws IOException {
        testBasicRequestParsing(TopLevelThreadGroups.enqueueCommand(ovm), new TopLevelThreadGroupsRequest(0));
    }

    @Test
    public void testVirtualMachine_TopLevelThreadGroupsReplyParsing() {
        testReplyParsing(TopLevelThreadGroups::new,
                new TopLevelThreadGroupsReply(0, new ListValue<>(Reference.threadGroup(10))),
                (o, r) -> {
                    assertEquals(1, r.groups.size());
                    assertEquals(1, o.groups.length);
                    assertEquals(10, o.groups[0].ref);
                });
    }

    @Test
    public void testThreadReference_NameRequestParsing() throws IOException {
        // can we generate the same package as the oracle
        var t = new ThreadReferenceImpl();
        t.ref = 100;
        var oraclePacket = Name.enqueueCommand(ovm, t).finishedPacket;
        var packet = new jdwp.JDWP.ThreadReference.NameRequest(0, (short) 0, Reference.thread(100)).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                jdwp.JDWP.ThreadReference.NameRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(100, readPkg.thread.value);
        assertEquals(0, readPkg.id);

        // test get()
        assertEquals(Reference.thread(100), readPkg.get("thread"));

        // test JDWP.parse
        assertInstanceOf(NameRequest.class, jdwp.JDWP.parse(vm, packet));
    }

    @Test
    public void testThreadReference_NameReplyParsing() {
        testReplyParsing(Name::new, new jdwp.JDWP.ThreadReference.NameReply(0, wrap("a")),
                (o, r) -> {
                    assertEquals("a", o.threadName);
                    assertEquals("a", ((StringValue)r.get("threadName")).value);
                });
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    <O, R extends Reply> void testReplyParsing(BiFunction<VirtualMachineImpl, PacketStream, O> oracleSupplier,
                                               R reply,
                                               BiConsumer<O, R> checker) {
        var producedPacket = reply.toPacket(vm);
        var oracleObj = oracleSupplier.apply(ovm, oraclePacketStream(producedPacket));
        checker.accept(oracleObj, reply);

        // can we parse our package?
        var rreply = ((ReplyOrError<R>)reply.getClass().getMethod("parse", VM.class, jdwp.Packet.class)
                .invoke(null, vm, producedPacket)).getReply();
        assertEquals(reply, rreply);
    }

    @Test
    public void testParsingErrorPackage() {
        // can we handle errors properly (we only have to this for a single test case)
        var reply = new ReplyOrError<jdwp.JDWP.ThreadReference.NameReply>(0, (short) 10);
        assertEquals(10, oraclePacketStream(reply.toPacket(vm)).pkt.errorCode);

        // can we parse our package?
        assertEquals(10, NameReply.parse(vm, reply.toPacket(vm)).getErrorCode());
    }

    /**
     * Check that both packages are equal and that we can parse the package
     *
     * Should only be used for command replies that are similar to better tested ones
     */
    @SuppressWarnings("unchecked")
    public <R extends WalkableValue<String> & Request<?>>
        void testBasicRequestParsing(PacketStream expected, R request) throws IOException {
        // can we generate the same package as the oracle
        var oraclePacket = expected.finishedPacket;
        var packet = request.toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        R readPkg =
                null;
        try {
            readPkg = (R)request.getClass().getMethod("parse", VM.class, jdwp.Packet.class)
                    .invoke(null, vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            e.printStackTrace();
        }
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(0, readPkg.getId());
        assertEquals(0, readPkg.getFlags());

        // test getKeys()
        assertEquals(request.getKeyStream().count(), readPkg.getKeyStream().count());

        // test JDWP.parse
        assertInstanceOf(request.getClass(), jdwp.JDWP.parse(vm, packet));
    }

    static PacketStream oraclePacketStream(jdwp.Packet packet) {
        try {
            return new PacketStream(ovm, Packet.fromByteArray(packet.toByteArray()));
        } catch (IOException e) {
            return null;
        }
    }

    static void assertPacketsEqual(PacketStream expectedPacket, jdwp.ParsedPacket actualPacket) {
        assertPacketsEqual(expectedPacket.finishedPacket, actualPacket.toPacket(vm));
    }

    static void assertPacketsEqual(Packet expectedPacket, jdwp.Packet actualPacket) {
        assertEquals(expectedPacket.id, actualPacket.id);
        assertArrayEquals(expectedPacket.toByteArray(), actualPacket.toByteArray());
    }

    static void assertPacketsEqual(jdwp.Packet expectedPacket, jdwp.Packet actualPacket) {
        assertEquals(expectedPacket.id, actualPacket.id);
        assertArrayEquals(expectedPacket.toByteArray(), actualPacket.toByteArray());
    }

    static <T> void assertEquals2(T expected, T oracle, BasicValue<T> jdwp) {
        assertEquals(expected, oracle);
        assertEquals(expected, jdwp.value);
    }
}