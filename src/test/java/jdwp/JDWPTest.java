package jdwp;

import jdwp.JDWP.ClassType.*;
import jdwp.JDWP.ThreadReference.NameReply;
import jdwp.JDWP.ThreadReference.NameRequest;
import jdwp.JDWP.VirtualMachine.*;
import jdwp.JDWP.VirtualMachine.ClassesBySignatureReply.ClassInfo;
import jdwp.PrimitiveValue.StringValue;
import jdwp.VM.NoTagPresentException;
import jdwp.Value.*;
import jdwp.oracle.JDWP;
import jdwp.oracle.JDWP.ClassType.InvokeMethod;
import jdwp.oracle.JDWP.ClassType.Superclass;
import jdwp.oracle.JDWP.ThreadReference.Name;
import jdwp.oracle.JDWP.VirtualMachine.*;
import jdwp.oracle.Packet;
import jdwp.oracle.PacketStream;
import jdwp.oracle.*;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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

    @BeforeEach
    void clean() {
        vm.reset();
    }

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
        var oraclePs = JDWP.VirtualMachine.AllClasses.enqueueCommand(ovm);
        oraclePs.pkt.id = 1;
        var oraclePacket = oraclePs.finishedPacket;
        var packet = new jdwp.JDWP.VirtualMachine.AllClassesRequest(1, (short) 0).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                jdwp.JDWP.VirtualMachine.AllClassesRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(1, readPkg.id);

        // test getKeys()
        assertEquals(0, readPkg.getKeys().size());

        // test JDWP.parse
        assertInstanceOf(AllClassesRequest.class, jdwp.JDWP.parse(vm, packet));

        // test parsing the related reply
        var reply = new AllClassesReply(1,
                new ListValue<>(new AllClassesReply.ClassInfo(wrap((byte)1), Reference.klass(1), wrap("bla"), wrap(1))));
        var preply = readPkg.parseReply(new jdwp.PacketStream(vm, reply.toPacket(vm))).getReply();
        assertEquals(reply.classes.size(), preply.classes.size());
        assertEquals(reply.classes.get(0).signature, preply.classes.get(0).signature);

        // test parsing a related reply with different id
        Assertions.assertThrows(Reply.IdMismatchException.class, () -> {
            readPkg.parseReply(new jdwp.PacketStream(vm, new AllClassesReply(10,
                    new ListValue<>(Type.OBJECT)).toPacket(vm)));
        });
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
    public void testVirtualMachine_AllThreadsRequestParsing() {
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
    public void testVirtualMachine_DisposeRequestParsing() {
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
    public void testVirtualMachine_TopLevelThreadGroupsRequestParsing() {
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
    public void testVirtualMachine_IDSizesRequestParsing() {
        testBasicRequestParsing(IDSizes.enqueueCommand(ovm), new IDSizesRequest(0));
    }

    @Test
    public void testVirtualMachine_IDSizesReplyParsing() {
        testReplyParsing(IDSizes::new,
                new IDSizesReply(1011, wrap(9), wrap(1), wrap(10), wrap(8), wrap(10)),
                (o, r) -> {
                    assertEquals2(10, o.frameIDSize, r.frameIDSize);
                    assertEquals2(9, o.fieldIDSize, r.fieldIDSize);
                });
    }

    @Test
    public void testVirtualMachine_SuspendResumeExitRequestParsing() {
        Assertions.assertAll(
                () -> testBasicRequestParsing(Suspend.enqueueCommand(ovm), new SuspendRequest(0)),
                () -> testBasicRequestParsing(Resume.enqueueCommand(ovm), new ResumeRequest(0)),
                () -> testBasicRequestParsing(Exit.enqueueCommand(ovm, 10), new ExitRequest(0, wrap(10)))
                );
    }

    @Test
    public void testVirtualMachine_SuspendResumeExitReplyParsing() {
        BiConsumer<Object, CombinedValue> empty = (o, r) -> assertEquals(0, r.getKeyStream().count());
        Assertions.assertAll(
                () -> testReplyParsing(Suspend::new, new SuspendReply(0), empty),
                () -> testReplyParsing(Resume::new, new ResumeReply(0), empty),
                () -> testReplyParsing(Exit::new, new ExitReply(10), empty)
        );
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

    @Test
    public void testClassType_SuperclassRequestParsing() {
        testBasicRequestParsing(Superclass.enqueueCommand(ovm, new ClassTypeImpl(ovm, 10)),
                new SuperclassRequest(0, Reference.classType(10)));
    }

    @Test
    public void testClassType_SuperclassReplyParsing() {
        testReplyParsing(Superclass::new,
                new SuperclassReply(0, Reference.classType(10)),
                (o, r) -> {
                    assertEquals2(10L, o.superclass.ref, r.superclass);
                });
    }

    @Test
    public void testClassType_SetValuesRequestParsing() {
        // this is interesting as it uses untagged values
        // we cannot therefore use the oracle here

        // first we assume that the field has a known type
        var klass = 1;
        var field = 2;
        var type = Type.BOOLEAN;
        vm.addField(klass, field, (byte)type.tag);
        var request = new SetValuesRequest(0, Reference.classType(klass),
                new ListValue<>(new SetValuesRequest.FieldValue(Reference.field(field), wrap(1))));
        var packet = request.toPacket(vm);
        assertEquals(request, jdwp.JDWP.parse(vm, packet));

        // we then assume that the field has an unknown type
        vm.reset();
        Assertions.assertThrows(NoTagPresentException.class, () -> jdwp.JDWP.parse(vm, packet));
    }

    @Test
    public void testClassType_InvokeMethodRequestParsing() throws IOException {
        // can we generate the same package as the oracle
        var oraclePacket = InvokeMethod.enqueueCommand(ovm,
                new ClassTypeImpl(ovm, 1),
                new ThreadReferenceImpl(ovm, 2),
                3,
                new ValueImpl[]{
                        new BooleanValueImpl(ovm, true),
                        new ClassLoaderReferenceImpl(ovm,2),
                        new ThreadGroupReferenceImpl(ovm,3),
                        new ThreadReferenceImpl(ovm, 4),
                        new ClassObjectReferenceImpl(ovm,5),
                        new ArrayReferenceImpl(ovm, 7)
                }, 4).finishedPacket;
        var packet = new InvokeMethodRequest(0,
                Reference.classType(1),
                Reference.thread(2),
                Reference.method(3),
                new ListValue<BasicValue<?>>(
                        PrimitiveValue.wrap(true),
                        Reference.classLoader(2),
                        Reference.threadGroup(3),
                        Reference.thread(4),
                        Reference.classObject(5),
                        Reference.array(7)),
                PrimitiveValue.wrap(4)).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                InvokeMethodRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(3, readPkg.methodID.value);
        assertEquals(6, readPkg.arguments.size());
        assertEquals(PrimitiveValue.wrap(true), readPkg.arguments.get(0));
        assertEquals(Reference.threadGroup(3), readPkg.arguments.get(2));
    }

    @Test
    public void testClassType_InvokeMethodReplyParsing() {
        testReplyParsing(InvokeMethod::new,
                new InvokeMethodReply(0, PrimitiveValue.wrap(10), Reference.objectReference(5)),
                (o, r) -> {
                    assertEquals(10, ((IntegerValueImpl)o.returnValue).value());
                    assertEquals(10, r.returnValue.value);
                });
    }

    @Test
    public void testWriteValueChecked() {
        Assertions.assertAll(
                () -> twh(new ObjectReferenceImpl(ovm, 17), Reference.objectReference(17)),
                () -> twh(new ThreadReferenceImpl(ovm, 17), Reference.thread(17)),
                () -> twh(new BooleanValueImpl(ovm, true), wrap(true)),
                () -> twh(new IntegerValueImpl(ovm, 1), wrap(1))
        );
    }

    @SneakyThrows
    private <T> void twh(ValueImpl oracleValue, BasicValue<T> value) {
        var ops = new PacketStream(ovm, 0, 0);
        ops.writeValueChecked(oracleValue);
        ops.send();
        var ps = new jdwp.PacketStream(vm, 0, 0);
        ps.writeValueTagged(value);
        assertArrayEquals(ops.finishedPacket.toByteArray(), ps.toPacket().toByteArray());
    }

    @SneakyThrows
    @SuppressWarnings("unchecked")
    <O, R extends Reply> void testReplyParsing(BiFunction<VirtualMachineImpl, PacketStream, O> oracleSupplier,
                                               R reply,
                                               BiConsumer<? super O, ? super R> checker) {
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
    @SneakyThrows
    public <R extends WalkableValue<String> & Request<?>>
        void testBasicRequestParsing(PacketStream expected, R request) {
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
        assertEquals(request.getId(), readPkg.getId());
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