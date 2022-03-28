package jdwp;

import jdwp.JDWP.ThreadReference.NameReply;
import jdwp.JDWP.ThreadReference.NameRequest;
import jdwp.JDWP.VirtualMachine.ClassesBySignatureReply;
import jdwp.JDWP.VirtualMachine.ClassesBySignatureReply.ClassInfo;
import jdwp.JDWP.VirtualMachine.ClassesBySignatureRequest;
import jdwp.JDWP.VirtualMachine.VersionReply;
import jdwp.JDWP.VirtualMachine.VersionRequest;
import jdwp.PrimitiveValue.StringValue;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.oracle.JDWP;
import jdwp.oracle.JDWP.ThreadReference;
import jdwp.oracle.JDWP.ThreadReference.Name;
import jdwp.oracle.JDWP.VirtualMachine.ClassesBySignature;
import jdwp.oracle.JDWP.VirtualMachine.Version;
import jdwp.oracle.Packet;
import jdwp.oracle.PacketStream;
import jdwp.oracle.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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
        testReplyParsing(new jdwp.JDWP.VirtualMachine.VersionReply(0,
                        wrap("d"), wrap(1), wrap(2), wrap("v"), wrap("w")),
                Version::new,
                (r, o) -> {
                    assertEquals("d", o.description);
                    assertEquals(1, o.jdwpMajor);
                    assertEquals(2, o.jdwpMinor);
                    assertEquals("v", o.vmVersion);
                    assertEquals("w", o.vmName);
                }, VersionReply::parse);
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
        testReplyParsing(new jdwp.JDWP.VirtualMachine.ClassesBySignatureReply(0,
                        new ListValue<>(Type.OBJECT, new ArrayList<>())),
                ClassesBySignature::new,
                (r, o) -> {
                    assertEquals(0, r.classes.size());
                    assertEquals(0, o.classes.length);
                }, ClassesBySignatureReply::parse);
        // single
        testReplyParsing(new jdwp.JDWP.VirtualMachine.ClassesBySignatureReply(0,
                        new ListValue<>(Type.OBJECT, List.of(new ClassInfo(wrap((byte) 1), Reference.klass(100), wrap(101))))),
                ClassesBySignature::new,
                (r, o) -> {
                    assertEquals(1, r.classes.size());
                    assertEquals(1, o.classes.length);
                    assertEquals(1, o.classes[0].refTypeTag);
                    assertEquals(100, o.classes[0].typeID);
                    assertEquals(101, o.classes[0].status);
                }, ClassesBySignatureReply::parse);
        // several
        testReplyParsing(new jdwp.JDWP.VirtualMachine.ClassesBySignatureReply(0,
                        new ListValue<>(Type.OBJECT, IntStream.range(0, 5).mapToObj(i -> new ClassInfo(wrap((byte) (1 + i)), Reference.klass(100 + i), wrap(101 + i))).collect(Collectors.toList()))),
                ClassesBySignature::new,
                (r, o) -> {
                    assertEquals(5, r.classes.size());
                    assertEquals(5, o.classes.length);
                    for (int i = 0; i < 5; i++) {
                        assertEquals(1 + i, o.classes[i].refTypeTag);
                        assertEquals(100 + i, o.classes[i].typeID);
                        assertEquals(101 + i, o.classes[i].status);
                    }
                }, ClassesBySignatureReply::parse);
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
        testReplyParsing(new jdwp.JDWP.ThreadReference.NameReply(0, wrap("a")),
                ThreadReference.Name::new,
                (r, o) -> {
                    assertEquals("a", o.threadName);
                    assertEquals("a", ((StringValue)r.get("threadName")).value);
                }, NameReply::parse);
    }

    <O, R extends Reply> void testReplyParsing(R reply,
                              BiFunction<VirtualMachineImpl, PacketStream, O> oracleSupplier,
                              BiConsumer<R, O> checker, BiFunction<VM, jdwp.Packet, ReplyOrError<R>> replyParser) {
        var producedPacket = reply.toPacket(vm);
        var oracleName = oracleSupplier.apply(ovm, oraclePacketStream(producedPacket));
        checker.accept(reply, oracleName);

        // can we parse our package?
        var rreply = replyParser.apply(vm, producedPacket).getReply();
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
}