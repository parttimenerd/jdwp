package jdwp;

import jdwp.ArrayReferenceCmds.GetValuesReply;
import jdwp.ArrayReferenceCmds.GetValuesRequest;
import jdwp.ArrayReferenceCmds.LengthReply;
import jdwp.ArrayReferenceCmds.LengthRequest;
import jdwp.ClassLoaderReferenceCmds.VisibleClassesRequest;
import jdwp.ClassTypeCmds.*;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.Exception;
import jdwp.EventCmds.Events.ThreadDeath;
import jdwp.EventCmds.Events.*;
import jdwp.EventRequestCmds.SetRequest;
import jdwp.EventRequestCmds.SetRequest.ModifierCommon;
import jdwp.JDWP.SuspendPolicy;
import jdwp.MethodCmds.BytecodesReply;
import jdwp.MethodCmds.IsObsoleteReply;
import jdwp.MethodCmds.LineTableReply;
import jdwp.MethodCmds.VariableTableWithGenericRequest;
import jdwp.PrimitiveValue.IntValue;
import jdwp.PrimitiveValue.StringValue;
import jdwp.ReferenceTypeCmds.*;
import jdwp.ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo;
import jdwp.StackFrameCmds.ThisObjectRequest;
import jdwp.ThreadGroupReferenceCmds.ChildrenReply;
import jdwp.ThreadReferenceCmds.NameReply;
import jdwp.ThreadReferenceCmds.NameRequest;
import jdwp.ThreadReferenceCmds.ThreadGroupReply;
import jdwp.TunnelCmds.EvaluateProgramReply;
import jdwp.TunnelCmds.EvaluateProgramReply.RequestReply;
import jdwp.VM.NoTagPresentException;
import jdwp.Value.*;
import jdwp.VirtualMachineCmds.*;
import jdwp.VirtualMachineCmds.ClassesBySignatureReply.ClassInfo;
import jdwp.oracle.JDWP;
import jdwp.oracle.*;
import jdwp.oracle.JDWP.ArrayReference.GetValues;
import jdwp.oracle.JDWP.ArrayReference.Length;
import jdwp.oracle.JDWP.ArrayType;
import jdwp.oracle.JDWP.ClassLoaderReference.VisibleClasses;
import jdwp.oracle.JDWP.ClassType.InvokeMethod;
import jdwp.oracle.JDWP.ClassType.NewInstance;
import jdwp.oracle.JDWP.ClassType.SetValues;
import jdwp.oracle.JDWP.ClassType.Superclass;
import jdwp.oracle.JDWP.Event.Composite;
import jdwp.oracle.JDWP.EventRequest.Set;
import jdwp.oracle.JDWP.EventRequest.Set.Modifier;
import jdwp.oracle.JDWP.EventRequest.Set.Modifier.ClassMatch;
import jdwp.oracle.JDWP.EventRequest.Set.Modifier.Count;
import jdwp.oracle.JDWP.Method.IsObsolete;
import jdwp.oracle.JDWP.Method.LineTable;
import jdwp.oracle.JDWP.ModuleReference;
import jdwp.oracle.JDWP.ObjectReference;
import jdwp.oracle.JDWP.ReferenceType;
import jdwp.oracle.JDWP.ReferenceType.*;
import jdwp.oracle.JDWP.ReferenceType.GetValues.Field;
import jdwp.oracle.JDWP.StackFrame.ThisObject;
import jdwp.oracle.JDWP.ThreadGroupReference.Children;
import jdwp.oracle.JDWP.ThreadReference.Name;
import jdwp.oracle.JDWP.ThreadReference.ThreadGroup;
import jdwp.oracle.JDWP.VirtualMachine.*;
import jdwp.oracle.Packet;
import jdwp.oracle.JDWP.VirtualMachine.RedefineClasses.ClassDef;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.*;
import static tunnel.BasicTunnel.parseEvaluateProgramReply;

/**
 * Tests for the JDWP classes, including many reply and request classes.
 * <p>
 * Some classes are omitted as there is (near) duplication in the specification
 */
class JDWPTest {

    private static final VirtualMachineImpl ovm = new VirtualMachineImpl(null, null, null, 1);
    private static final VM vm = new VM(0);

    @BeforeEach
    void clean() {
        vm.reset();
    }

    @Test
    public void testPacketToStream() {
        var outStream = new PacketOutputStream(vm, 1, 2);
        outStream.writeDouble(1043.4);
        var pkt = outStream.toPacket();
        var inStream = pkt.toStream(vm);
        assertArrayEquals(pkt.toByteArray(), inStream.data());
    }

    @Test
    public void testVirtualMachine_VersionRequestParsing() {
        // can we generate the same package as the oracle
        var oraclePacket = JDWP.VirtualMachine.Version.enqueueCommand(ovm).finishedPacket;
        var packet = new VirtualMachineCmds.VersionRequest(0, (short) 0).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                VirtualMachineCmds.VersionRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertEquals(0, readPkg.getId());
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
                new VirtualMachineCmds.VersionReply(0,
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
    public void testVirtualMachine_ClassBySignatureRequestParsing() {
        // can we generate the same package as the oracle
        var oraclePacket = JDWP.VirtualMachine.ClassesBySignature.enqueueCommand(ovm, "sig").finishedPacket;
        var packet = new VirtualMachineCmds.ClassesBySignatureRequest(0, wrap("sig")).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                VirtualMachineCmds.ClassesBySignatureRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
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
                new VirtualMachineCmds.ClassesBySignatureReply(0,
                        new ListValue<>(Type.OBJECT, new ArrayList<>())),
                (o, r) -> {
                    assertEquals(0, r.classes.size());
                    assertEquals(0, o.classes.length);
                });
        // single
        testReplyParsing(ClassesBySignature::new,
                new VirtualMachineCmds.ClassesBySignatureReply(0,
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
                new VirtualMachineCmds.ClassesBySignatureReply(0,
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
    public void testVirtualMachine_AllClassesRequestParsing() {
        // can we generate the same package as the oracle
        var oraclePs = JDWP.VirtualMachine.AllClasses.enqueueCommand(ovm);
        oraclePs.pkt.id = 1;
        var oraclePacket = oraclePs.finishedPacket;
        var packet = new VirtualMachineCmds.AllClassesRequest(1, (short) 0).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                VirtualMachineCmds.AllClassesRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(1, readPkg.id);

        // test getKeys()
        assertEquals(0, readPkg.getKeys().size());

        // test JDWP.parse
        assertInstanceOf(AllClassesRequest.class, jdwp.JDWP.parse(vm, packet));

        // test parsing the related reply
        var reply = new AllClassesReply(1,
                new ListValue<>(new AllClassesReply.ClassInfo(wrap((byte) 1), Reference.klass(1), wrap("bla"), wrap(1))));
        var preply = readPkg.parseReply(reply.toStream(vm)).getReply();
        assertEquals(reply.classes.size(), preply.classes.size());
        assertEquals(reply.classes.get(0).signature, preply.classes.get(0).signature);

        // test parsing a related reply with different id
        Assertions.assertThrows(Reply.IdMismatchException.class, () -> readPkg.parseReply(new AllClassesReply(10,
                new ListValue<>(Type.OBJECT)).toStream(vm)));
    }

    @Test
    public void testVirtualMachine_AllClassesReplyParsing() {
        // several
        testReplyParsing(AllClasses::new,
                new VirtualMachineCmds.AllClassesReply(0,
                        new ListValue<>(Type.OBJECT, IntStream.range(0, 5).mapToObj(i -> new AllClassesReply.ClassInfo(wrap((byte) (1 + i)), Reference.klass(100 + i), wrap("blabla" + i), wrap(101 + i))).collect(Collectors.toList()))),
                (o, r) -> {
                    assertEquals(5, r.classes.size());
                    assertEquals(5, o.classes.length);
                    for (int i = 0; i < 5; i++) {
                        assertEquals2((byte) (1 + i), o.classes[i].refTypeTag, r.classes.get(i).refTypeTag);
                        assertEquals2(100L + i, o.classes[i].typeID, r.classes.get(i).typeID);
                        assertEquals2(101 + i, o.classes[i].status, r.classes.get(i).status);
                        assertEquals2("blabla" + i, o.classes[i].signature, r.classes.get(i).signature);
                    }
                });
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_AllThreadsRequestParsing() {
        testBasicRequestParsing(AllThreads.enqueueCommand(ovm), new AllThreadsRequest(0));
    }

    @Test
    @Tag("basic")
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
    @Tag("basic")
    public void testVirtualMachine_DisposeRequestParsing() {
        testBasicRequestParsing(Dispose.enqueueCommand(ovm), new DisposeRequest(0));
        assertFalse(new DisposeRequest(0).onlyReads());
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_DisposeReplyParsing() {
        testReplyParsing(Dispose::new,
                new DisposeReply(0),
                (o, r) -> assertEquals(0, r.getKeys().size()));
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_TopLevelThreadGroupsRequestParsing() {
        testBasicRequestParsing(TopLevelThreadGroups.enqueueCommand(ovm), new TopLevelThreadGroupsRequest(0));
    }

    @Test
    @Tag("basic")
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
    @Tag("basic")
    public void testVirtualMachine_IDSizesRequestParsing() {
        testBasicRequestParsing(IDSizes.enqueueCommand(ovm), new IDSizesRequest(0));
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_IDSizesReplyParsing() {
        testReplyParsing(IDSizes::new,
                new IDSizesReply(1011, wrap(9), wrap(1), wrap(10), wrap(8), wrap(10)),
                (o, r) -> {
                    assertEquals2(10, o.frameIDSize, r.frameIDSize);
                    assertEquals2(9, o.fieldIDSize, r.fieldIDSize);
                });
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_SuspendResumeExitRequestParsing() {
        Assertions.assertAll(
                () -> testBasicRequestParsing(Suspend.enqueueCommand(ovm), new SuspendRequest(0)),
                () -> testBasicRequestParsing(Resume.enqueueCommand(ovm), new ResumeRequest(0)),
                () -> testBasicRequestParsing(Exit.enqueueCommand(ovm, 10), new ExitRequest(0, wrap(10)))
        );
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_SuspendResumeExitReplyParsing() {
        BiConsumer<Object, CombinedValue> empty = (o, r) -> assertEquals(0, r.getKeyStream().count());
        Assertions.assertAll(
                () -> testReplyParsing(Suspend::new, new SuspendReply(0), empty),
                () -> testReplyParsing(Resume::new, new ResumeReply(0), empty),
                () -> testReplyParsing(Exit::new, new ExitReply(10), empty)
        );
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_CreateStringRequestParsing() {
        testBasicRequestParsing(CreateString.enqueueCommand(ovm, "45"), new CreateStringRequest(0, wrap("45")));
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_CreateStringReplyParsing() {
        testReplyParsing(CreateString::new,
                new CreateStringReply(0, Reference.object(1)),
                (o, r) -> {
                    assertEquals2((long) 1, o.stringObject.ref, r.stringObject);
                });
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_CapabilitiesRequestParsing() {
        testBasicRequestParsing(Capabilities.enqueueCommand(ovm),
                new CapabilitiesRequest(0));
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_CapabilitiesReplyParsing() {
        testReplyParsing(Capabilities::new,
                new CapabilitiesReply(0, wrap(true), wrap(false),
                        wrap(true), wrap(true), wrap(false), wrap(false), wrap(true)),
                (o, r) -> {
                    assertEquals2(false, o.canWatchFieldAccess, r.canWatchFieldAccess);
                    assertEquals2(true, o.canGetBytecodes, r.canGetBytecodes);
                });
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_ClassPathsRequestParsing() {
        testBasicRequestParsing(ClassPaths.enqueueCommand(ovm),
                new ClassPathsRequest(0));
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_ClassPathsReplyParsing() {
        testReplyParsing(ClassPaths::new,
                new ClassPathsReply(0, wrap("dir/dir"),
                        new ListValue<>(wrap("x"), wrap("y")),
                        new ListValue<>(wrap("x"))),
                (o, r) -> {
                    assertEquals2("dir/dir", o.baseDir, r.baseDir);
                    assertEquals2("y", o.classpaths[1], r.classpaths.get(1));
                });
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_DisposeObjectsReplyParsing() {
        testBasicRequestParsing(DisposeObjects.enqueueCommand(ovm, new DisposeObjects.Request[]{
                        new DisposeObjects.Request(new ObjectReferenceImpl(ovm, 100), 101),
                        new DisposeObjects.Request(new ObjectReferenceImpl(ovm, 100000), 10)}),
                new DisposeObjectsRequest(0, new ListValue<>(
                        new DisposeObjectsRequest.Request(Reference.object(100), wrap(101)),
                        new DisposeObjectsRequest.Request(Reference.object(100000), wrap(10)))));
    }

    @Test
    public void testVirtualMachine_RedefineClassesRequestParsing() {
        testBasicRequestParsing(RedefineClasses.enqueueCommand(ovm, new ClassDef[]{
                        new ClassDef(new ClassTypeImpl(ovm, 100), "hallo".getBytes(StandardCharsets.UTF_8))
                }),
                new RedefineClassesRequest(0, new ListValue<>(
                        new RedefineClassesRequest.ClassDef(Reference.klass(100),
                                new ByteList("hallo".getBytes(StandardCharsets.UTF_8))
                        ))));
        testBasicRequestParsing(RedefineClasses.enqueueCommand(ovm, new ClassDef[]{}),
                new RedefineClassesRequest(0, new ListValue<>(Type.OBJECT)));
    }

    @Test
    public void testVirtualMachine_AllModulesReplyParsing() {
        testReplyParsing(AllModules::new,
                new AllModulesReply(0, new ListValue<>(Reference.module(100110))),
                (o, r) -> {
                    assertEquals2((long) 100110, o.modules[0].ref, r.modules.get(0));
                    assertEquals(1, o.modules.length);
                });
    }

    @Test
    public void testReferenceType_GetValuesRequestParsing() {
        testBasicRequestParsing(ReferenceType.GetValues.enqueueCommand(ovm,
                        new ClassTypeImpl(ovm, -2), new Field[]{
                                new Field(1000),
                                new Field(-1)
                        }),
                new ReferenceTypeCmds.GetValuesRequest(0,
                        Reference.klass(-2),
                        new ListValue<>(
                                new ReferenceTypeCmds.GetValuesRequest.Field(Reference.field(1000)),
                                new ReferenceTypeCmds.GetValuesRequest.Field(Reference.field(-1))
                        )));
    }

    @Test
    public void testReferenceType_GetValuesReplyParsing() {
        testReplyParsing(ReferenceType.GetValues::new,
                new ReferenceTypeCmds.GetValuesReply(0, new ListValue<>(Type.VALUE, wrap(1), wrap(-1))),
                (o, r) -> {
                    assertEquals2(1, ((PrimitiveValueImpl) o.values[0]).intValue(), (IntValue) r.values.get(0));
                });
    }

    @Test
    public void testVirtualMachine_InterfacesRequestParsing() {
        testBasicRequestParsing(Interfaces.enqueueCommand(ovm, new InterfaceTypeImpl(ovm, -2)),
                new InterfacesRequest(0, Reference.klass(-2)));
    }

    @Test
    public void testReferenceType_InterfacesReplyParsing() {
        testReplyParsing(Interfaces::new,
                new InterfacesReply(0, new ListValue<>(Reference.interfaceType(-2))),
                (o, r) -> {
                    assertEquals2((long) -2, o.interfaces[0].ref, r.interfaces.get(0));
                });
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_ClassObjectRequestParsing() {
        testBasicRequestParsing(ClassObject.enqueueCommand(ovm, new InterfaceTypeImpl(ovm, -2)),
                new ClassObjectRequest(0, Reference.klass(-2)));
    }

    @Test
    public void testReferenceType_ClassObjectReplyParsing() {
        testReplyParsing(ClassObject::new,
                new ClassObjectReply(0, Reference.classObject(-2)),
                (o, r) -> {
                    assertEquals2((long) -2, o.classObject.ref, r.classObject);
                });
    }

    @Test
    @Tag("basic")
    public void testReferenceType_MethodsWithGenericReplyParsing() {
        testReplyParsing(MethodsWithGeneric::new,
                new MethodsWithGenericReply(0, new ListValue<>(
                        new MethodInfo(Reference.method(-2),
                                wrap("class"), wrap("sig"),
                                wrap("blub"), wrap(1))
                )),
                (o, r) -> {
                });
    }

    @Test
    public void testReferenceType_InstancesReplyParsing() {
        testReplyParsing(Instances::new,
                new InstancesReply(0, new ListValue<>(
                        Reference.object(-324),
                        Reference.object(100)
                )),
                (o, r) -> {
                    assertEquals2((long) -324, o.instances[0].ref, r.instances.get(0));
                    assertEquals2((long) 100, o.instances[1].ref, r.instances.get(1));
                });
    }

    @Test
    @Tag("basic")
    public void testVirtualMachine_ConstantPoolRequestParsing() {
        testBasicRequestParsing(ConstantPool.enqueueCommand(ovm, new InterfaceTypeImpl(ovm, -2)),
                new ConstantPoolRequest(0, Reference.klass(-2)));
    }

    @Test
    public void testReferenceType_ConstantPoolReplyParsing() {
        testReplyParsing(ConstantPool::new,
                new ConstantPoolReply(0, wrap(100), new ByteList((byte) 1, (byte) 2, (byte) 3)),
                (o, r) -> {
                    assertEquals2((byte) 1, o.bytes[0], r.bytes.get(0));
                    assertEquals2((byte) 2, o.bytes[1], r.bytes.get(1));
                    assertEquals2((byte) 3, o.bytes[2], r.bytes.get(2));
                });
    }

    @Test
    @Tag("basic")
    public void testClassType_SuperclassRequestParsing() {
        testBasicRequestParsing(Superclass.enqueueCommand(ovm, new ClassTypeImpl(ovm, 10)),
                new SuperclassRequest(0, Reference.classType(10)));
    }

    @Test
    @Tag("basic")
    public void testClassType_SuperclassReplyParsing() {
        testReplyParsing(Superclass::new,
                new SuperclassReply(0, Reference.classType(10)),
                (o, r) -> assertEquals2(10L, o.superclass.ref, r.superclass));
    }

    @Test
    public void testClassType_SetValuesRequestParsing() {
        // this is interesting as it uses untagged values
        // we cannot therefore use the oracle here

        // first we assume that the field has a known type
        var klass = 1;
        var field = 2;
        var type = Type.BOOLEAN;
        vm.addFieldTag(klass, field, (byte) type.tag);
        var request = new SetValuesRequest(0, Reference.classType(klass),
                new ListValue<>(Type.OBJECT, new SetValuesRequest.FieldValue(Reference.field(field), wrap(true))));
        var packet = request.toPacket(vm);
        assertEquals(request, jdwp.JDWP.parse(vm, packet));

        // we then assume that the field has an unknown type
        vm.reset();
        Assertions.assertThrows(NoTagPresentException.class, () -> jdwp.JDWP.parse(vm, packet));
    }


    @Test
    @Tag("basic")
    public void testClassType_SetValuesReplyParsing() {
        testReplyParsing(SetValues::new,
                new SetValuesReply(0),
                (o, r) -> assertEquals(0, r.getKeys().size()));
    }

    @Test
    public void testClassType_InvokeMethodRequestParsing() {
        // can we generate the same package as the oracle
        var oraclePacket = InvokeMethod.enqueueCommand(ovm,
                new ClassTypeImpl(ovm, 1),
                new ThreadReferenceImpl(ovm, 2),
                3,
                new ValueImpl[]{
                        new BooleanValueImpl(ovm, true),
                        new ClassLoaderReferenceImpl(ovm, 2),
                        new ThreadGroupReferenceImpl(ovm, 3),
                        new ThreadReferenceImpl(ovm, 4),
                        new ClassObjectReferenceImpl(ovm, 5),
                        new ArrayReferenceImpl(ovm, 7)
                }, 4).finishedPacket;
        var packet = new InvokeMethodRequest(0,
                Reference.classType(1),
                Reference.thread(2),
                Reference.method(3),
                new ListValue<>(
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
    @Tag("basic")
    public void testClassType_InvokeMethodReplyParsing() {
        testReplyParsing(InvokeMethod::new,
                new InvokeMethodReply(0, PrimitiveValue.wrap(10), Reference.object(5)),
                (o, r) -> {
                    assertEquals(10, ((IntegerValueImpl) o.returnValue).value());
                    assertEquals(10, r.returnValue.value);
                });
    }

    @Test
    public void testClassType_NewInstanceReplyParsing() {
        testReplyParsing(NewInstance::new,
                new NewInstanceReply(0, Reference.object(1), Reference.object(10)),
                (o, r) -> {
                    assertEquals2((long) 1, o.newObject.ref, r.newObject);
                    assertEquals2((long) 10, o.exception.ref, r.exception);
                });
    }

    @Test
    public void testArrayType_NewInstanceRequestParsing() {
        testBasicRequestParsing(ArrayType.NewInstance.enqueueCommand(ovm, new ArrayTypeImpl(ovm, 1000), 99),
                new ArrayTypeCmds.NewInstanceRequest(0, Reference.arrayType(1000), wrap(99)));
    }

    @Test
    @Tag("basic")
    public void testMethod_LineTableReplyParsing() {
        testReplyParsing(LineTable::new,
                new LineTableReply(0, wrap((long) 1), wrap((long) 11),
                        new ListValue<>(new LineTableReply.LineInfo(wrap((long) -1), wrap(10)))),
                (o, r) -> {
                    assertEquals2((long) -1, o.lines[0].lineCodeIndex, r.lines.get(0).lineCodeIndex);
                });
    }

    @Test
    @Tag("basic")
    public void testMethod_IsObsoleteReplyParsing() {
        testReplyParsing(IsObsolete::new,
                new IsObsoleteReply(0, wrap(true)),
                (o, r) -> {
                    assertEquals2(true, o.isObsolete, r.isObsolete);
                });
    }

    @Test
    @Tag("basic")
    public void testObjectReference_GetValuesRequestParsing() {
        testBasicRequestParsing(ObjectReference.GetValues.enqueueCommand(ovm,
                new ObjectReferenceImpl(ovm, 10), new ObjectReference.GetValues.Field[]{
                        new ObjectReference.GetValues.Field(10),
                        new ObjectReference.GetValues.Field(10000)
                }), new ObjectReferenceCmds.GetValuesRequest(0, Reference.object(10),
                new ListValue<>(new ObjectReferenceCmds.GetValuesRequest.Field(Reference.field(10)),
                        new ObjectReferenceCmds.GetValuesRequest.Field(Reference.field(10000)))));
    }

    @Test
    public void testObjectReference_SetValuesRequestParsing() {
        vm.addFieldTag(10, 10, (byte) Type.INT.tag);
        vm.addFieldTag(10, 11, (byte) Type.STRING.tag);
        vm.setClass(10, 10);
        testBasicRequestParsing(ObjectReference.SetValues.enqueueCommand(ovm,
                new ObjectReferenceImpl(ovm, 10), new ObjectReference.SetValues.FieldValue[]{
                        new ObjectReference.SetValues.FieldValue(10, new IntegerValueImpl(ovm, -1)),
                        new ObjectReference.SetValues.FieldValue(11, new StringReferenceImpl(ovm, -2)),
                }), new ObjectReferenceCmds.SetValuesRequest(0, Reference.object(10),
                new ListValue<>
                        (new ObjectReferenceCmds.SetValuesRequest.FieldValue(Reference.field(10), wrap(-1)),
                                new ObjectReferenceCmds.SetValuesRequest.FieldValue(Reference.field(11), Reference.string(-2)))));
    }

    @Test
    public void testThreadReference_NameRequestParsing() {
        // can we generate the same package as the oracle
        var t = new ThreadReferenceImpl();
        t.ref = 100;
        var oraclePacket = Name.enqueueCommand(ovm, t).finishedPacket;
        var packet = new ThreadReferenceCmds.NameRequest(0, (short) 0, Reference.thread(100)).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                ThreadReferenceCmds.NameRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
        assertEquals(100, readPkg.thread.value);
        assertEquals(0, readPkg.id);

        // test get()
        assertEquals(Reference.thread(100), readPkg.get("thread"));

        // test JDWP.parse
        assertInstanceOf(NameRequest.class, jdwp.JDWP.parse(vm, packet));
    }

    @Test
    @Tag("basic")
    public void testThreadReference_NameReplyParsing() {
        testReplyParsing(Name::new, new ThreadReferenceCmds.NameReply(0, wrap("a")),
                (o, r) -> {
                    assertEquals("a", o.threadName);
                    assertEquals("a", ((StringValue) r.get("threadName")).value);
                });
    }

    @Test
    public void testThreadReference_ThreadGroupReplyParsing() {
        testReplyParsing(ThreadGroup::new,
                new ThreadGroupReply(0, Reference.threadGroup(79)),
                (o, r) -> assertEquals2((long) 79, o.group.ref, r.group));
    }

    @Test
    public void testThreadGroupReference_ChildrenReplyParsing() {
        testReplyParsing(Children::new,
                new ChildrenReply(0,
                        new ListValue<>(Reference.thread(1), Reference.thread(2)),
                        new ListValue<>(Reference.threadGroup(-1))),
                (o, r) -> {
                    assertEquals2(1, o.childGroups.length, r.childGroups.size());
                    assertEquals2((long) -1, o.childGroups[0].ref, r.childGroups.get(0));
                });
    }

    @Test
    @Tag("basic")
    public void testArrayReference_LengthRequestParsing() {
        testBasicRequestParsing(Length.enqueueCommand(ovm, new ArrayReferenceImpl(ovm, 9)),
                new LengthRequest(0, Reference.array(9)));
    }

    @Test
    @Tag("basic")
    public void testArrayReference_LengthReplyParsing() {
        testReplyParsing(Length::new,
                new LengthReply(0, wrap(10)),
                (o, r) -> assertEquals2(10, o.arrayLength, r.arrayLength));
    }

    @Test
    @Tag("basic")
    public void testArrayReference_GetValuesRequestParsing() {
        testBasicRequestParsing(GetValues.enqueueCommand(ovm, new ArrayReferenceImpl(ovm, 9), 10, 11),
                new GetValuesRequest(0, Reference.array(9), wrap(10), wrap(11)));
    }

    @Test
    @Tag("basic")
    public void testArrayReference_GetValuesReplyParsing() {
        testReplyParsing(GetValues::new,
                new GetValuesReply(0, new BasicListValue<>(Type.INT, List.of(wrap(11)))),
                (o, r) -> assertEquals2(11, ((IntegerValueImpl) o.values.get(0)).intValue(), (IntValue) r.values.get(0)));
    }

    @Test
    public void testArrayReference_SetValuesRequestParsing() {
        // this is interesting as it uses untagged values with an array reference
        // we cannot therefore use the oracle here

        // first we assume that the field has a known type
        var array = 1;
        var type = Type.INT;
        vm.addArrayTag(array, (byte) type.tag);
        var request = new ArrayReferenceCmds.SetValuesRequest(0, Reference.array(array), wrap(1),
                new ListValue<>(Type.VALUE, wrap(1), wrap(2), wrap(3)));
        var packet = request.toPacket(vm);
        assertEquals(request, jdwp.JDWP.parse(vm, packet));

        // we then assume that the field has an unknown type
        vm.reset();
        Assertions.assertThrows(NoTagPresentException.class, () -> jdwp.JDWP.parse(vm, packet));
    }

    @Test
    @Tag("basic")
    public void testArrayReference_SetValuesReplyParsing() {
        testReplyParsing(JDWP.ArrayReference.SetValues::new,
                new ArrayReferenceCmds.SetValuesReply(0),
                (o, r) -> assertEquals(0, r.getKeys().size()));
    }

    @Test
    @Tag("basic")
    public void testMethodReference_BytecodesReplyParsing() {
        testReplyParsing(JDWP.ArrayReference.SetValues::new,
                new BytecodesReply(0, new ByteList((byte) 1, (byte) 2)),
                (o, r) -> assertArrayEquals(new byte[]{1, 2}, r.bytes.bytes));
    }

    @Test
    public void testClassLoaderReference_VisibleClassesRequestParsing() {
        testBasicRequestParsing(VisibleClasses.enqueueCommand(ovm, new ClassLoaderReferenceImpl(ovm, 43)),
                new VisibleClassesRequest(0, Reference.classLoader(43)));
    }

    @Test
    @SneakyThrows
    public void testEventRequest_SetRequestParsing() {
        // can we generate the same package as the oracle
        Modifier[] omods = new Modifier[]{
                new Modifier((byte) 1, new Count(11)),
                new Modifier((byte) 5, new ClassMatch("asd"))
        };
        ModifierCommon[] mods = new ModifierCommon[]{
                new SetRequest.Count(wrap(11)),
                new SetRequest.ClassMatch(wrap("asd"))
        };
        var oraclePs = Set.enqueueCommand(ovm, (byte) 0, (byte) 0, omods);
        var oraclePacket = oraclePs.finishedPacket;
        var packet = new SetRequest(0, wrap((byte) 0), wrap((byte) 0),
                new ListValue<>(Type.OBJECT, mods)).toPacket(vm);
        assertPacketsEqual(oraclePacket, packet);

        // can we parse the package?
        var readPkg =
                SetRequest.parse(vm, jdwp.Packet.fromByteArray(packet.toByteArray()));
        assertPacketsEqual(packet, readPkg.toPacket(vm));
    }

    @Test
    public void testStackFrame_ThisObjectRequestParsing() {
        testBasicRequestParsing(ThisObject.enqueueCommand(ovm, new ThreadReferenceImpl(ovm, 43), 10),
                new ThisObjectRequest(0, Reference.thread(43), Reference.frame(10)));
    }

    @Test
    public void testModuleReference_NameRequestParsing() {
        testBasicRequestParsing(ModuleReference.Name.enqueueCommand(ovm, new ModuleReferenceImpl(ovm, 42)),
                new ModuleReferenceCmds.NameRequest(0, Reference.module(42)));
    }

    /**
     * also tests parsing VMStart and SingleStep events
     */
    @Test
    public void testEvent_parse() {
        // basic round-trip parse
        var vmStart = new VMStart(wrap(1), Reference.thread(10));
        var singleStep = new SingleStep(wrap(10),
                Reference.thread(100),
                new Location(Reference.classType(1), Reference.method(7), wrap((long) 101)));
        var events = new Events(0, wrap((byte) SuspendPolicy.NONE), new ListValue<>(vmStart, singleStep));

        // basic check with oracle
        var oparsed = new Composite(ovm, oraclePacketStream(events.toPacket(vm)));
        assertEquals(events.suspendPolicy.value, oparsed.suspendPolicy);
        assertEquals(events.size(), oparsed.events.length);

        assertInstanceOf(Composite.Events.VMStart.class, oparsed.events[0].aEventsCommon);
        var oVmStart = (Composite.Events.VMStart) oparsed.events[0].aEventsCommon;
        assertEquals(vmStart.requestID.value, oVmStart.requestID);
        assertEquals(vmStart.thread.value, oVmStart.thread.ref);

        assertInstanceOf(Composite.Events.SingleStep.class, oparsed.events[1].aEventsCommon);
        var oSingleStep = (Composite.Events.SingleStep) oparsed.events[1].aEventsCommon;
        assertEquals(singleStep.requestID.value, oSingleStep.requestID);
        assertEquals(singleStep.thread.value, oSingleStep.thread.ref);
        var location = (LocationImpl) oSingleStep.location;
        assertEquals(singleStep.location.codeIndex.value, location.codeIndex);
        assertEquals(singleStep.location.methodRef.value, location.methodRef);

        // test basic parse of produced packet
        var parsed = EventCmds.parse(vm, events.toPacket(vm));
        var pparsed = EventCmds.Events.parse(vm, events.toPacket(vm));
        assertEquals(parsed, pparsed);
        assertEquals(events, parsed);
    }

    @Test
    public void testEvent_VMThreadStartThreadDeath() {
        var vmStart = new VMStart(wrap(1), Reference.thread(10));
        var ps = eventOraclePacketStream(vmStart);
        var oVMStart = new Composite.Events.VMStart(ovm, ps);
        assertEquals(vmStart.requestID.value, oVMStart.requestID);
        assertEquals(vmStart.thread.value, oVMStart.thread.ref);
        assertEquals(vmStart, VMStart.parse(eventPacketStream(vmStart)));

        var threadStart = new ThreadStart(wrap(1), Reference.thread(10));
        var ps2 = eventOraclePacketStream(threadStart);
        var oThreadStart = new Composite.Events.ThreadStart(ovm, ps2);
        assertEquals(threadStart.requestID.value, oThreadStart.requestID);
        assertEquals(threadStart.thread.value, oThreadStart.thread.ref);
        assertEquals(threadStart, ThreadStart.parse(eventPacketStream(threadStart)));

        var threadDeath = new ThreadDeath(wrap(1), Reference.thread(10));
        var ps3 = eventOraclePacketStream(threadDeath);
        var oThreadDeath = new Composite.Events.ThreadDeath(ovm, ps3);
        assertEquals(threadDeath.requestID.value, oThreadDeath.requestID);
        assertEquals(threadDeath.thread.value, oThreadDeath.thread.ref);
        assertEquals(threadDeath, ThreadDeath.parse(eventPacketStream(threadDeath)));
    }

    @Test
    public void testEvent_SingleStep() {
        var singleStep = new SingleStep(wrap(10),
                Reference.thread(100),
                new Location(Reference.classType(1), Reference.method(7), wrap((long) 101)));
        var ps = eventOraclePacketStream(singleStep);
        var oSingleStep = new Composite.Events.SingleStep(ovm, ps);
        assertEquals(singleStep.requestID.value, oSingleStep.requestID);
        assertEquals(singleStep.thread.value, oSingleStep.thread.ref);
        var location = (LocationImpl) oSingleStep.location;
        assertEquals(singleStep.location.codeIndex.value, location.codeIndex);
        assertEquals(singleStep.location.methodRef.value, location.methodRef);
    }

    private final Location location =
            new Location(Reference.classType(1010), Reference.method(79), wrap((long) 4345341));

    private void assertLocationCorrect(com.sun.jdi.Location loc) {
        var lo = (LocationImpl) loc;
        assertEquals(location.codeIndex.value, lo.codeIndex);
        assertEquals(location.methodRef.value, lo.methodRef);
    }

    @Test
    @Tag("basic")
    public void testEvent_Breakpoint() {
        var event = new Breakpoint(wrap(1), Reference.thread(10), location);
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.Breakpoint(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event, Breakpoint.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_MethodEntry() {
        var event = new MethodEntry(wrap(1), Reference.thread(10), location);
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.MethodEntry(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event, MethodEntry.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_MethodExit() {
        var event = new MethodExit(wrap(1), Reference.thread(10), location);
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.MethodExit(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event, MethodExit.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_MethodExitWithReturnValue() {
        var event = new MethodExitWithReturnValue(wrap(1), Reference.thread(10), location, wrap(true));
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.MethodExitWithReturnValue(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event.value.value, ((BooleanValueImpl) oEvent.value).booleanValue());
        assertEquals(event, MethodExitWithReturnValue.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_MonitorContendedEnter() {
        var event = new MonitorContendedEnter(wrap(1), Reference.thread(10), Reference.object(345345345), location);
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.MonitorContendedEnter(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertEquals(event.object.value, oEvent.object.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event, MonitorContendedEnter.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_MonitorContendedEntered() {
        var event = new MonitorContendedEntered(wrap(200), Reference.thread(1023434), Reference.object(345345345), location);
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.MonitorContendedEntered(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertEquals(event.object.value, oEvent.object.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event, MonitorContendedEntered.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_MonitorWaitWaited() {
        var event = new MonitorWait(wrap(200),
                Reference.thread(1023434), Reference.object(345345345),
                location, wrap(Long.parseLong("100000000000")));
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.MonitorWait(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertEquals(event.object.value, oEvent.object.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event.timeout.value, oEvent.timeout);
        assertEquals(event, MonitorWait.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_MonitorWaited() {
        var event = new MonitorWaited(wrap(200),
                Reference.thread(1023434), Reference.object(345345345),
                location, wrap(true));
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.MonitorWaited(ovm, ps);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertEquals(event.timed_out.value, oEvent.timed_out);
        assertEquals(event, MonitorWaited.parse(eventPacketStream(event)));

    }

    @Test
    @Tag("basic")
    public void testEvent_Exception() {
        var event = new Exception(wrap(200), Reference.thread(1023434),
                location, Reference.object(Long.parseLong("8354762345873")),
                location);
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.Exception(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertEquals(event.exception.value, oEvent.exception.ref);
        assertLocationCorrect(oEvent.location);
        assertLocationCorrect(oEvent.catchLocation);
        assertEquals(event, Exception.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_ClassPrepare() {
        var event = new ClassPrepare(wrap(1), Reference.thread(10), wrap((byte) 3),
                Reference.klass(10000), wrap("dfgadfjg"), wrap(-100003));
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.ClassPrepare(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertEquals(event.refTypeTag.value, oEvent.refTypeTag);
        assertEquals(event.signature.value, oEvent.signature);
        assertEquals(event.status.value, oEvent.status);
        assertEquals(event, ClassPrepare.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_ClassUnload() {
        var event = new ClassUnload(wrap(1), wrap("345345345939873454"));
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.ClassUnload(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.signature.value, oEvent.signature);
        assertEquals(event, ClassUnload.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_FieldAccess() {
        var event = new FieldAccess(wrap(1), Reference.thread(10), location,
                wrap((byte) 102), Reference.klass(234), Reference.field(1324),
                Reference.object(3453345));
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.FieldAccess(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event.refTypeTag.value, oEvent.refTypeTag);
        assertEquals(event.typeID.value, oEvent.typeID);
        assertEquals(event.fieldID.value, oEvent.fieldID);
        assertEquals(event.object.value, oEvent.object.ref);
        assertEquals(event, FieldAccess.parse(eventPacketStream(event)));
    }

    @Test
    @Tag("basic")
    public void testEvent_FieldModification() {
        var event = new FieldModification(wrap(1), Reference.thread(10), location,
                wrap((byte) 102), Reference.klass(234), Reference.field(1324),
                Reference.object(3453345), wrap(1.0));
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.FieldModification(ovm, ps);
        assertEquals(event.requestID.value, oEvent.requestID);
        assertEquals(event.thread.value, oEvent.thread.ref);
        assertLocationCorrect(oEvent.location);
        assertEquals(event.refTypeTag.value, oEvent.refTypeTag);
        assertEquals(event.typeID.value, oEvent.typeID);
        assertEquals(event.fieldID.value, oEvent.fieldID);
        assertEquals(event.object.value, oEvent.object.ref);
        assertEquals(event.valueToBe.value, ((DoubleValueImpl) oEvent.valueToBe).doubleValue());
        assertEquals(event, FieldModification.parse(eventPacketStream(event)));
    }

    @Test
    public void testEvent_VMDeath() {
        int requestID = 129;
        var event = new VMDeath(wrap(requestID));
        var ps = eventOraclePacketStream(event);
        var oEvent = new Composite.Events.VMDeath(ovm, ps);
        assertEquals(requestID, oEvent.requestID);
        var eps = eventPacketStream(event);
        var parsedEvent = VMDeath.parse(eps);
        assertEquals(event, parsedEvent);
    }

    /**
     * skips the type byte (but asserts that is correct)
     */
    private PacketStream eventOraclePacketStream(EventCommon event) {
        var ps = new jdwp.PacketOutputStream(vm, 0, 0);
        event.write(ps);
        var os = oraclePacketStream(ps.toPacket());
        assertEquals(event.getKind(), os.readByte());
        return os;
    }

    /**
     * skips the type byte (but asserts that is correct)
     */
    private jdwp.PacketInputStream eventPacketStream(EventCommon event) {
        var ps = new jdwp.PacketOutputStream(vm, 0, 0);
        event.write(ps);
        var pps = ps.toPacket().toStream(vm);
        assertEquals(event.getKind(), pps.readByte());
        return pps;
    }

    @Test
    public void testByteListSameAsListOfBytes() {
        var list = new BasicListValue<>(wrap((byte) 1), wrap((byte) -2), wrap((byte) 100));
        var ps = new jdwp.PacketOutputStream(vm, 0, 0);
        list.writeUntagged(ps);
        var list2 = new ByteList(new byte[]{1, -2, 100});
        var ps2 = new jdwp.PacketOutputStream(vm, 0, 0);
        list2.write(ps2);
        assertArrayEquals(ps.toPacket().toByteArray(), ps2.toPacket().toByteArray());
    }

    @Test
    public void testWriteValueChecked() {
        Assertions.assertAll(
                () -> twh(new ObjectReferenceImpl(ovm, 17), Reference.object(17)),
                () -> twh(new ThreadReferenceImpl(ovm, 17), Reference.thread(17)),
                () -> twh(new BooleanValueImpl(ovm, true), wrap(true)),
                () -> twh(new IntegerValueImpl(ovm, 1), wrap(1))
        );
    }

    @SneakyThrows
    private <T> void twh(ValueImpl oracleValue, BasicScalarValue<T> value) {
        var ops = new PacketStream(ovm, 0, 0);
        ops.writeValueChecked(oracleValue);
        ops.send();
        var ps = new jdwp.PacketOutputStream(vm, 0, 0);
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
        var rreply = ((ReplyOrError<R>) reply.getClass().getMethod("parse", VM.class, jdwp.Packet.class)
                .invoke(null, vm, producedPacket)).getReply();
        assertEquals(reply, rreply);
    }

    @Test
    public void testParsingErrorPackage() {
        // can we handle errors properly (we only have to this for a single test case)
        var reply = new ReplyOrError<ThreadReferenceCmds.NameReply>(0, NameRequest.METADATA, (short) 10);
        assertEquals(10, oraclePacketStream(reply.toPacket(vm)).pkt.errorCode);

        // can we parse our package?
        assertEquals(10, NameReply.parse(vm, reply.toPacket(vm)).getErrorCode());
    }

    @Test
    public void testVisitorAndBasicInformationGathering() {
        var request = new ReferenceTypeCmds.GetValuesRequest(0,
                Reference.klass(-2),
                new ListValue<>(
                        new ReferenceTypeCmds.GetValuesRequest.Field(Reference.field(1000)),
                        new ReferenceTypeCmds.GetValuesRequest.Field(Reference.field(-1))
                ));
        var reply = new ReferenceTypeCmds.GetValuesReply(0, new ListValue<>(wrap("a"), wrap(1)));
        vm.captureInformation(request, reply);
        assertTrue(vm.hasFieldTagForObj(-2, 1000));
        assertTrue(vm.hasFieldTagForObj(-2, -1));
        assertEquals(jdwp.JDWP.Tag.STRING, vm.getFieldTagForObj(-2, 1000));
        assertEquals(jdwp.JDWP.Tag.INT, vm.getFieldTagForObj(-2, -1));
    }

    /**
     * Check that both packages are equal and that we can parse the package
     * <p>
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
            readPkg = (R) request.getClass().getMethod("parse", VM.class, jdwp.Packet.class)
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

    private static byte[][] packageErrorHandlingTestSource() {
        return new byte[][] {
                {},
                {0},
                {0, 3, 4,5, 4,5,6}
        };
    }

    @ParameterizedTest
    @MethodSource("packageErrorHandlingTestSource")
    public void testPackageErrorHandling(byte[] b) {
        assertThrows(PacketError.class, () -> jdwp.Packet.fromByteArray(b));
    }

    @Test
    public void testEqualsIgnoresIds() {
        assertEquals(new IDSizesRequest(1), new IDSizesRequest(10));
    }

    @Test
    public void testProcessEvaluateProgramReply() {
        var idSizesRequest = new IDSizesRequest(0);
        var idSizesReply = new IDSizesReply(0, wrap(8), wrap(8), wrap(8), wrap(8), wrap(8));
        var reply = new EvaluateProgramReply(0, new ListValue<>(
                new RequestReply(new ByteList(idSizesRequest.toPacket(vm).toByteArray()),
                        new ByteList(idSizesReply.toPacket(vm).toByteArray()))));
        assertEquals(List.of(p(idSizesRequest, new ReplyOrError<>(idSizesReply))),
                parseEvaluateProgramReply(vm, EvaluateProgramReply.parse(reply.toStream(vm)).getReply()));
    }

    @Test
    public void testCreateRequestWithMapWithDifferentReference() {
        // with reference in same group
        new VariableTableWithGenericRequest(0, Map.of("refType", Reference.classType(1),
                "methodID", Reference.method(1)));
        // with reference in different group
        assertThrows(AssertionError.class, () -> new VariableTableWithGenericRequest(0,
                Map.of("refType", Reference.frame(1), "methodID", Reference.method(1))));
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
        assertEquals(expectedPacket.cmd, actualPacket.cmd);
        assertEquals(expectedPacket.cmdSet, actualPacket.cmdSet);
        assertEquals(expectedPacket.flags, actualPacket.flags);
        assertEquals(expectedPacket.errorCode, actualPacket.errorCode);
        assertArrayEquals(expectedPacket.toByteArray(), actualPacket.toByteArray());
    }

    static void assertPacketsEqual(jdwp.Packet expectedPacket, jdwp.Packet actualPacket) {
        assertEquals(expectedPacket.id, actualPacket.id, "id");
        assertEquals(expectedPacket.cmd, actualPacket.cmd, "cmd");
        assertEquals(expectedPacket.cmdSet, actualPacket.cmdSet, "cmdSet");
        assertEquals(expectedPacket.flags, actualPacket.flags, "flags");
        assertEquals(expectedPacket.errorCode, actualPacket.errorCode, "errorCode");
        assertArrayEquals(expectedPacket.toByteArray(), actualPacket.toByteArray());
    }

    static <T> void assertEquals2(T expected, T oracle, BasicScalarValue<T> jdwp) {
        assertEquals(expected, oracle);
        assertEquals(expected, jdwp.value);
    }

    static <T> void assertEquals2(T expected, T oracle, T jdwp) {
        assertEquals(expected, oracle);
        assertEquals(expected, jdwp);
    }
}