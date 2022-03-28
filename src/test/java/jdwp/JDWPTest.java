package jdwp;

import com.sun.jdi.ArrayType;
import com.sun.tools.attach.VirtualMachine;
import jdwp.JDWP.ThreadReference.NameReply;
import jdwp.JDWP.ThreadReference.NameRequest;
import jdwp.PrimitiveValue.StringValue;
import jdwp.oracle.JDWP;
import jdwp.oracle.JDWP.ThreadReference;
import jdwp.oracle.JDWP.ThreadReference.Name;
import jdwp.oracle.Packet;
import jdwp.oracle.PacketStream;
import jdwp.oracle.ThreadReferenceImpl;
import jdwp.oracle.VirtualMachineImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class JDWPTest {

    private static VirtualMachineImpl ovm = new VirtualMachineImpl(null, null, null, 1);
    private static VM vm = new VM(0);

    @Test
    public void testThreadReferenceNameRequestParsing() throws IOException {
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
    public void testThreadReferenceNameResponseParsing() throws IOException {
        // can our generated response be read by the oracle?
        var name = PrimitiveValue.wrap("Thread");
        var t = new jdwp.JDWP.ThreadReference.NameReply(0, name);
        var producedPacket = t.toPacket(vm);
        var oracleName = new JDWP.ThreadReference.Name(ovm, oraclePacketStream(producedPacket));
        assertEquals(name.value, oracleName.threadName);
        assertEquals(oracleName.threadName, ((StringValue)t.get("threadName")).value);

        // can we parse our package?
        assertEquals(name, NameReply.parse(vm, producedPacket).getReply().threadName);
    }

    @Test
    public void testParsingErrorPackage() throws IOException {
        // can we handle errors properly (we only have to this for a single test case)
        var reply = new ReplyOrError<jdwp.JDWP.ThreadReference.NameReply>(0, (short) 10);
        assertEquals(10, oraclePacketStream(reply.toPacket(vm)).pkt.errorCode);

        // can we parse our package?
        assertEquals(10, NameReply.parse(vm, reply.toPacket(vm)).getErrorCode());
    }

    static PacketStream oraclePacketStream(jdwp.Packet packet) throws IOException {
        return new PacketStream(ovm, Packet.fromByteArray(packet.toByteArray()));
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