package jdwp;

import com.sun.jdi.ArrayType;
import jdwp.JDWP.ThreadReference.NameReply;
import jdwp.JDWP.ThreadReference.NameRequest;
import jdwp.JDWP.VirtualMachine;
import jdwp.JDWP.VirtualMachine.VersionReply;
import jdwp.JDWP.VirtualMachine.VersionRequest;
import jdwp.PrimitiveValue.StringValue;
import jdwp.oracle.JDWP;
import jdwp.oracle.JDWP.ThreadReference;
import jdwp.oracle.JDWP.ThreadReference.Name;
import jdwp.oracle.JDWP.VirtualMachine.Version;
import jdwp.oracle.Packet;
import jdwp.oracle.PacketStream;
import jdwp.oracle.ThreadReferenceImpl;
import jdwp.oracle.VirtualMachineImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Serializable;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.*;

class JDWPTest {

    private static VirtualMachineImpl ovm = new VirtualMachineImpl(null, null, null, 1);
    private static VM vm = new VM(0);

    @Test
    public void testVirtualMachine_VersionRequestParsing() throws IOException {
        // can we generate the same package as the oracle
        var t = new ThreadReferenceImpl();
        t.ref = 100;
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
    public void testVirtualMachine_VersionReplyParsing() throws IOException {
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
    public void testThreadReference_NameReplyParsing() throws IOException {
        testReplyParsing(new jdwp.JDWP.ThreadReference.NameReply(0, wrap("a")),
                ThreadReference.Name::new,
                (r, o) -> {
                    assertEquals("a", o.threadName);
                    assertEquals("a", ((StringValue)r.get("threadName")).value);
                }, NameReply::parse);
    }

    <O, R extends Reply> void testReplyParsing(R reply,
                              BiFunction<VirtualMachineImpl, PacketStream, O> oracleSupplier,
                              BiConsumer<R, O> checker, BiFunction<VM, jdwp.Packet, ReplyOrError<R>> replyParser) throws IOException {
        var producedPacket = reply.toPacket(vm);
        var oracleName = oracleSupplier.apply(ovm, oraclePacketStream(producedPacket));
        checker.accept(reply, oracleName);

        // can we parse our package?
        var rreply = replyParser.apply(vm, producedPacket).getReply();
        assertEquals(reply, rreply);
    }

    @Test
    public void testParsingErrorPackage() throws IOException {
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

    /**
     * Simple implementation of an immutable pair
     */
    static class Pair<T, V> implements Serializable {
        public final T first;

        public final V second;

        public Pair(T first, V second) {
            this.first = first;
            this.second = second;
        }

        @Override
        public int hashCode() {
            return first.hashCode() ^ second.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof Pair)){
                return false;
            }
            Pair pair = (Pair)obj;
            return pair.first == this.first && pair.second == this.second;
        }

        public Stream<T> firstStream(){
            return Stream.of(first);
        }

        @Override
        public String toString() {
            return String.format("(%s,%s)", first, second);
        }

        public T first(){
            return first;
        }
    }

    static <S, T> Pair<S, T> p(S s, T t){
        return new Pair<>(s,t);
    }
}