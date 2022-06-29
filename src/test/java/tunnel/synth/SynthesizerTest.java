package tunnel.synth;

import jdwp.ArrayReferenceCmds.LengthReply;
import jdwp.ArrayReferenceCmds.LengthRequest;
import jdwp.ClassTypeCmds.SuperclassReply;
import jdwp.ClassTypeCmds.SuperclassRequest;
import jdwp.*;
import jdwp.JDWP.Error;
import jdwp.ObjectReferenceCmds.ReferenceTypeReply;
import jdwp.ObjectReferenceCmds.ReferenceTypeRequest;
import jdwp.Reference.*;
import jdwp.ReferenceTypeCmds.*;
import jdwp.StackFrameCmds.GetValuesReply;
import jdwp.StackFrameCmds.GetValuesRequest;
import jdwp.StackFrameCmds.GetValuesRequest.SlotInfo;
import jdwp.Value.ByteList;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.util.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.synth.DependencyGraph.Node;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.ProgramTest.RecordingFunctions;
import tunnel.synth.program.Evaluator;
import tunnel.synth.program.Program;
import tunnel.util.Either;

import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.Reference.*;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static tunnel.synth.DependencyGraph.DEFAULT_OPTIONS;
import static tunnel.synth.DependencyGraphTest.getGetValuesRequestPartition;

public class SynthesizerTest {

    private static Object[][] realPartitionsTestSource() {
        return new Object[][]{
                {"((= var0 (request VirtualMachine IDSizes)))", new Partition(null,
                        List.of(p(new jdwp.VirtualMachineCmds.IDSizesRequest(174),
                                new jdwp.VirtualMachineCmds.IDSizesReply(174, wrap(8), wrap(8),
                                        wrap(8), wrap(8), wrap(8)))))},
                {"((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) (\"suspendPolicy\")=(wrap " +
                        "\"byte\" 1) (\"modifiers\" 0 \"kind\")=(wrap \"string\" \"ClassMatch\") (\"modifiers\" 0 " +
                        "\"classPattern\")=(wrap \"string\" \"sun.instrument.InstrumentationImpl\")))\n" +
                        "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) (\"suspendPolicy\")=" +
                        "(wrap \"byte\" 1) (\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"sun.instrument" +
                        ".InstrumentationImpl\") (\"modifiers\" 0 \"kind\")=(wrap \"string\" \"ClassMatch\")))\n" +
                        "  (= var1 (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" 426))))",
                        new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(12090,
                                wrap((byte) 8), wrap((byte) 1),
                                new ListValue<>(Type.LIST,
                                        List.of(new EventRequestCmds.SetRequest.ClassMatch(
                                                wrap("sun" + ".instrument.InstrumentationImpl")))))),
                                List.of(p(new jdwp.EventRequestCmds.SetRequest(12090, wrap((byte) 8),
                                                        wrap((byte) 1), new ListValue<>(Type.LIST,
                                                        List.of(new EventRequestCmds.SetRequest.ClassMatch(
                                                                wrap("sun" + ".instrument" +
                                                                        ".InstrumentationImpl"))))),
                                                new jdwp.EventRequestCmds.SetReply(12090, wrap(6))),
                                        p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(12094,
                                                        new ClassReference(426L)),
                                                new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(12094,
                                                        new ListValue<>(Type.LIST,
                                                                List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(
                                                                        new MethodReference(105553140349016L),
                                                                        wrap("<init>"),
                                                                        wrap("(JZZ)V"),
                                                                        wrap(""),
                                                                        wrap(2))))))))
                },
                {
                        "((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) (\"suspendPolicy\")=" +
                                "(wrap \"byte\" 1) (\"modifiers\" 0 \"kind\")=(wrap \"string\" \"ClassMatch\") " +
                                "(\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"build.tools.jdwpgen" +
                                ".CodeGeneration$genToString$1$*\")))\n" +
                                "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) " +
                                "(\"suspendPolicy\")=(wrap \"byte\" 1) (\"modifiers\" 0 \"classPattern\")=(wrap " +
                                "\"string\" \"build.tools.jdwpgen.CodeGeneration$genToString$1$*\") (\"modifiers\" 0 " +
                                "\"kind\")=(wrap \"string\" \"ClassMatch\"))))",
                        new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(16165,
                                wrap((byte) 8), wrap((byte) 1),
                                new ListValue<>(Type.LIST,
                                        List.of(new EventRequestCmds.SetRequest.ClassMatch(wrap("build" +
                                                ".tools.jdwpgen.CodeGeneration$genToString$1$*")))))), List.of(
                                p(new jdwp.EventRequestCmds.SetRequest(16165, wrap((byte) 8),
                                                wrap((byte) 1), new ListValue<>(Type.LIST,
                                                List.of(new EventRequestCmds.SetRequest.ClassMatch(wrap("build" +
                                                        ".tools.jdwpgen.CodeGeneration$genToString$1$*"))))),
                                        new jdwp.EventRequestCmds.SetReply(16165, wrap(45)))))
                },
                {
                        "((= cause (request EventRequest Clear (\"eventKind\")=(wrap \"byte\" 1) (\"requestID\")=" +
                                "(wrap \"int\" 77)))\n" +
                                "  (= var0 (request EventRequest Clear (\"eventKind\")=(wrap \"byte\" 1) " +
                                "(\"requestID\")=(wrap \"int\" 77)))\n" +
                                "  (= var1 (request Method LineTable (\"methodID\")=(wrap \"method\" 105553155251400)" +
                                " (\"refType\")=(wrap \"klass\" 1055))))",
                        new Partition(Either.left(new jdwp.EventRequestCmds.ClearRequest(37962,
                                wrap((byte) 1), wrap(77))), List.of(
                                p(new jdwp.EventRequestCmds.ClearRequest(37962, wrap((byte) 1),
                                        wrap(77)), new jdwp.EventRequestCmds.ClearReply(37962)),
                                p(new jdwp.MethodCmds.LineTableRequest(37963, new ClassReference(1055L),
                                                new MethodReference(105553155251400L)),
                                        new jdwp.MethodCmds.LineTableReply(37963, wrap((long) 0),
                                                wrap((long) 7), new ListValue<>(Type.LIST,
                                                List.of(new MethodCmds.LineTableReply.LineInfo(wrap((long) 0),
                                                                wrap(6)),
                                                        new MethodCmds.LineTableReply.LineInfo(wrap((long) 2),
                                                                wrap(8))))))))
                },
                {
                        "((= cause (request VirtualMachine IDSizes)) (= var0 (request VirtualMachine IDSizes)) " +
                                "(= var1 (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" " +
                                "\"test\"))))",
                        new Partition(Either.left(new jdwp.VirtualMachineCmds.IDSizesRequest(0)), List.of(
                                p(new jdwp.VirtualMachineCmds.IDSizesRequest(0),
                                        new jdwp.VirtualMachineCmds.IDSizesReply(0, wrap(8),
                                                wrap(8), wrap(8),
                                                wrap(8), wrap(8))),
                                p(new jdwp.VirtualMachineCmds.ClassesBySignatureRequest(1,
                                                wrap("test")),
                                        new jdwp.VirtualMachineCmds.ClassesBySignatureReply(1,
                                                new ListValue<>(Type.LIST, List.of())))))
                },
                {
                        "((= cause (events Event Composite (\"events\" 0 \"kind\")=(wrap \"string\" \"ClassUnload\") " +
                                "(\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                                "\"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"signature\")=(wrap \"string\" " +
                                "\"sig\"))) (= var0" +
                                " (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" " +
                                "\"test\"))))",
                        new Partition(Either.right(new jdwp.EventCmds.Events(0, wrap((byte) 2),
                                new ListValue<>(Type.LIST,
                                        List.of(new EventCmds.Events.ClassUnload(wrap(0),
                                                wrap("sig")))))), List.of(
                                p(new jdwp.VirtualMachineCmds.ClassesBySignatureRequest(0,
                                                wrap("test")),
                                        new jdwp.VirtualMachineCmds.ClassesBySignatureReply(0,
                                                new ListValue<>(Type.LIST, List.of())))))
                }
        };
    }

    /**
     * partitions from real debugging runs
     */
    @ParameterizedTest
    @MethodSource("realPartitionsTestSource")
    public void testRealPartitions(String expectedProgram, Partition partition) {
        assertSynthesizedProgram(expectedProgram, partition);
    }

    private static void assertSynthesizedProgram(String expectedProgram, Partition partition) {
        assertEquals(Program.parse(expectedProgram).toPrettyString(),
                Synthesizer.synthesizeProgram(partition).toPrettyString());
    }

    @Test
    public void testRemoveDuplicatesInPartition() {
        Function<Integer, Pair<Request<?>, Reply>> func = id -> p(new jdwp.VirtualMachineCmds.IDSizesRequest(id),
                new jdwp.VirtualMachineCmds.IDSizesReply(id, wrap(8), wrap(8),
                        wrap(8), wrap(8), wrap(8)));
        var partition = new Partition(Either.left(func.apply(1).first),
                List.of(func.apply(1), func.apply(3), func.apply(4)));
        assertEquals("((= cause (request VirtualMachine IDSizes)) (= var0 (request VirtualMachine IDSizes)))",
                Synthesizer.synthesizeProgram(partition).toString());
    }

    @Test
    public void testRealPartition() {
        var partition = new Partition(Either.left(new jdwp.ThreadReferenceCmds.NameRequest(425824,
                new ThreadReference(1136L))), List.of(
                p(new jdwp.ThreadReferenceCmds.NameRequest(425824, new ThreadReference(1136L)),
                        new jdwp.ThreadReferenceCmds.NameReply(425824, PrimitiveValue.wrap("process reaper"))),
                p(new jdwp.ThreadReferenceCmds.ThreadGroupRequest(425825, new ThreadReference(1136L)),
                        new jdwp.ThreadReferenceCmds.ThreadGroupReply(425825, new ThreadGroupReference(2L))),
                p(new jdwp.ThreadReferenceCmds.StatusRequest(425826, new ThreadReference(1136L)),
                        new jdwp.ThreadReferenceCmds.StatusReply(425826, PrimitiveValue.wrap(1),
                                PrimitiveValue.wrap(1))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(425827, new ThreadReference(1136L),
                        PrimitiveValue.wrap(0), PrimitiveValue.wrap(1)),
                        new jdwp.ThreadReferenceCmds.FramesReply(425827, new ListValue<>(Type.LIST,
                                List.of(new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(65536L),
                                        new Location(new ClassTypeReference(110L),
                                                new MethodReference(105553119273528L),
                                                PrimitiveValue.wrap((long) -1))))))),
                p(new jdwp.ThreadReferenceCmds.NameRequest(425828, new ThreadReference(1137L)),
                        new jdwp.ThreadReferenceCmds.NameReply(425828, PrimitiveValue.wrap("process reaper")))
        ));

        var program = Synthesizer.synthesizeProgram(partition);
    }

    @Test
    public void testMultiplePathsToSameValue() {
        Function<Integer, Pair<Request<?>, Reply>> func = id -> p(new jdwp.VirtualMachineCmds.IDSizesRequest(id),
                new jdwp.VirtualMachineCmds.IDSizesReply(id, wrap(8), wrap(8),
                        wrap(8), wrap(8), wrap(8)));
        var partition = new Partition(Either.left(func.apply(1).first),
                List.of(func.apply(1),
                        p(new InstancesRequest(0, klass(110L), wrap(8)),
                                new InstancesReply(0, new ListValue<>(Type.OBJECT)))));
        var dep = DependencyGraph.compute(partition, DEFAULT_OPTIONS.withCheckPropertyNames(false));
        assertEquals("((= cause (request VirtualMachine IDSizes)) " +
                        "(= var0 (request VirtualMachine IDSizes)) (= var1 " +
                        "(request ReferenceType Instances (\"maxInstances\")=(get var0 \"fieldIDSize\") (\"refType\")" +
                        "=(wrap \"klass\" 110))))",
                Synthesizer.synthesizeProgram(dep).toString());
    }

    @Test
    public void testMultiplePathsToSameValue2() {
        Function<Integer, Pair<Request<?>, Reply>> func = id -> p(new jdwp.VirtualMachineCmds.IDSizesRequest(id),
                new jdwp.VirtualMachineCmds.IDSizesReply(id, wrap(8), wrap(8),
                        wrap(8), wrap(8), wrap(8)));
        var partition = new Partition(Either.left(func.apply(1).first),
                List.of(func.apply(1),
                        p(new ClassFileVersionRequest(2, klass(110L)),
                                new ClassFileVersionReply(2, wrap(8), wrap(8))),
                        p(new InstancesRequest(3, klass(110L), wrap(8)),
                                new InstancesReply(3, new ListValue<>(Type.OBJECT)))));
        var dep = DependencyGraph.compute(partition, DEFAULT_OPTIONS.withCheckPropertyNames(false));
        System.out.println(Synthesizer.synthesizeProgram(dep).toPrettyString());
        assertEquals("((= cause (request VirtualMachine IDSizes))\n" +
                        "  (= var0 (request VirtualMachine IDSizes))\n" +
                        "  (= var1 (request ReferenceType ClassFileVersion (\"refType\")=(wrap \"klass\" 110)))\n" +
                        "  (= var2 (request ReferenceType Instances (\"maxInstances\")=(get var1 \"majorVersion\") " +
                        "(\"refType\")=(wrap \"klass\" 110))))",
                Synthesizer.synthesizeProgram(dep).toPrettyString());
    }

    @Test
    public void testBasicTransformerUsage() {
        Function<Integer, Pair<Request<?>, Reply>> func = id -> p(new jdwp.VirtualMachineCmds.IDSizesRequest(id),
                new jdwp.VirtualMachineCmds.IDSizesReply(id, wrap(8), wrap(8),
                        wrap(8), wrap(8), wrap(8)));
        var partition = new Partition(Either.left(func.apply(1).first),
                List.of(func.apply(1), p(new GetValuesRequest(2, Reference.thread(1L), Reference.frame(1L),
                                new ListValue<>(new SlotInfo(wrap(1), wrap((byte) Type.INT.getTag())))),
                        new GetValuesReply(2, new ListValue<>(Type.LIST, List.of(wrap(1)))))));
        var graph = DependencyGraph.compute(partition,
                DEFAULT_OPTIONS.withCheckPropertyNames(false).withUseTransformers(true));
        assertEquals("((= cause (request VirtualMachine IDSizes))\n" +
                "  (= var0 (request VirtualMachine IDSizes))\n" +
                "  (= var1 (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 1) (\"thread\")=(wrap \"thread\"" +
                " 1) (\"slots\" 0 \"sigbyte\")=(getTagForValue (get var0 \"fieldIDSize\")) (\"slots\" 0 \"slot\")=" +
                "(wrap \"int\" 1))))", Synthesizer.synthesizeProgram(graph).toPrettyString());
    }

    /**
     * it is kind of difficult, as all slots have the same type
     * (see {@link DependencyGraphTest#getGetValuesRequestPartition})
     */
    @Test
    public void testMapCallSynthesisForGetValuesRequest() {
        var partition = getGetValuesRequestPartition(2);
        var graph = DependencyGraph.compute(partition, DEFAULT_OPTIONS);
        var program = Synthesizer.synthesizeProgram(graph,
                Synthesizer.DEFAULT_OPTIONS.withMapCallStatements(true));
        System.out.println(program.toPrettyString());
        assertEquals(Program.parse("(\n" +
                "  (= var0 (request Method VariableTable (\"methodID\")=(wrap \"method\" 32505856) (\"refType\")=" +
                "(wrap \"klass\" 10)))\n" +
                "  (map map0 (get var0 \"slots\") 0 iter0 (\"sigbyte\")=(getTagForSignature (get iter0 \"signature\")" +
                ") (\"slot\")=(get iter0 \"slot\"))\n" +
                "  (= var1 (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 1) (\"slots\")=map0 (\"thread\")" +
                "=(wrap \"thread\" 1))))").toPrettyString(), program.toPrettyString());
        // now check that the evaluation works
        var funcs = new RecordingFunctions() {
            @Override
            protected Value processRequest(Request<?> request) {
                requests.add(request);
                return partition.stream().filter(p -> p.first.equals(request)).findFirst().get().second.asCombined();
            }
        };
        new Evaluator(new VM(1), funcs, Throwable::printStackTrace).evaluate(program);
        assertEquals(List.of(partition.get(0).first, partition.get(1).first), funcs.requests);
    }

    @Test
    public void testRecursionWithLoopSynthesis() {
        /*
        Idea: introduce loop in the following program:
  (= var19 (request ReferenceType Interfaces ("refType")=(get var16 "typeID")))
  (= var20 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 4)))
  (= var21 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 3)))
  (= var22 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 2)))
  (= var23 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 1)))
  (= var24 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 0))))
         */
        BiFunction<Integer, List<Long>, Pair<InterfacesRequest, InterfacesReply>> interfacesCreator = (id,
                                                                                                       interfaces) ->
                p(new InterfacesRequest(id, klass(id)),
                        new InterfacesReply(id, new ListValue<>(Type.OBJECT,
                                interfaces.stream().map(Reference::interfaceType).collect(Collectors.toList()))));
        var partition = new Partition(null, List.of(
                interfacesCreator.apply(1, List.of(2L, 3L, 4L, 5L)),
                interfacesCreator.apply(2, List.of(6L)),
                interfacesCreator.apply(3, List.of()),
                interfacesCreator.apply(4, List.of()),
                interfacesCreator.apply(5, List.of()),
                interfacesCreator.apply(6, List.of())));
        var program = Synthesizer.synthesizeProgram(partition, Synthesizer.DEFAULT_OPTIONS);
        assertEquals("(\n" +
                "  (rec recursion0 1000 var0 (request ReferenceType Interfaces (\"refType\")=(wrap \"klass\" 1))\n" +
                "    (for iter0 (get var0 \"interfaces\") \n" +
                "      (reccall var1 recursion0 (\"refType\")=iter0))))", program.toPrettyString());
    }

    @Test
    public void testRecursionSynthesis() {
        BiFunction<Integer, Long, Pair<SuperclassRequest, SuperclassReply>> interfacesCreator = (id, superClass) ->
                p(new SuperclassRequest(id, classType(id)), new SuperclassReply(id,
                        classType(superClass)));
        var partition = new Partition(null, List.of(
                interfacesCreator.apply(1, 2L),
                interfacesCreator.apply(2, 3L),
                interfacesCreator.apply(3, 0L)));
        var program = Synthesizer.synthesizeProgram(partition, Synthesizer.DEFAULT_OPTIONS);
        assertEquals("(\n" +
                "  (rec recursion0 1000 var0 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 1))\n" +
                "    (reccall var1 recursion0 (\"clazz\")=(get var0 \"superclass\"))))", program.toPrettyString());
    }

    @Test
    public void testTypeSwitchLoopSynthesis() {
        /*
        Idea: introduce type switch statements in the following program:

  (= var14 (request StackFrame GetValues ("frame")=(get var10 "frames" 0 "frameID")
    ("thread")=(get cause "events" 0 "thread")
    ("slots" 0 "sigbyte")=(wrap "byte" 91) ("slots" 0 "slot")=(get var8 "slots" 0 "slot")
    ("slots" 1 "sigbyte")=(wrap "byte" 76) ("slots" 1 "slot")=(get var6 "frameCount")
    ("slots" 2 "sigbyte")=(wrap "byte" 73)
    ("slots" 2 "slot")=(get var8 "slots" 2 "slot")))
  (= var15 (request ObjectReference ReferenceType ("object")=(get var14 "values" 0)))
  (= var16 (request ObjectReference ReferenceType ("object")=(get var14 "values" 1)))
  (= var17 (request ArrayReference Length ("arrayObject")=(get var14 "values" 0)))
  (= var18 (request ClassType Superclass ("clazz")=(get var16 "typeID")))
  (= var19 (request ReferenceType Interfaces ("refType")=(get var16 "typeID")))
  (= var20 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 4)))
  (= var21 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 3)))
         */
        var arrayReference = Reference.array(1L);
        var objectReference = Reference.object(2L);
        var partition = new Partition(null, List.of(
                p(new GetValuesRequest(1, Reference.thread(1L), Reference.frame(1L),
                                new ListValue<>(new SlotInfo(wrap(1), wrap((byte) Type.INT.getTag())))),
                        new GetValuesReply(1, new ListValue<>(Type.LIST, List.of(wrap(1), wrap(2),
                                arrayReference, objectReference)))),
                p(new ReferenceTypeRequest(2, arrayReference),
                        new ReferenceTypeReply(2, wrap((byte) '['), klass(3L))),
                p(new ReferenceTypeRequest(3, objectReference),
                        new ReferenceTypeReply(3, wrap((byte) 'L'), klass(3L))),
                p(new LengthRequest(4, arrayReference), new LengthReply(4, wrap(0))),
                p(new SuperclassRequest(5, classType(3L)), new SuperclassReply(5, classType(4L)))
        ));
        var program = Synthesizer.synthesizeProgram(partition, Synthesizer.DEFAULT_OPTIONS);
        assertEquals("(\n" +
                        "  (= var0 (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 1) (\"thread\")=(wrap " +
                        "\"thread\" 1) (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 0 \"slot\")=(wrap " +
                        "\"int\" 1)))\n" +
                        "  (for iter0 (get var0 \"values\") \n" +
                        "    (switch (getTagForValue iter0)\n" +
                        "      (case (wrap \"byte\" 91)\n" +
                        "        (= var1 (request ObjectReference ReferenceType (\"object\")=iter0))\n" +
                        "        (= var2 (request ArrayReference Length (\"arrayObject\")=iter0))\n" +
                        "        (= var3 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\"))))\n" +
                        "      (case (wrap \"byte\" 76)\n" +
                        "        (= var1 (request ObjectReference ReferenceType (\"object\")=iter0))\n" +
                        "        (= var2 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\"))))\n" +
                        "      (default\n" +
                        "        (= var1 (request ObjectReference ReferenceType (\"object\")=iter0))\n" +
                        "        (= var2 (request ArrayReference Length (\"arrayObject\")=iter0))\n" +
                        "        (= var3 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\")))))))",
                program.toPrettyString());
        // can we parse it?
        Program.parse(program.toPrettyString());
    }

    @Test
    public void testTypeSwitchLoopSynthesisWithAbsentInformationError() {
        var arrayReference = Reference.array(1L);
        var objectReference = Reference.object(2L);
        var partition = new Partition(null, List.of(
                p(new GetValuesRequest(1, Reference.thread(1L), Reference.frame(1L),
                                new ListValue<>(new SlotInfo(wrap(1), wrap((byte) Type.INT.getTag())))),
                        new GetValuesReply(1, new ListValue<>(Type.LIST, List.of(wrap(1), wrap(2),
                                arrayReference, objectReference)))),
                p(new ReferenceTypeRequest(2, arrayReference),
                        new ReferenceTypeReply(2, wrap((byte) '['), klass(3L))),
                p(new ReferenceTypeRequest(3, objectReference),
                        new ReferenceTypeReply(3, wrap((byte) 'L'), klass(3L))),
                p(new LengthRequest(4, arrayReference), new LengthReply(4, wrap(0))),
                p(new SourceDebugExtensionRequest(5, klass(3L)),
                        new ReplyOrError<>(5, SourceDebugExtensionRequest.METADATA, (short) Error.ABSENT_INFORMATION)),
                p(new SuperclassRequest(6, classType(3L)), new SuperclassReply(6, classType(4L)))
        ));
        var program = Synthesizer.synthesizeProgram(partition, Synthesizer.DEFAULT_OPTIONS);
        assertEquals("(\n" +
                        "  (= var0 (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 1) (\"thread\")=(wrap " +
                        "\"thread\" 1) (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 0 \"slot\")=(wrap " +
                        "\"int\" 1)))\n" +
                        "  (for iter0 (get var0 \"values\") \n" +
                        "    (switch (getTagForValue iter0)\n" +
                        "      (case (wrap \"byte\" 91)\n" +
                        "        (= var1 (request ObjectReference ReferenceType (\"object\")=iter0))\n" +
                        "        (= var2 (request ArrayReference Length (\"arrayObject\")=iter0))\n" +
                        "        (= var3 (request ReferenceType SourceDebugExtension (\"refType\")=(get var1 " +
                        "\"typeID\")))\n" +
                        "        (= var4 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\"))))\n" +
                        "      (case (wrap \"byte\" 76)\n" +
                        "        (= var1 (request ObjectReference ReferenceType (\"object\")=iter0))\n" +
                        "        (= var2 (request ReferenceType SourceDebugExtension (\"refType\")=(get var1 " +
                        "\"typeID\")))\n" +
                        "        (= var3 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\"))))\n" +
                        "      (default\n" +
                        "        (= var1 (request ObjectReference ReferenceType (\"object\")=iter0))\n" +
                        "        (= var2 (request ArrayReference Length (\"arrayObject\")=iter0))\n" +
                        "        (= var3 (request ReferenceType SourceDebugExtension (\"refType\")=(get var1 " +
                        "\"typeID\")))\n" +
                        "        (= var4 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\")))))))",
                program.toPrettyString());
        // can we parse it?
        Program.parse(program.toPrettyString());
    }

    @Test
    public void testLoopLikePartitionWithDuplicates() {
        var partition = new Partition(Either.right(new jdwp.EventCmds.Events(5, PrimitiveValue.wrap((byte) 2),
                new ListValue<>(Type.LIST, List.of(new EventCmds.Events.Breakpoint(PrimitiveValue.wrap(41),
                        new ThreadReference(1L), new Location(new ClassTypeReference(1070L),
                        new MethodReference(105553126474952L), PrimitiveValue.wrap((long) 5))))))), List.of(
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(353, new ThreadReference(1L)),
                        new jdwp.ThreadReferenceCmds.FrameCountReply(353, PrimitiveValue.wrap(1))),
                p(new jdwp.StackFrameCmds.GetValuesRequest(367, new ThreadReference(1L), new FrameReference(131072L),
                                new ListValue<>(Type.LIST,
                                        List.of(new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(0),
                                                        PrimitiveValue.wrap((byte) 91)),
                                                new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(1),
                                                        PrimitiveValue.wrap((byte) 76)),
                                                new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(2),
                                                        PrimitiveValue.wrap((byte) 73))))),
                        new jdwp.StackFrameCmds.GetValuesReply(367, new ListValue<>(Type.LIST,
                                List.of(new ArrayReference(1072L), new ObjectReference(1073L),
                                        PrimitiveValue.wrap(0))))),
                p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(369, new ObjectReference(1073L)),
                        new jdwp.ObjectReferenceCmds.ReferenceTypeReply(369, PrimitiveValue.wrap((byte) 1),
                                new ClassReference(1052L))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(370, new ArrayReference(1072L)),
                        new jdwp.ArrayReferenceCmds.LengthReply(370, PrimitiveValue.wrap(0))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(372, new ArrayReference(1072L)),
                        new jdwp.ArrayReferenceCmds.LengthReply(372, PrimitiveValue.wrap(0)))));
        Synthesizer.synthesizeProgram(partition);
    }

    @Test
    public void testLargeSynthesis() {
        Partition partition = new Partition(Either.right(new jdwp.EventCmds.Events(5, wrap((byte) 2),
                new ListValue<>(Type.LIST, List.of(new EventCmds.Events.Breakpoint(wrap(42), thread(1L),
                        new Location(classType(1070L), method(105553136387016L), wrap(5L))))))), List.of(
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(20408, thread(1L)), new ReplyOrError<>(20408,
                        new jdwp.ThreadReferenceCmds.FrameCountReply(20408, wrap(1)))),
                p(new jdwp.ThreadReferenceCmds.NameRequest(20409, thread(1L)), new ReplyOrError<>(20409,
                        new jdwp.ThreadReferenceCmds.NameReply(20409, wrap("main")))),
                p(new jdwp.ThreadReferenceCmds.StatusRequest(20410, thread(1L)), new ReplyOrError<>(20410,
                        new jdwp.ThreadReferenceCmds.StatusReply(20410, wrap(1), wrap(1)))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(20411, thread(1L), wrap(0), wrap(1)),
                        new ReplyOrError<>(20411, new jdwp.ThreadReferenceCmds.FramesReply(20411,
                                new ListValue<>(Type.LIST,
                                List.of(new ThreadReferenceCmds.FramesReply.Frame(frame(131072L),
                                        new Location(classType(1070L),
                                        method(105553136387016L), wrap(5L)))))))),
                p(new jdwp.ThreadReferenceCmds.ThreadGroupRequest(20412, thread(1L)), new ReplyOrError<>(20412,
                        new jdwp.ThreadReferenceCmds.ThreadGroupReply(20412, threadGroup(1071L)))),
                p(new jdwp.ThreadGroupReferenceCmds.NameRequest(20413, threadGroup(1071L)), new ReplyOrError<>(20413,
                        new jdwp.ThreadGroupReferenceCmds.NameReply(20413, wrap("main")))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20414, classType(1070L)), new ReplyOrError<>(20414,
                        new jdwp.ClassTypeCmds.SuperclassReply(20414, classType(1058L)))),
                p(new jdwp.MethodCmds.IsObsoleteRequest(20415, klass(1070L), method(105553136387016L)),
                        new ReplyOrError<>(20415, new jdwp.MethodCmds.IsObsoleteReply(20415,
PrimitiveValue.wrap(false)))),
                p(new jdwp.ReferenceTypeCmds.FieldsWithGenericRequest(20416, klass(1070L)), new ReplyOrError<>(20416,
                        new jdwp.ReferenceTypeCmds.FieldsWithGenericReply(20416, new ListValue<>(Type.LIST,
                                List.of())))),
                p(new jdwp.MethodCmds.VariableTableWithGenericRequest(20417, klass(1070L), method(105553136387016L)),
                        new ReplyOrError<>(20417, new jdwp.MethodCmds.VariableTableWithGenericReply(20417, wrap(1),
                                new ListValue<>(Type.LIST,
List.of(new MethodCmds.VariableTableWithGenericReply.SlotInfo(wrap(0L),
                                                wrap("args"), wrap("[Ljava/lang/String;"), wrap(""), wrap(11), wrap(0)),
                                        new MethodCmds.VariableTableWithGenericReply.SlotInfo(wrap(3L), wrap("s"),
wrap("Ljava/lang" +
                                                "/String;"), wrap(""), wrap(8), wrap(1)),
                                        new MethodCmds.VariableTableWithGenericReply.SlotInfo(wrap(5L), wrap("i"),
                                                wrap("I"), wrap(""),
                                                wrap(6), wrap(2))))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20418, klass(1070L)), new ReplyOrError<>(20418,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20418, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.FieldsWithGenericRequest(20419, klass(1058L)), new ReplyOrError<>(20419,
                        new jdwp.ReferenceTypeCmds.FieldsWithGenericReply(20419, new ListValue<>(Type.LIST,
                         List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20420, classType(1058L)), new ReplyOrError<>(20420,
                        new jdwp.ClassTypeCmds.SuperclassReply(20420, classType(0L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20421, klass(1058L)), new ReplyOrError<>(20421,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20421, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.StackFrameCmds.GetValuesRequest(20422, thread(1L), frame(131072L),
                                new ListValue<>(Type.LIST, List.of(new StackFrameCmds.GetValuesRequest.SlotInfo(wrap(0),
                                                wrap((byte) 91)),
                                        new StackFrameCmds.GetValuesRequest.SlotInfo(wrap(1), wrap((byte) 76)),
                                        new StackFrameCmds.GetValuesRequest.SlotInfo(wrap(2), wrap((byte) 73))))),
                        new ReplyOrError<>(20422, new jdwp.StackFrameCmds.GetValuesReply(20422,
                         new ListValue<>(Type.LIST
                                , List.of(array(1072L), object(1073L), wrap(0)))))),
                p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(20423, object(1072L)), new ReplyOrError<>(20423,
                        new jdwp.ObjectReferenceCmds.ReferenceTypeReply(20423, wrap((byte) 3), klass(920L)))),
                p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(20424, object(1073L)), new ReplyOrError<>(20424,
                        new jdwp.ObjectReferenceCmds.ReferenceTypeReply(20424, wrap((byte) 1), klass(1052L)))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(20425, array(1072L)), new ReplyOrError<>(20425,
                        new jdwp.ArrayReferenceCmds.LengthReply(20425, wrap(0)))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(20426, array(1072L)), new ReplyOrError<>(20426,
                        new jdwp.ArrayReferenceCmds.LengthReply(20426, wrap(0)))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(20427, array(1072L)), new ReplyOrError<>(20427,
                        new jdwp.ArrayReferenceCmds.LengthReply(20427, wrap(0)))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20428, classType(1052L)), new ReplyOrError<>(20428,
                        new jdwp.ClassTypeCmds.SuperclassReply(20428, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20429, klass(1052L)), new ReplyOrError<>(20429,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20429, new ListValue<>(Type.LIST,
                                List.of(interfaceType(1057L), interfaceType(1056L), interfaceType(1055L),
                                 interfaceType(1054L),
                                        interfaceType(1053L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20432, classType(1052L)), new ReplyOrError<>(20432,
                        new jdwp.ClassTypeCmds.SuperclassReply(20432, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20433, klass(1052L)), new ReplyOrError<>(20433,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20433, new ListValue<>(Type.LIST,
                                List.of(interfaceType(1057L), interfaceType(1056L), interfaceType(1055L),
                                        interfaceType(1054L),
                                        interfaceType(1053L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20434, classType(1052L)), new ReplyOrError<>(20434,
                        new jdwp.ClassTypeCmds.SuperclassReply(20434, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20435, klass(1052L)), new ReplyOrError<>(20435,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20435, new ListValue<>(Type.LIST,
                                List.of(interfaceType(1057L), interfaceType(1056L), interfaceType(1055L),
                                        interfaceType(1054L),
                                        interfaceType(1053L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20445, klass(1057L)), new ReplyOrError<>(20445,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20445, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20446, klass(1056L)), new ReplyOrError<>(20446,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20446, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20458, klass(1054L)), new ReplyOrError<>(20458,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20458, new ListValue<>(Type.LIST, List.of())))),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20459, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20454, klass(1053L)), new ReplyOrError<>(20454,
        assertEquals("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")" +
                        "=(wrap \"string\" \"Breakpoint\") (\"events\" 0 \"requestID\")=(wrap \"int\" 42) (\"events\" 0 \"thread\")=" +
                        "(wrap \"thread\" 1) (\"events\" 0 \"location\" \"codeIndex\")=(wrap \"long\" 5) (\"events\" 0 \"location\"" +
           " \"declaringType\")=(wrap \"class-type\" 1070) (\"events\" 0 \"location\" \"methodRef\")=(wrap \"method\"" +
            " 105553136387016)))\n" +
                        "  (= var0 (request ReferenceType Interfaces (\"refType\")=(get cause \"events\" 0 " +
                         "\"location\" \"declaringType\")))\n" +
                        "  (= var1 (request ReferenceType FieldsWithGeneric (\"refType\")=(get cause \"events\" 0 " +
                         "\"location\" \"declaringType\")))\n" +
                        "  (rec recursion0 1000 var2 (request ClassType Superclass (\"clazz\")=(get cause \"events\" " +
                         "0 \"location\" \"declaringType\"))\n" +
                        "    (= var3 (request ReferenceType Interfaces (\"refType\")=(get var2 \"superclass\")))\n" +
                        "    (= var4 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var2 \"superclass\")" +
                         "))\n" +
                        "    (reccall var5 recursion0 (\"clazz\")=(get var2 \"superclass\")))\n" +
                        "  (= var4 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                        "  (= var5 (request ThreadReference Status (\"thread\")=(get cause \"events\" 0 \"thread\")))" +
                         "\n" +
                        "  (= var6 (request ThreadReference ThreadGroup (\"thread\")=(get cause \"events\" 0 " +
                         "\"thread\")))\n" +
                        "  (= var7 (request ThreadReference FrameCount (\"thread\")=(get cause \"events\" 0 " +
                         "\"thread\")))\n" +
                        "  (= var8 (request ThreadReference Frames (\"length\")=(get var7 \"frameCount\") " +
                         "(\"startFrame\")=(wrap \"int\" 0) (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                        "  (= var9 (request ThreadGroupReference Name (\"group\")=(get var6 \"group\")))\n" +
                        "  (= var10 (request Method IsObsolete (\"methodID\")=(get var8 \"frames\" 0 \"location\" " +
                         "\"methodRef\") (\"refType\")=(get cause \"events\" 0 \"location\" \"declaringType\")))\n" +
                        "  (= var11 (request Method VariableTableWithGeneric (\"methodID\")=(get var8 \"frames\" 0 " +
                         "\"location\" \"methodRef\") (\"refType\")=(get cause \"events\" 0 \"location\" " +
                          "\"declaringType\")))\n" +
                        "  (map map0 (get var11 \"slots\") 0 iter1 (\"sigbyte\")=(getTagForSignature (get iter1 " +
                         "\"signature\")) (\"slot\")=(get iter1 \"slot\"))\n" +
                        "  (= var12 (request StackFrame GetValues (\"frame\")=(get var8 \"frames\" 0 \"frameID\") " +
                         "(\"slots\")=map0 (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                        "  (for iter0 (get var12 \"values\") \n" +
                        "    (switch (getTagForValue iter0)\n" +
                        "      (case (wrap \"byte\" 91)\n" +
                        "        (= var13 (request ObjectReference ReferenceType (\"object\")=iter0))\n" +
                        "        (= var14 (request ArrayReference Length (\"arrayObject\")=iter0)))\n" +
                        "      (case (wrap \"byte\" 76)\n" +
                        "        (= var13 (request ObjectReference ReferenceType (\"object\")=iter0))\n" +
                        "        (= var15 (request ReferenceType Interfaces (\"refType\")=(get var13 \"typeID\")))\n" +
                        "        (for iter2 (get var15 \"interfaces\") \n" +
                        "          (= var16 (request ReferenceType Interfaces (\"refType\")=iter2)))\n" +
                        "        (= var16 (request ClassType Superclass (\"clazz\")=(get var13 \"typeID\"))))\n" +
                        "      (default\n" +
                        "        (= var13 (request ObjectReference ReferenceType (\"object\")=iter0))))))",
                Synthesizer.synthesizeProgram(partition).toPrettyString());
    }

    @Test
    public void testMapSynthesisInGetValuesWithGenerics() {
        Partition partition = new Partition(null, List.of(
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(20408, new ThreadReference(1L)),
                        new jdwp.ThreadReferenceCmds.FrameCountReply(20408, PrimitiveValue.wrap(1))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(20411, new ThreadReference(1L), PrimitiveValue.wrap(0),
                        PrimitiveValue.wrap(1)), new
                        jdwp.ThreadReferenceCmds.FramesReply(20411, new ListValue<>(Type.LIST,
                        List.of(new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(131072L), new Location
                                (new ClassTypeReference(1070L), new MethodReference(105553136387016L),
                                        PrimitiveValue.wrap((long) 5))))))),
                p(new jdwp.MethodCmds.VariableTableWithGenericRequest(20417, new ClassReference(1070L),
                        new MethodReference(105553136387016L)), new
                        jdwp.MethodCmds.VariableTableWithGenericReply(20417, PrimitiveValue.wrap(1),
                        new ListValue<>(Type.LIST, List.of(new
                                        MethodCmds.VariableTableWithGenericReply.SlotInfo(PrimitiveValue.wrap((long) 0),
                                        PrimitiveValue.wrap("args"), PrimitiveValue.wrap("[Ljava/lang/String;"),
                                        PrimitiveValue.wrap(""), PrimitiveValue.wrap(11), PrimitiveValue.wrap(0)),
                                new MethodCmds.VariableTableWithGenericReply.SlotInfo(PrimitiveValue.wrap((long) 3),
                                        PrimitiveValue.wrap("s"), PrimitiveValue.wrap("Ljava/lang/String;"),
                                        PrimitiveValue.wrap(""),
                                        PrimitiveValue.wrap(8), PrimitiveValue.wrap(1)), new
                                        MethodCmds.VariableTableWithGenericReply.SlotInfo(PrimitiveValue.wrap((long) 5),
                                        PrimitiveValue.wrap("i"), PrimitiveValue.wrap("I"), PrimitiveValue.wrap(""),
                                        PrimitiveValue.wrap(6), PrimitiveValue.wrap(2)))))),
                p(new jdwp.StackFrameCmds.GetValuesRequest(20422, new ThreadReference(1L),
                        new FrameReference(131072L), new ListValue<>(Type.LIST, List.of(new
                                StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(0),
                                PrimitiveValue.wrap((byte) 91)),
                        new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(
                                1), PrimitiveValue.wrap((byte) 76)),
                        new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(2),
                                PrimitiveValue.wrap((byte) 73))))), new
                        jdwp.StackFrameCmds.GetValuesReply(20422, new ListValue<>(Type.LIST,
                        List.of(new ArrayReference(1072L), new ObjectReference(1073L), PrimitiveValue.wrap(0)))))));
        assertEquals("(\n" +
                "  (= var0 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                "  (= var1 (request ThreadReference Frames (\"length\")=(get var0 \"frameCount\") (\"startFrame\")=" +
                "(wrap \"int\" 0) (\"thread\")=(wrap \"thread\" 1)))\n" +
                "  (= var2 (request Method VariableTableWithGeneric (\"methodID\")=(get var1 \"frames\" 0 " +
                "\"location\" \"methodRef\") (\"refType\")=(get var1 \"frames\" 0 \"location\" \"declaringType\")))" +
                "\n" +
                "  (map map0 (get var2 \"slots\") 0 iter0 (\"sigbyte\")=(getTagForSignature (get iter0 \"signature\")" +
                ") (\"slot\")=(get iter0 \"slot\"))\n" +
                "  (= var3 (request StackFrame GetValues (\"frame\")=(get var1 \"frames\" 0 \"frameID\") (\"slots\")" +
                "=map0 (\"thread\")=(wrap \"thread\" 1))))", Synthesizer.synthesizeProgram(partition).toPrettyString());
    }

    @Test
    public void testWithInhomogenousList() {
        var partition = new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(2260891,
                PrimitiveValue.wrap((byte) 1), PrimitiveValue.wrap((byte) 2), new ListValue
                <>(Type.LIST, List.of(new EventRequestCmds.SetRequest.Step(new ThreadReference(1L),
                        PrimitiveValue.wrap(1), PrimitiveValue.wrap(0)), new
                        EventRequestCmds.SetRequest.ClassExclude(PrimitiveValue.wrap("com.sun.*")),
                new EventRequestCmds.SetRequest.ClassExclude(PrimitiveValue.wrap("org.codehaus.groovy.*")), new
                        EventRequestCmds.SetRequest.ClassExclude(PrimitiveValue.wrap("groovy.*")),
                new EventRequestCmds.SetRequest.Count(PrimitiveValue.wrap(1)))))), List.of(
                p(new jdwp.EventRequestCmds.SetRequest(2260891, PrimitiveValue.wrap((byte) 1),
                                PrimitiveValue.wrap((byte) 2), new ListValue<>(Type.LIST, List.of(new
                                        EventRequestCmds.SetRequest.Step(new ThreadReference(1L),
                                        PrimitiveValue.wrap(1),
                                        PrimitiveValue.wrap(0)), new EventRequestCmds.SetRequest.ClassExclude(
                                        PrimitiveValue.wrap("com.sun.*")), new EventRequestCmds.SetRequest.ClassExclude(
                                        PrimitiveValue.wrap("org.codehaus.groovy.*")),
                                new EventRequestCmds.SetRequest.ClassExclude(PrimitiveValue.wrap("groovy.*")), new
                                        EventRequestCmds.SetRequest.Count(PrimitiveValue.wrap(1))))),
                        new ReplyOrError<>(2260891,
                                new jdwp.EventRequestCmds.SetReply(2260891, PrimitiveValue.wrap(85)))),
                p(new jdwp.ThreadReferenceCmds.NameRequest(2260894, new ThreadReference(1L)),
                        new ReplyOrError<>(2260894, new jdwp.ThreadReferenceCmds.NameReply(2260894,
                                PrimitiveValue.wrap("main")))),
                p(new jdwp.ThreadReferenceCmds.StatusRequest(2260895, new ThreadReference(1L)),
                        new ReplyOrError<>(2260895, new jdwp.ThreadReferenceCmds.StatusReply(2260895,
                                PrimitiveValue.wrap(1), PrimitiveValue.wrap(1)))),
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(2260893, new ThreadReference(1L)),
                        new ReplyOrError<>(2260893, new jdwp.ThreadReferenceCmds.FrameCountReply(2260893,
                                PrimitiveValue.wrap(3)))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(2260896, new ThreadReference(1L), PrimitiveValue.wrap(0)
                        , PrimitiveValue.wrap(1)), new ReplyOrError<>(2260896, new
                        jdwp.ThreadReferenceCmds.FramesReply(2260896, new ListValue<>(Type.LIST,
                        List.of(new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(2555904L), new
                                Location(new ClassTypeReference(1079L), new MethodReference(5257610320L),
                                PrimitiveValue.wrap((long) 6))))))))));
        assertEquals("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 1) (\"suspendPolicy\")=(wrap" +
                        " \"byte\" 2) (\"modifiers\" 0 \"depth\")=(wrap \"int\" 0) (\"modifiers\" 0 \"kind\")=(wrap " +
                        "\"string\" \"Step\") (\"modifiers\" 0 \"size\")=(wrap \"int\" 1) (\"modifiers\" 0 " +
                        "\"thread\")=(wrap \"thread\" 1) (\"modifiers\" 1 \"classPattern\")=(wrap \"string\" \"com" +
                        ".sun.*\") (\"modifiers\" 1 \"kind\")=(wrap \"string\" \"ClassExclude\") (\"modifiers\" 2 " +
                        "\"classPattern\")=(wrap \"string\" \"org.codehaus.groovy.*\") (\"modifiers\" 2 \"kind\")=" +
                        "(wrap \"string\" \"ClassExclude\") (\"modifiers\" 3 \"classPattern\")=(wrap \"string\" " +
                        "\"groovy.*\") (\"modifiers\" 3 \"kind\")=(wrap \"string\" \"ClassExclude\") (\"modifiers\" 4" +
                        " \"count\")=(wrap \"int\" 1) (\"modifiers\" 4 \"kind\")=(wrap \"string\" \"Count\")))\n" +
                        "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 1) (\"suspendPolicy\")=" +
                        "(wrap \"byte\" 2) (\"modifiers\" 0 \"depth\")=(wrap \"int\" 0) (\"modifiers\" 0 \"kind\")=" +
                        "(wrap \"string\" \"Step\") (\"modifiers\" 0 \"size\")=(wrap \"int\" 1) (\"modifiers\" 0 " +
                        "\"thread\")=(wrap \"thread\" 1) (\"modifiers\" 1 \"classPattern\")=(wrap \"string\" \"com" +
                        ".sun.*\") (\"modifiers\" 1 \"kind\")=(wrap \"string\" \"ClassExclude\") (\"modifiers\" 2 " +
                        "\"classPattern\")=(wrap \"string\" \"org.codehaus.groovy.*\") (\"modifiers\" 2 \"kind\")=" +
                        "(wrap \"string\" \"ClassExclude\") (\"modifiers\" 3 \"classPattern\")=(wrap \"string\" " +
                        "\"groovy.*\") (\"modifiers\" 3 \"kind\")=(wrap \"string\" \"ClassExclude\") (\"modifiers\" 4" +
                        " \"count\")=(wrap \"int\" 1) (\"modifiers\" 4 \"kind\")=(wrap \"string\" \"Count\")))\n" +
                        "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"modifiers\" 0 \"thread\"))" +
                        ")\n" +
                        "  (= var2 (request ThreadReference Status (\"thread\")=(get cause \"modifiers\" 0 " +
                        "\"thread\")))\n" +
                        "  (= var3 (request ThreadReference Frames (\"length\")=(wrap \"int\" 1) (\"startFrame\")=" +
                        "(wrap \"int\" 0) (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                        "  (= var4 (request ThreadReference FrameCount (\"thread\")=(get cause \"modifiers\" 0 " +
                        "\"thread\"))))",
                Synthesizer.synthesizeProgram(partition).toPrettyString());
    }


    @Test
                PrimitiveValue.wrap((byte) 40), PrimitiveValue.wrap((byte) 1), new ListValue<>(Type.LIST,
                List.of(new EventRequestCmds.SetRequest.ThreadOnly(new ThreadReference(1L)))))), List.of(
                                PrimitiveValue.wrap((byte) 1), new ListValue<>(Type.LIST,
                                List.of(new EventRequestCmds.SetRequest.ThreadOnly(new ThreadReference(1L))))),
                                "main")))),
                                PrimitiveValue.wrap(1)
                                , PrimitiveValue.wrap(1)))),
                                                new Location(new ClassTypeReference(1079L),
                                                        new MethodReference(5099358456L),
                                                new Location(new ClassTypeReference(1079L),
                                                        new MethodReference(5099358520L),
                                                        PrimitiveValue.wrap((long) 11))),
                                                new Location(new ClassTypeReference(1077L),
                                                        new MethodReference(105553141105552L),
                                                        PrimitiveValue.wrap((long) 8))),
                                                new Location(new ClassTypeReference(1070L),
                                                        new MethodReference(105553141105544L),
                                                        PrimitiveValue.wrap((long) 14)))))))),
                        List.of(new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(1),
                                new ListValue<>(Type.LIST,

    /**
     * Test that there is no hash collision. This program could also be used for performance optimization
     */
    @Test
    public void testHashCollisionAndRecursionsInLargePartition() {
        var partition = new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(2276552, wrap((byte) 40),
                wrap((byte) 1), new ListValue<>(Type.LIST, List.of(new EventRequestCmds.SetRequest.ThreadOnly(thread(1L)))))), List.of(
                p(new jdwp.EventRequestCmds.SetRequest(2276552, wrap((byte) 40), wrap((byte) 1),
new ListValue<>(Type.LIST, List.of(new EventRequestCmds.SetRequest.ThreadOnly(thread(1L))))),
                  new ReplyOrError<>(2276552, new jdwp.EventRequestCmds.SetReply(2276552, wrap(121)))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276558, klass(961L)),
                 new ReplyOrError<>(2276558, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276558,
                  new ListValue<>(Type.LIST,
                   List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159160L), wrap(
                           "<init>"), wrap("(I)V"), wrap(""), wrap(1)),
                            new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159144L), wrap(
                                    "<init>"), wrap("()V"), wrap(""), wrap(1)),
                                     new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159152L), wrap("<init>"), wrap("(Ljava/util/Collection;)V"), wrap("(Ljava/util/Collection<+TE;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792864L), wrap("trimToSize"), wrap("()V"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792856L), wrap("ensureCapacity"), wrap("(I)V"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792880L), wrap("grow"), wrap("(I)[Ljava/lang/Object;"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792872L), wrap("grow"), wrap("()[Ljava/lang/Object;"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553140937056L), wrap("size"), wrap("()I"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792736L), wrap("isEmpty"), wrap("()Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792784L), wrap("contains"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792712L), wrap("indexOf"), wrap("(Ljava/lang/Object;)I"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792888L), wrap("indexOfRange"), wrap("(Ljava/lang/Object;II)I"), wrap(""), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792728L), wrap("lastIndexOf"), wrap("(Ljava/lang/Object;)I"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792896L), wrap("lastIndexOfRange"), wrap("(Ljava/lang/Object;II)I"), wrap(""), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792704L), wrap("clone"), wrap("()Ljava/lang/Object;"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792760L), wrap("toArray"), wrap("()[Ljava/lang/Object;"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792768L), wrap("toArray"), wrap("([Ljava/lang/Object;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang/Object;>([TT;)[TT;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553140937072L), wrap("elementData"), wrap("(I)Ljava/lang/Object;"), wrap("(I)TE;"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792984L), wrap("elementAt"), wrap("([Ljava/lang/Object;I)Ljava/lang/Object;"), wrap("<E:Ljava/lang/Object;>([Ljava/lang/Object;I)TE;"), wrap(8)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553140937080L), wrap("get"), wrap("(I)Ljava/lang/Object;"), wrap("(I)TE;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792816L), wrap("set"), wrap("(ILjava/lang/Object;)Ljava/lang/Object;"), wrap("(ITE;)TE;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141039104L), wrap("add"), wrap("(Ljava/lang/Object;[Ljava/lang/Object;I)V"), wrap("(TE;[Ljava/lang/Object;I)V"), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141039112L), wrap("add"), wrap("(Ljava/lang/Object;)Z"), wrap("(TE;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792664L), wrap("add"), wrap("(ILjava/lang/Object;)V"), wrap("(ITE;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792680L), wrap("remove"), wrap("(I)Ljava/lang/Object;"), wrap("(I)TE;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792688L), wrap("equals"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792928L), wrap("equalsRange"), wrap("(Ljava/util/List;II)Z"), wrap("(Ljava/util/List<*>;II)Z"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792920L), wrap("equalsArrayList"), wrap("(Ljava/util/ArrayList;)Z"), wrap("(Ljava/util/ArrayList<*>;)Z"), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792936L), wrap("checkForComodification"), wrap("(I)V"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792696L), wrap("hashCode"), wrap("()I"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792944L), wrap("hashCodeRange"), wrap("(II)I"), wrap(""), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792672L), wrap("remove"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792912L), wrap("fastRemove"), wrap("([Ljava/lang/Object;I)V"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792720L), wrap("clear"), wrap("()V"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792808L), wrap("addAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<+TE;>;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792800L), wrap("addAll"), wrap("(ILjava/util/Collection;)Z"), wrap("(ILjava/util/Collection<+TE;>;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793040L), wrap("removeRange"), wrap("(II)V"), wrap(""), wrap(4)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792968L), wrap("shiftTailOverGap"), wrap("([Ljava/lang/Object;II)V"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792904L), wrap("rangeCheckForAdd"), wrap("(I)V"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792960L), wrap("outOfBoundsMsg"), wrap("(I)Ljava/lang/String;"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792952L), wrap("outOfBoundsMsg"), wrap("(II)Ljava/lang/String;"), wrap(""), wrap(10)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793048L), wrap("removeAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793056L), wrap("retainAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792976L), wrap("batchRemove"), wrap("(Ljava/util/Collection;ZII)Z"), wrap("(Ljava/util/Collection<*>;ZII)Z"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792832L), wrap("writeObject"), wrap("(Ljava/io/ObjectOutputStream;)V"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792824L), wrap("readObject"), wrap("(Ljava/io/ObjectInputStream;)V"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793072L), wrap("listIterator"), wrap("(I)Ljava/util/ListIterator;"), wrap("(I)Ljava/util/ListIterator<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793064L), wrap("listIterator"), wrap("()Ljava/util/ListIterator;"), wrap("()Ljava/util/ListIterator<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792776L), wrap("iterator"), wrap("()Ljava/util/Iterator;"), wrap("()Ljava/util/Iterator<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792752L), wrap("subList"), wrap("(II)Ljava/util/List;"), wrap("(II)Ljava/util/List<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792840L), wrap("forEach"), wrap("(Ljava/util/function/Consumer;)V"), wrap("(Ljava/util/function/Consumer<-TE;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792792L), wrap("spliterator"), wrap("()Ljava/util/Spliterator;"), wrap("()Ljava/util/Spliterator<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793008L), wrap("nBits"), wrap("(I)[J"), wrap(""), wrap(10)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793016L), wrap("setBit"), wrap("([JI)V"), wrap(""), wrap(10)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793024L), wrap("isClear"), wrap("([JI)Z"), wrap(""), wrap(10)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793000L), wrap("removeIf"), wrap("(Ljava/util/function/Predicate;)Z"), wrap("(Ljava/util/function/Predicate<-TE;>;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792992L), wrap("removeIf"), wrap("(Ljava/util/function/Predicate;II)Z"), wrap("(Ljava/util/function/Predicate<-TE;>;II)Z"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792744L), wrap("replaceAll"), wrap("(Ljava/util/function/UnaryOperator;)V"), wrap("(Ljava/util/function/UnaryOperator<TE;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793032L), wrap("replaceAllRange"), wrap("(Ljava/util/function/UnaryOperator;II)V"), wrap("(Ljava/util/function/UnaryOperator<TE;>;II)V"), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792848L), wrap("sort"), wrap("(Ljava/util/Comparator;)V"), wrap("(Ljava/util/Comparator<-TE;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829793080L), wrap("checkInvariants"), wrap("()V"), wrap(""), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4829792656L), wrap("<clinit>"), wrap("()V"), wrap(""), wrap(8))))))),
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(2276566, thread(1L)), new ReplyOrError<>(2276566,
                        new jdwp.ThreadReferenceCmds.FrameCountReply(2276566, wrap(4)))),
                p(new jdwp.ThreadReferenceCmds.NameRequest(2276567, thread(1L)), new ReplyOrError<>(2276567,
                        new jdwp.ThreadReferenceCmds.NameReply(2276567, wrap("main")))),
                p(new jdwp.ThreadReferenceCmds.StatusRequest(2276568, thread(1L)), new ReplyOrError<>(2276568,
                        new jdwp.ThreadReferenceCmds.StatusReply(2276568, wrap(1), wrap(1)))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(2276569, thread(1L), wrap(0), wrap(1)),
new ReplyOrError<>(2276569, new jdwp.ThreadReferenceCmds.FramesReply(2276569,
                  new ListValue<>(Type.LIST, List.of(new ThreadReferenceCmds.FramesReply.Frame(frame(5242880L),
                          new Location(classType(1079L), method(5099358456L), wrap(15L)))))))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(2276573, thread(1L), wrap(0), wrap(4)),
                        new ReplyOrError<>(2276573, new jdwp.ThreadReferenceCmds.FramesReply(2276573,
                  new ListValue<>(Type.LIST, List.of(new ThreadReferenceCmds.FramesReply.Frame(frame(5242880L),
                   new Location(classType(1079L), method(5099358456L), wrap(15L))),
                    new ThreadReferenceCmds.FramesReply.Frame(frame(5242881L), new Location(classType(1079L),
                     method(5099358520L), wrap(11L))), new ThreadReferenceCmds.FramesReply.Frame(frame(5242882L),
                                  new Location(classType(1077L), method(105553141105552L), wrap(8L))),
                          new ThreadReferenceCmds.FramesReply.Frame(frame(5242883L), new Location(classType(1070L),
                        method(105553141105544L), wrap(14L)))))))),
                p(new jdwp.StackFrameCmds.ThisObjectRequest(2276574, thread(1L), frame(5242880L)),
                        new ReplyOrError<>(2276574, new jdwp.StackFrameCmds.ThisObjectReply(2276574, object(1080L)))),
                p(new jdwp.StackFrameCmds.GetValuesRequest(2276575, thread(1L), frame(5242880L),
                 new ListValue<>(Type.LIST, List.of(new StackFrameCmds.GetValuesRequest.SlotInfo(wrap(1),
                  wrap((byte) 76))))), new ReplyOrError<>(2276575, new jdwp.StackFrameCmds.GetValuesReply(2276575,
                   new ListValue<>(Type.LIST, List.of(object(1115L)))))),
                p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(2276576, object(1115L)),
new ReplyOrError<>(2276576, new jdwp.ObjectReferenceCmds.ReferenceTypeReply(2276576, wrap((byte) 1),
 klass(961L)))),
                p(new jdwp.ObjectReferenceCmds.GetValuesRequest(2276577, object(1080L), new ListValue<>(Type.LIST,
                 List.of(new ObjectReferenceCmds.GetValuesRequest.Field(field(50L))))), new ReplyOrError<>(2276577,
                  new jdwp.ObjectReferenceCmds.GetValuesReply(2276577, new ListValue<>(Type.LIST, List.of(wrap(40)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276578, classType(961L)), new ReplyOrError<>(2276578,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276578, classType(962L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276579, klass(961L)), new ReplyOrError<>(2276579,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276579, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L), interfaceType(964L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276588, classType(961L)), new ReplyOrError<>(2276588,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276588, classType(962L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276589, klass(961L)), new ReplyOrError<>(2276589,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276589, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L), interfaceType(964L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276590, classType(961L)), new ReplyOrError<>(2276590,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276590, classType(962L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276591, klass(961L)), new ReplyOrError<>(2276591,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276591, new ListValue<>(Type.LIST,
                         List.of(interfaceType(965L), interfaceType(964L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276592, classType(961L)), new ReplyOrError<>(2276592,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276592, classType(962L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276593, klass(961L)), new ReplyOrError<>(2276593,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276593, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L), interfaceType(964L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276594, classType(961L)), new ReplyOrError<>(2276594,
new jdwp.ClassTypeCmds.SuperclassReply(2276594, classType(962L)))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276580, classType(961L)), new ReplyOrError<>(2276580,
new jdwp.ClassTypeCmds.SuperclassReply(2276580, classType(962L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276581, klass(961L)), new ReplyOrError<>(2276581,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276581, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L), interfaceType(964L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276582, classType(961L)), new ReplyOrError<>(2276582,
                        new jdwp.ClassTypeCmds.SuperclassReply(2276582, classType(962L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276583, klass(961L)), new ReplyOrError<>(2276583,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276583, new ListValue<>(Type.LIST,
List.of(interfaceType(965L), interfaceType(964L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276584, classType(961L)), new ReplyOrError<>(2276584,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276584, classType(962L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276585, klass(961L)), new ReplyOrError<>(2276585,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276585, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L), interfaceType(964L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276586, classType(961L)), new ReplyOrError<>(2276586,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276586, classType(962L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276587, klass(961L)), new ReplyOrError<>(2276587,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276587, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L), interfaceType(964L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276595, classType(962L)), new ReplyOrError<>(2276595,
                        new jdwp.ClassTypeCmds.SuperclassReply(2276595, classType(963L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276596, klass(962L)), new ReplyOrError<>(2276596,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276596, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276597, klass(965L)), new ReplyOrError<>(2276597,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276597, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276598, klass(964L)), new ReplyOrError<>(2276598,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276598, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276607, classType(962L)), new ReplyOrError<>(2276607,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276607, classType(963L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276608, klass(962L)), new ReplyOrError<>(2276608,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276608, new ListValue<>(Type.LIST,
List.of(interfaceType(965L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276609, klass(965L)), new ReplyOrError<>(2276609,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276609, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276610, klass(964L)), new ReplyOrError<>(2276610,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276610, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276599, klass(1045L)), new ReplyOrError<>(2276599,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276599, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276600, klass(1057L)), new ReplyOrError<>(2276600,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276600, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276613, classType(962L)), new ReplyOrError<>(2276613,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276613, classType(963L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276614, klass(962L)), new ReplyOrError<>(2276614,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276614, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276615, klass(965L)), new ReplyOrError<>(2276615,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276615, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276616, klass(964L)), new ReplyOrError<>(2276616,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276616, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276617, klass(1045L)), new ReplyOrError<>(2276617,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276617, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276618, klass(1057L)), new ReplyOrError<>(2276618,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276618, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276619, classType(962L)), new ReplyOrError<>(2276619,
                        new jdwp.ClassTypeCmds.SuperclassReply(2276619, classType(963L)))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276620, classType(962L)), new ReplyOrError<>(2276620,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276620, classType(963L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276621, klass(962L)), new ReplyOrError<>(2276621,
new jdwp.ReferenceTypeCmds.InterfacesReply(2276621, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276622, klass(965L)), new ReplyOrError<>(2276622,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276622, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276623, klass(964L)), new ReplyOrError<>(2276623,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276623, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276624, klass(1045L)), new ReplyOrError<>(2276624,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276624, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276625, klass(1057L)), new ReplyOrError<>(2276625,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276625, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276626, classType(962L)), new ReplyOrError<>(2276626,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276626, classType(963L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276627, klass(962L)), new ReplyOrError<>(2276627,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276627, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276628, klass(965L)), new ReplyOrError<>(2276628,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276628, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276629, klass(964L)), new ReplyOrError<>(2276629,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276629, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276630, klass(1045L)), new ReplyOrError<>(2276630,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276630, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276631, klass(1057L)), new ReplyOrError<>(2276631,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276631, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276632, classType(962L)), new ReplyOrError<>(2276632,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276632, classType(963L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276633, klass(962L)), new ReplyOrError<>(2276633,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276633, new ListValue<>(Type.LIST,
                         List.of(interfaceType(965L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276634, klass(965L)), new ReplyOrError<>(2276634,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276634, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276635, klass(964L)), new ReplyOrError<>(2276635,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276635, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276636, klass(1045L)), new ReplyOrError<>(2276636,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276636, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276637, klass(1057L)), new ReplyOrError<>(2276637,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276637, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276638, classType(962L)), new ReplyOrError<>(2276638,
 new jdwp.ClassTypeCmds.SuperclassReply(2276638, classType(963L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276639, klass(962L)), new ReplyOrError<>(2276639,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276639, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276640, klass(965L)), new ReplyOrError<>(2276640,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276640, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276641, klass(964L)), new ReplyOrError<>(2276641,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276641, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276642, klass(1045L)), new ReplyOrError<>(2276642,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276642, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276643, klass(1057L)), new ReplyOrError<>(2276643,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276643, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276601, classType(962L)), new ReplyOrError<>(2276601,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276601, classType(963L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276602, klass(962L)), new ReplyOrError<>(2276602,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276602, new ListValue<>(Type.LIST,
                  List.of(interfaceType(965L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276646, klass(965L)), new ReplyOrError<>(2276646,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276646, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276603, klass(965L)), new ReplyOrError<>(2276603,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276603, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276604, klass(964L)), new ReplyOrError<>(2276604,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276604, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276605, klass(1045L)), new ReplyOrError<>(2276605,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276605, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276606, klass(1057L)), new ReplyOrError<>(2276606,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276606, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276611, klass(1045L)), new ReplyOrError<>(2276611,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276611, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276612, klass(1057L)), new ReplyOrError<>(2276612,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276612, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276644, classType(963L)), new ReplyOrError<>(2276644,
new jdwp.ClassTypeCmds.SuperclassReply(2276644, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276645, klass(963L)), new ReplyOrError<>(2276645,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276645, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276647, klass(966L)), new ReplyOrError<>(2276647,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276647, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276656, classType(963L)), new ReplyOrError<>(2276656,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276656, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276657, klass(963L)), new ReplyOrError<>(2276657,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276657, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276658, classType(963L)), new ReplyOrError<>(2276658,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276658, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276659, klass(966L)), new ReplyOrError<>(2276659,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276659, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276660, klass(966L)), new ReplyOrError<>(2276660,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276660, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276661, classType(963L)), new ReplyOrError<>(2276661,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276661, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276662, klass(963L)), new ReplyOrError<>(2276662,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276662, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276663, klass(966L)), new ReplyOrError<>(2276663,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276663, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276664, klass(966L)), new ReplyOrError<>(2276664,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276664, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276665, classType(963L)), new ReplyOrError<>(2276665,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276665, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276666, klass(963L)), new ReplyOrError<>(2276666,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276666, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276667, klass(966L)), new ReplyOrError<>(2276667,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276667, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276668, klass(966L)), new ReplyOrError<>(2276668,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276668, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276669, classType(963L)), new ReplyOrError<>(2276669,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276669, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276670, klass(963L)), new ReplyOrError<>(2276670,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276670, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276671, klass(966L)), new ReplyOrError<>(2276671,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276671, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276672, klass(966L)), new ReplyOrError<>(2276672,
 new jdwp.ReferenceTypeCmds.InterfacesReply(2276672, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276673, classType(963L)), new ReplyOrError<>(2276673,
                 new jdwp.ClassTypeCmds.SuperclassReply(2276673, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276674, klass(963L)), new ReplyOrError<>(2276674,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276674, new ListValue<>(Type.LIST,
                  List.of(interfaceType(966L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276675, klass(966L)), new ReplyOrError<>(2276675,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276675, new ListValue<>(Type.LIST,
                         List.of(interfaceType(967L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276676, klass(966L)), new ReplyOrError<>(2276676,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276676, new ListValue<>(Type.LIST,
 List.of(interfaceType(967L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276677, klass(966L)), new ReplyOrError<>(2276677,
                 new jdwp.ReferenceTypeCmds.InterfacesReply(2276677, new ListValue<>(Type.LIST,
                  List.of(interfaceType(967L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276648, classType(963L)), new ReplyOrError<>(2276648,
new jdwp.ClassTypeCmds.SuperclassReply(2276648, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276712, klass(965L)),
                 new ReplyOrError<>(2276712, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276712,
                  new ListValue<>(Type.LIST,
                   List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417488L), wrap("size")
                   , wrap("()I"), wrap(""), wrap(1025)),
                    new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417472L), wrap("isEmpty"),
                            wrap("()Z"), wrap(""), wrap(1025)),
                      new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417624L), wrap("contains"),
                       wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)),
                        new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417520L),
                         wrap("iterator"), wrap("()Ljava/util/Iterator;"), wrap("()Ljava/util/Iterator<TE;>;"),
                          wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417504L),
                                   wrap("toArray"), wrap("()[Ljava/lang/Object;"), wrap(""), wrap(1025)),
                            new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417512L), wrap(
                                    "toArray"), wrap("([Ljava/lang/Object;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang" +
                                    "/Object;>([TT;)[TT;"), wrap(1025)),
                                      new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159264L), wrap("add"), wrap("(Ljava/lang/Object;)Z"), wrap("(TE;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159272L), wrap("remove"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417704L), wrap("containsAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417648L), wrap("addAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<+TE;>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417640L), wrap("addAll"), wrap("(ILjava/util/Collection;)Z"), wrap("(ILjava/util/Collection<+TE;>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417672L), wrap("removeAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417680L), wrap("retainAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417480L), wrap("replaceAll"), wrap("(Ljava/util/function/UnaryOperator;)V"), wrap("(Ljava/util/function/UnaryOperator<TE;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417664L), wrap("sort"), wrap("(Ljava/util/Comparator;)V"), wrap("(Ljava/util/Comparator<-TE;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417456L), wrap("clear"), wrap("()V"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417424L), wrap("equals"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417432L), wrap("hashCode"), wrap("()I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159288L), wrap("get"), wrap("(I)Ljava/lang/Object;"), wrap("(I)TE;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417656L), wrap("set"), wrap("(ILjava/lang/Object;)Ljava/lang/Object;"), wrap("(ITE;)TE;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159256L), wrap("add"), wrap("(ILjava/lang/Object;)V"), wrap("(ITE;)V"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159280L), wrap("remove"), wrap("(I)Ljava/lang/Object;"), wrap("(I)TE;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417448L), wrap("indexOf"), wrap("(Ljava/lang/Object;)I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417464L), wrap("lastIndexOf"), wrap("(Ljava/lang/Object;)I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417696L), wrap("listIterator"), wrap("()Ljava/util/ListIterator;"), wrap("()Ljava/util/ListIterator<TE;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417688L), wrap("listIterator"), wrap("(I)Ljava/util/ListIterator;"), wrap("(I)Ljava/util/ListIterator<TE;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417496L), wrap("subList"), wrap("(II)Ljava/util/List;"), wrap("(II)Ljava/util/List<TE;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417632L), wrap("spliterator"), wrap("()Ljava/util/Spliterator;"), wrap("()Ljava/util/Spliterator<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417560L), wrap("of"), wrap("()Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>()Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417552L), wrap("of"), wrap("(Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417544L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417536L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417528L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417592L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417584L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417576L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417568L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417608L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417600L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417616L), wrap("of"), wrap("([Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>([TE;)Ljava/util/List<TE;>;"), wrap(137)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417440L), wrap("copyOf"), wrap("(Ljava/util/Collection;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(Ljava/util/Collection<+TE;>;)Ljava/util/List<TE;>;"), wrap(9))))))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276713, klass(966L)),
                 new ReplyOrError<>(2276713, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276713,
                  new ListValue<>(Type.LIST,
                   List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385456L), wrap(
                           "size"), wrap("()I"), wrap(""), wrap(1025)),
                            new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385448L), wrap(
                                    "isEmpty"), wrap("()Z"), wrap(""), wrap(1025)),
                                     new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385504L), wrap("contains"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385488L), wrap("iterator"), wrap("()Ljava/util/Iterator;"), wrap("()Ljava/util/Iterator<TE;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385480L), wrap("toArray"), wrap("()[Ljava/lang/Object;"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385464L), wrap("toArray"), wrap("([Ljava/lang/Object;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang/Object;>([TT;)[TT;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385472L), wrap("toArray"), wrap("(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang/Object;>(Ljava/util/function/IntFunction<[TT;>;)[TT;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385408L), wrap("add"), wrap("(Ljava/lang/Object;)Z"), wrap("(TE;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385416L), wrap("remove"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385552L), wrap("containsAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385520L), wrap("addAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<+TE;>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385536L), wrap("removeAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385528L), wrap("removeIf"), wrap("(Ljava/util/function/Predicate;)Z"), wrap("(Ljava/util/function/Predicate<-TE;>;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385544L), wrap("retainAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385440L), wrap("clear"), wrap("()V"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385424L), wrap("equals"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385432L), wrap("hashCode"), wrap("()I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385512L), wrap("spliterator"), wrap("()Ljava/util/Spliterator;"), wrap("()Ljava/util/Spliterator<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385496L), wrap("stream"), wrap("()Ljava/util/stream/Stream;"), wrap("()Ljava/util/stream/Stream<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385560L), wrap("parallelStream"), wrap("()Ljava/util/stream/Stream;"), wrap("()Ljava/util/stream/Stream<TE;>;"), wrap(1))))))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276714, klass(967L)),
                 new ReplyOrError<>(2276714, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276714,
                  new ListValue<>(Type.LIST,
                   List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159232L), wrap(
                           "iterator"), wrap("()Ljava/util/Iterator;"), wrap("()Ljava/util/Iterator<TT;>;"),
                            wrap(1025)),
                             new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159248L), wrap(
                                     "forEach"), wrap("(Ljava/util/function/Consumer;)V"), wrap("(Ljava/util/function" +
                                      "/Consumer<-TT;>;)V"), wrap(1)),
                                       new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159240L), wrap("spliterator"), wrap("()Ljava/util/Spliterator;"), wrap("()Ljava/util/Spliterator<TT;>;"), wrap(1))))))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276715, klass(965L)),
                 new ReplyOrError<>(2276715, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276715,
                  new ListValue<>(Type.LIST,
                   List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417488L), wrap("size")
                   , wrap("()I"), wrap(""), wrap(1025)),
                    new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417472L), wrap("isEmpty"),
                            wrap("()Z"), wrap(""), wrap(1025)),
                      new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417624L), wrap("contains"),
                              wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)),
                        new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417520L),
                         wrap("iterator"), wrap("()Ljava/util/Iterator;"), wrap("()Ljava/util/Iterator<TE;>;"),
                                wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417504L),
                           wrap("toArray"), wrap("()[Ljava/lang/Object;"), wrap(""), wrap(1025)),
                            new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417512L), wrap(
                                    "toArray"), wrap("([Ljava/lang/Object;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang" +
                                     "/Object;>([TT;)[TT;"), wrap(1025)),
                                      new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159264L), wrap("add"), wrap("(Ljava/lang/Object;)Z"), wrap("(TE;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159272L), wrap("remove"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417704L), wrap("containsAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417648L), wrap("addAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<+TE;>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417640L), wrap("addAll"), wrap("(ILjava/util/Collection;)Z"), wrap("(ILjava/util/Collection<+TE;>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417672L), wrap("removeAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417680L), wrap("retainAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417480L), wrap("replaceAll"), wrap("(Ljava/util/function/UnaryOperator;)V"), wrap("(Ljava/util/function/UnaryOperator<TE;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417664L), wrap("sort"), wrap("(Ljava/util/Comparator;)V"), wrap("(Ljava/util/Comparator<-TE;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417456L), wrap("clear"), wrap("()V"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417424L), wrap("equals"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417432L), wrap("hashCode"), wrap("()I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159288L), wrap("get"), wrap("(I)Ljava/lang/Object;"), wrap("(I)TE;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417656L), wrap("set"), wrap("(ILjava/lang/Object;)Ljava/lang/Object;"), wrap("(ITE;)TE;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159256L), wrap("add"), wrap("(ILjava/lang/Object;)V"), wrap("(ITE;)V"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159280L), wrap("remove"), wrap("(I)Ljava/lang/Object;"), wrap("(I)TE;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417448L), wrap("indexOf"), wrap("(Ljava/lang/Object;)I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417464L), wrap("lastIndexOf"), wrap("(Ljava/lang/Object;)I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417696L), wrap("listIterator"), wrap("()Ljava/util/ListIterator;"), wrap("()Ljava/util/ListIterator<TE;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417688L), wrap("listIterator"), wrap("(I)Ljava/util/ListIterator;"), wrap("(I)Ljava/util/ListIterator<TE;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417496L), wrap("subList"), wrap("(II)Ljava/util/List;"), wrap("(II)Ljava/util/List<TE;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417632L), wrap("spliterator"), wrap("()Ljava/util/Spliterator;"), wrap("()Ljava/util/Spliterator<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417560L), wrap("of"), wrap("()Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>()Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417552L), wrap("of"), wrap("(Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417544L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417536L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417528L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417592L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417584L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417576L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417568L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417608L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417600L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(TE;TE;TE;TE;TE;TE;TE;TE;TE;TE;)Ljava/util/List<TE;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417616L), wrap("of"), wrap("([Ljava/lang/Object;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>([TE;)Ljava/util/List<TE;>;"), wrap(137)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5098417440L), wrap("copyOf"), wrap("(Ljava/util/Collection;)Ljava/util/List;"), wrap("<E:Ljava/lang/Object;>(Ljava/util/Collection<+TE;>;)Ljava/util/List<TE;>;"), wrap(9))))))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276716, klass(966L)),
new ReplyOrError<>(2276716, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276716,
                  new ListValue<>(Type.LIST,
                   List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385456L), wrap(
                           "size"), wrap("()I"), wrap(""), wrap(1025)),
                            new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385448L), wrap(
                                    "isEmpty"), wrap("()Z"), wrap(""), wrap(1025)),
                                     new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385504L), wrap("contains"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385488L), wrap("iterator"), wrap("()Ljava/util/Iterator;"), wrap("()Ljava/util/Iterator<TE;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385480L), wrap("toArray"), wrap("()[Ljava/lang/Object;"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385464L), wrap("toArray"), wrap("([Ljava/lang/Object;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang/Object;>([TT;)[TT;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385472L), wrap("toArray"), wrap("(Ljava/util/function/IntFunction;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang/Object;>(Ljava/util/function/IntFunction<[TT;>;)[TT;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385408L), wrap("add"), wrap("(Ljava/lang/Object;)Z"), wrap("(TE;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385416L), wrap("remove"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385552L), wrap("containsAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385520L), wrap("addAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<+TE;>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385536L), wrap("removeAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385528L), wrap("removeIf"), wrap("(Ljava/util/function/Predicate;)Z"), wrap("(Ljava/util/function/Predicate<-TE;>;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385544L), wrap("retainAll"), wrap("(Ljava/util/Collection;)Z"), wrap("(Ljava/util/Collection<*>;)Z"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385440L), wrap("clear"), wrap("()V"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385424L), wrap("equals"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385432L), wrap("hashCode"), wrap("()I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385512L), wrap("spliterator"), wrap("()Ljava/util/Spliterator;"), wrap("()Ljava/util/Spliterator<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385496L), wrap("stream"), wrap("()Ljava/util/stream/Stream;"), wrap("()Ljava/util/stream/Stream<TE;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553170385560L), wrap("parallelStream"), wrap("()Ljava/util/stream/Stream;"), wrap("()Ljava/util/stream/Stream<TE;>;"), wrap(1))))))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276717, klass(967L)),
                 new ReplyOrError<>(2276717, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276717,
                  new ListValue<>(Type.LIST,
                   List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159232L), wrap(
                           "iterator"), wrap("()Ljava/util/Iterator;"), wrap("()Ljava/util/Iterator<TT;>;"),
                            wrap(1025)),
                             new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159248L), wrap(
                                     "forEach"), wrap("(Ljava/util/function/Consumer;)V"), wrap("(Ljava/util/function" +
                                      "/Consumer<-TT;>;)V"), wrap(1)),
                                       new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141159240L), wrap("spliterator"), wrap("()Ljava/util/Spliterator;"), wrap("()Ljava/util/Spliterator<TT;>;"), wrap(1))))))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276718, klass(964L)),
                 new ReplyOrError<>(2276718, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276718,
                         new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276719, klass(1045L)),
                 new ReplyOrError<>(2276719, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276719,
                  new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276720, klass(1057L)),
                 new ReplyOrError<>(2276720, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276720,
                  new ListValue<>(Type.LIST, List.of()))))));

        assertEquals("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 40) (\"suspendPolicy\")=" +
                        "(wrap \"byte\" 1) (\"modifiers\" 0 \"kind\")=(wrap \"string\" \"ThreadOnly\") (\"modifiers\" 0 \"thread\")=" +
          "(wrap \"thread\" 1)))\n" +
                        "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 40) (\"suspendPolicy\")=" +
                        "(wrap \"byte\" 1) (\"modifiers\" 0 \"kind\")=(wrap \"string\" \"ThreadOnly\") " +
                          "(\"modifiers\" 0 \"thread\")=(wrap \"thread\" 1)))\n" +
                        "  (= var1 (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" 961)))\n" +
                        "  (= var2 (request ThreadReference Name (\"thread\")=(get cause \"modifiers\" 0 \"thread\"))" +
                        ")\n" +
                        "  (= var3 (request ThreadReference Status (\"thread\")=(get cause \"modifiers\" 0 " +
                         "\"thread\")))\n" +
                        "  (= var4 (request ThreadReference Frames (\"length\")=(wrap \"int\" 1) (\"startFrame\")=" +
                        "(wrap \"int\" 0) (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                        "  (= var5 (request ThreadReference FrameCount (\"thread\")=(get cause \"modifiers\" 0 " +
                         "\"thread\")))\n" +
                        "  (= var6 (request ThreadReference Frames (\"length\")=(get var5 \"frameCount\") " +
                        "(\"startFrame\")=(wrap \"int\" 0) (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                        "  (= var7 (request StackFrame ThisObject (\"frame\")=(get var4 \"frames\" 0 \"frameID\") " +
                        "(\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                        "  (= var8 (request ObjectReference GetValues (\"object\")=(get var7 \"objectThis\") " +
                        "(\"fields\" 0 \"fieldID\")=(wrap \"field\" 50)))\n" +
                        "  (= var9 (request StackFrame GetValues (\"frame\")=(get var6 \"frames\" 0 \"frameID\") " +
                         "(\"thread\")=(get cause \"modifiers\" 0 \"thread\") (\"slots\" 0 \"sigbyte\")=" +
                          "(getTagForValue (get var6 \"frames\" 0 \"frameID\")) (\"slots\" 0 \"slot\")=(wrap \"int\" " +
                           "1)))\n" +
                        "  (= var10 (request ObjectReference ReferenceType (\"object\")=(get var9 \"values\" 0)))\n" +
                        "  (rec recursion0 1000 var11 (request ReferenceType Interfaces (\"refType\")=(get var10 " +
                         "\"typeID\"))\n" +
                        "    (for iter0 (get var11 \"interfaces\") \n" +
                        "      (reccall var13 recursion0 (\"refType\")=iter0)\n" +
                        "      (= var12 (request ReferenceType MethodsWithGeneric (\"refType\")=iter0))\n" +
                        "      (= var15 (request ReferenceType MethodsWithGeneric (\"refType\")=(get var13 " +
                        "\"interfaces\" 0)))))\n" +
                        "  (rec recursion1 1000 var13 (request ClassType Superclass (\"clazz\")=(get var10 " +
                        "\"typeID\"))\n" +
                        "    (= var14 (request ReferenceType Interfaces (\"refType\")=(get var13 \"superclass\")))\n" +
                        "    (reccall var15 recursion1 (\"clazz\")=(get var13 \"superclass\"))))",
                Synthesizer.synthesizeProgram(partition).toPrettyString());
    }

    @Test
    public void testSynthesizeLoop() {
        var partition = new Partition(null, List.of(
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(2276482, thread(1L)), new ReplyOrError<>(2276482,
                        new jdwp.ThreadReferenceCmds.FrameCountReply(2276482, wrap(5)))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(2276494, thread(1L), wrap(0), wrap(5)),
                        new ReplyOrError<>(2276494, new jdwp.ThreadReferenceCmds.FramesReply(2276494,
                                new ListValue<>(Type.LIST,
                                        List.of(new ThreadReferenceCmds.FramesReply.Frame(frame(4653056L),
                                                        new Location(classType(1084L), method(105553140938200L),
                                                                wrap(12L))),
                                                new ThreadReferenceCmds.FramesReply.Frame(frame(4653057L),
                                                        new Location(classType(1079L),
                                                                method(5099358456L), wrap(4L))),
                                                new ThreadReferenceCmds.FramesReply.Frame(frame(4653058L),
                                                        new Location(classType(1079L), method(5099358520L), wrap(11L))),
                                                new ThreadReferenceCmds.FramesReply.Frame(frame(4653059L),
                                                        new Location(classType(1077L),
                                                                method(105553141105552L), wrap(8L))),
                                                new ThreadReferenceCmds.FramesReply.Frame(frame(4653060L),
                                                        new Location(classType(1070L),
                                                                method(105553141105544L), wrap(14L)))))))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276495, klass(1079L)),
                        new ReplyOrError<>(2276495, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276495,
                                new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276500, klass(1077L)),
                 new ReplyOrError<>(2276500,
                        new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276500, new ListValue<>(Type.LIST,
                                List.of())))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276503, klass(1070L)), new ReplyOrError<>(2276503,
                        new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276503, new ListValue<>(Type.LIST, List.of()))))));
        assertEquals("(\n" +
                "  (= var0 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                "  (= var1 (request ThreadReference Frames (\"length\")=(get var0 \"frameCount\") (\"startFrame\")=" +
                "(wrap \"int\" 0) (\"thread\")=(wrap \"thread\" 1)))\n" +
                "  (for iter0 (get var1 \"frames\") \n" +
                "    (= var2 (request ReferenceType MethodsWithGeneric (\"refType\")=(get iter0 \"location\" " +
                "\"declaringType\")))))", Synthesizer.synthesizeProgram(partition).toPrettyString());
    }

    @Test
    public void testExpectMapStatement() {
        var partition = new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(2276413,
                PrimitiveValue.wrap((byte) 1), PrimitiveValue.wrap((byte) 2), new ListValue<>(Type.LIST,
                List.of(new EventRequestCmds.SetRequest.Step(new ThreadReference(1L), PrimitiveValue.wrap(1),
                        PrimitiveValue.wrap(0)), new EventRequestCmds.SetRequest.Count(PrimitiveValue.wrap(1)))))),
                List.of(
                        p(new jdwp.EventRequestCmds.SetRequest(2276413, PrimitiveValue.wrap((byte) 1),
                                        PrimitiveValue.wrap((byte) 2), new ListValue<>(Type.LIST,
                                        List.of(new EventRequestCmds.SetRequest.Step(new ThreadReference(1L),
                                                        PrimitiveValue.wrap(1),
                                                        PrimitiveValue.wrap(0)),
                                                new EventRequestCmds.SetRequest.Count(PrimitiveValue.wrap(1))))),
                                new ReplyOrError<>(2276413, new jdwp.EventRequestCmds.SetReply(2276413,
                                        PrimitiveValue.wrap(105)))),
                        p(new jdwp.ThreadReferenceCmds.FrameCountRequest(2276415, new ThreadReference(1L)),
                                new ReplyOrError<>(2276415, new jdwp.ThreadReferenceCmds.FrameCountReply(2276415,
                                        PrimitiveValue.wrap(7)))),
                        p(new jdwp.ThreadReferenceCmds.FramesRequest(2276423, new ThreadReference(1L),
                                PrimitiveValue.wrap(0)
                                , PrimitiveValue.wrap(7)), new ReplyOrError<>(2276423,
                                new jdwp.ThreadReferenceCmds.FramesReply(2276423, new ListValue<>(Type.LIST,
                                        List.of(new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(3604480L),
                                                        new Location(new ClassTypeReference(1085L),
                                                                new MethodReference(105553140938176L),
                                                                PrimitiveValue.wrap((long) 0))),
                                                new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(3604481L),
                                                        new Location(new ClassTypeReference(1085L),
                                                                new MethodReference(105553141204456L),
                                                                PrimitiveValue.wrap((long) 9))),
                                                new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(3604482L),
                                                        new Location(new ClassTypeReference(1084L),
                                                                new MethodReference(105553140938200L),
                                                                PrimitiveValue.wrap((long) 9))),
                                                new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(3604483L),
                                                        new Location(new ClassTypeReference(1079L),
                                                                new MethodReference(5099358456L),
                                                                PrimitiveValue.wrap((long) 4))),
                                                new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(3604484L),
                                                        new Location(new ClassTypeReference(1079L),
                                                                new MethodReference(5099358520L),
                                                                PrimitiveValue.wrap((long) 11))),
                                                new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(3604485L),
                                                        new Location(new ClassTypeReference(1077L),
                                                                new MethodReference(105553141105552L),
                                                                PrimitiveValue.wrap((long) 8))),
                                                new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(3604486L),
                                                        new Location(new ClassTypeReference(1070L),
                                                                new MethodReference(105553141105544L),
                                                                PrimitiveValue.wrap((long) 14)))))))),
                        p(new jdwp.StackFrameCmds.ThisObjectRequest(2276424, new ThreadReference(1L),
                                new FrameReference(3604480L)), new ReplyOrError<>(2276424,
                                new jdwp.StackFrameCmds.ThisObjectReply(2276424, new ObjectReference(1109L)))),
                        p(new jdwp.MethodCmds.VariableTableWithGenericRequest(2276425, new ClassReference(1085L),
                                new MethodReference(105553140938176L)), new ReplyOrError<>(2276425,
                                new jdwp.MethodCmds.VariableTableWithGenericReply(2276425, PrimitiveValue.wrap(3),
                                        new ListValue<>(Type.LIST,
                                                List.of(new MethodCmds.VariableTableWithGenericReply.SlotInfo(PrimitiveValue.wrap((long) 0),
                                                                PrimitiveValue.wrap("this"), PrimitiveValue.wrap(
                                                                "Ltunnel" +
                                                                        "/synth/program/Scopes$BasicScope;"),
                                                                PrimitiveValue.wrap("Ltunnel/synth/program" +
                                                                        "/Scopes$BasicScope" +
                                                                        "<TT;>;"), PrimitiveValue.wrap(15),
                                                                PrimitiveValue.wrap(0)),
                                                        new MethodCmds.VariableTableWithGenericReply.SlotInfo(PrimitiveValue.wrap((long) 0),
                                                                PrimitiveValue.wrap("parent"), PrimitiveValue.wrap(
                                                                "Ltunnel" +
                                                                        "/synth/program/Scopes$BasicScope;"
                                                        ), PrimitiveValue.wrap("Ltunnel/synth/program" +
                                                                "/Scopes$BasicScope<TT;>;"),
                                                                PrimitiveValue.wrap(15), PrimitiveValue.wrap(1)),
                                                        new MethodCmds.VariableTableWithGenericReply.SlotInfo(PrimitiveValue.wrap((long) 0),
                                                                PrimitiveValue.wrap("variables"),
                                                                PrimitiveValue.wrap("Ljava" +
                                                                        "/util/Map;"),
                                                                PrimitiveValue.wrap("Ljava/util/Map<Ljava/lang" +
                                                                        "/String;TT;>;")
                                                                , PrimitiveValue.wrap(15),
                                                                PrimitiveValue.wrap(2))))))),
                        p(new jdwp.StackFrameCmds.GetValuesRequest(2276426, new ThreadReference(1L),
                                new FrameReference(3604480L), new ListValue<>(Type.LIST,
                                List.of(new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(1),
                                                PrimitiveValue.wrap((byte) 76)),
                                        new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(2),
                                                PrimitiveValue.wrap((byte) 76))))), new ReplyOrError<>(2276426,
                                new jdwp.StackFrameCmds.GetValuesReply(2276426, new ListValue<>(Type.LIST,
                                        List.of(new ObjectReference(1107L), new ObjectReference(1110L)))))),
                        p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(2276427, new ObjectReference(1110L)),
                                new ReplyOrError<>(2276427, new jdwp.ObjectReferenceCmds.ReferenceTypeReply(2276427,
                                        PrimitiveValue.wrap((byte) 1), new ClassReference(884L))))));
        assertEquals("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 1) (\"suspendPolicy\")=(wrap" +
                        " \"byte\" 2) (\"modifiers\" 0 \"depth\")=(wrap \"int\" 0) (\"modifiers\" 0 \"kind\")=(wrap \"string\" " +
                        "\"Step\") (\"modifiers\" 0 \"size\")=(wrap \"int\" 1) (\"modifiers\" 0 \"thread\")=(wrap \"thread\" 1) " +
                        "(\"modifiers\" 1 \"count\")=(wrap \"int\" 1) (\"modifiers\" 1 \"kind\")=(wrap \"string\" \"Count\")))\n" +
                        "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 1) (\"suspendPolicy\")=" +
                        "(wrap \"byte\" 2) (\"modifiers\" 0 \"depth\")=(wrap \"int\" 0) (\"modifiers\" 0 \"kind\")=" +
                          "(wrap \"string\" \"Step\") (\"modifiers\" 0 \"size\")=(wrap \"int\" 1) (\"modifiers\" 0 " +
                        "\"thread\")=(wrap \"thread\" 1) (\"modifiers\" 1 \"count\")=(wrap \"int\" 1) " +
                        "(\"modifiers\" 1 \"kind\")=(wrap \"string\" \"Count\")))\n" +
                        "  (= var1 (request ThreadReference FrameCount (\"thread\")=(get cause \"modifiers\" 0 " +
                         "\"thread\")))\n" +
                        "  (= var2 (request ThreadReference Frames (\"length\")=(get var1 \"frameCount\") " +
                         "(\"startFrame\")=(wrap \"int\" 0) (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                        "  (= var3 (request Method VariableTableWithGeneric (\"methodID\")=(get var2 \"frames\" 0 " +
                        "\"location\" \"methodRef\") (\"refType\")=(get var2 \"frames\" 0 \"location\" " +
                        "\"declaringType\")))\n" +
                        "  (= var4 (request StackFrame ThisObject (\"frame\")=(get var2 \"frames\" 0 \"frameID\") " +
                        "(\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                        "  (map map0 (get var3 \"slots\") 1 iter1 (\"sigbyte\")=(getTagForSignature (get iter1 " +
                        "\"signature\")) (\"slot\")=(get iter1 \"slot\"))\n" +
                        "  (= var5 (request StackFrame GetValues (\"frame\")=(get var2 \"frames\" 0 \"frameID\") " +
                        "(\"slots\")=map0 (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                        "  (= var6 (request ObjectReference ReferenceType (\"object\")=(get var5 \"values\" 1))))",
                Synthesizer.synthesizeProgram(partition).toPrettyString());
    }

    @Test
    public void testExpectDefaultCaseForSwitchCaseWithNonSigtypeExpression() {
        var partition = new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(2276385, wrap((byte) 1),
                wrap((byte) 2), new ListValue<>(Type.LIST, List.of(new EventRequestCmds.SetRequest.Step(thread(1L),
                wrap(1),
                wrap(0)), new EventRequestCmds.SetRequest.Count(wrap(1)))))), List.of(
                p(new jdwp.EventRequestCmds.SetRequest(2276385, wrap((byte) 1), wrap((byte) 2),
                        new ListValue<>(Type.LIST, List.of(new EventRequestCmds.SetRequest.Step(thread(1L), wrap(1),
                                wrap(0)), new EventRequestCmds.SetRequest.Count(wrap(1))))), new ReplyOrError<>(2276385,
                        new jdwp.EventRequestCmds.SetReply(2276385, wrap(103)))),
                p(new jdwp.ThreadReferenceCmds.NameRequest(2276388, thread(1L)), new ReplyOrError<>(2276388,
                        new jdwp.ThreadReferenceCmds.NameReply(2276388, wrap("main")))),
                p(new jdwp.ThreadReferenceCmds.StatusRequest(2276389, thread(1L)), new ReplyOrError<>(2276389,
                        new jdwp.ThreadReferenceCmds.StatusReply(2276389, wrap(1), wrap(1)))),
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(2276387, thread(1L)), new ReplyOrError<>(2276387,
                        new jdwp.ThreadReferenceCmds.FrameCountReply(2276387, wrap(7)))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(2276390, thread(1L), wrap(0), wrap(1)),
                        new ReplyOrError<>(2276390, new jdwp.ThreadReferenceCmds.FramesReply(2276390,
                                new ListValue<>(Type.LIST,
                                        List.of(new ThreadReferenceCmds.FramesReply.Frame(frame(3473408L),
                                        new Location(classType(884L), method(105553141047024L), wrap(0L)))))))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276391, klass(884L)),
                        new ReplyOrError<>(2276391, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276391,
                                new ListValue<>(Type.LIST,
                                        List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141158272L), wrap(
                                                        "hash"), wrap("(Ljava/lang/Object;)I"), wrap(""), wrap(24)),
                                                new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334504L), wrap(
                                                        "comparableClassFor"), wrap("(Ljava/lang/Object;)" +
                                                        "Ljava/lang/Class;"), wrap(
                                                        "(Ljava/lang/Object;)Ljava/lang/Class<*>;"), wrap(8)),
                                                new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334512L), wrap("compareComparables"), wrap("(Ljava/lang/Class;Ljava/lang/Object;Ljava/lang/Object;)I"), wrap("(Ljava/lang/Class<*>;Ljava/lang/Object;Ljava/lang/Object;)I"), wrap(8)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334464L), wrap("tableSizeFor"), wrap("(I)I"), wrap(""), wrap(24)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334240L), wrap("<init>"), wrap("(IF)V"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334256L), wrap("<init>"), wrap("(I)V"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141047024L), wrap("<init>"), wrap("()V"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334248L), wrap("<init>"), wrap("(Ljava/util/Map;)V"), wrap("(Ljava/util/Map<+TK;+TV;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141122656L), wrap("putMapEntries"), wrap("(Ljava/util/Map;Z)V"), wrap("(Ljava/util/Map<+TK;+TV;>;Z)V"), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334344L), wrap("size"), wrap("()I"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334312L), wrap("isEmpty"), wrap("()Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141158224L), wrap("get"), wrap("(Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(Ljava/lang/Object;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141158216L), wrap("getNode"), wrap("(Ljava/lang/Object;)Ljava/util/HashMap$Node;"), wrap("(Ljava/lang/Object;)Ljava/util/HashMap$Node<TK;TV;>;"), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334416L), wrap("containsKey"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334280L), wrap("put"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(TK;TV;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334488L), wrap("putVal"), wrap("(ILjava/lang/Object;Ljava/lang/Object;ZZ)Ljava/lang/Object;"), wrap("(ITK;TV;ZZ)TV;"), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334520L), wrap("resize"), wrap("()[Ljava/util/HashMap$Node;"), wrap("()[Ljava/util/HashMap$Node<TK;TV;>;"), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334496L), wrap("treeifyBin"), wrap("([Ljava/util/HashMap$Node;I)V"), wrap("([Ljava/util/HashMap$Node<TK;TV;>;I)V"), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334368L), wrap("putAll"), wrap("(Ljava/util/Map;)V"), wrap("(Ljava/util/Map<+TK;+TV;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334272L), wrap("remove"), wrap("(Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(Ljava/lang/Object;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334168L), wrap("removeNode"), wrap("(ILjava/lang/Object;Ljava/lang/Object;ZZ)Ljava/util/HashMap$Node;"), wrap("(ILjava/lang/Object;Ljava/lang/Object;ZZ)Ljava/util/HashMap$Node<TK;TV;>;"), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334304L), wrap("clear"), wrap("()V"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334440L), wrap("containsValue"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334432L), wrap("keySet"), wrap("()Ljava/util/Set;"), wrap("()Ljava/util/Set<TK;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334200L), wrap("prepareArray"), wrap("([Ljava/lang/Object;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang/Object;>([TT;)[TT;"), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334208L), wrap("keysToArray"), wrap("([Ljava/lang/Object;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang/Object;>([TT;)[TT;"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334216L), wrap("valuesToArray"), wrap("([Ljava/lang/Object;)[Ljava/lang/Object;"), wrap("<T:Ljava/lang/Object;>([TT;)[TT;"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334288L), wrap("values"), wrap("()Ljava/util/Collection;"), wrap("()Ljava/util/Collection<TV;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334360L), wrap("entrySet"), wrap("()Ljava/util/Set;"), wrap("()Ljava/util/Set<Ljava/util/Map$Entry<TK;TV;>;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334448L), wrap("getOrDefault"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(Ljava/lang/Object;TV;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334376L), wrap("putIfAbsent"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(TK;TV;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334264L), wrap("remove"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334320L), wrap("replace"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"), wrap("(TK;TV;TV;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334328L), wrap("replace"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(TK;TV;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334424L), wrap("computeIfAbsent"), wrap("(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"), wrap("(TK;Ljava/util/function/Function<-TK;+TV;>;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334456L), wrap("computeIfPresent"), wrap("(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"), wrap("(TK;Ljava/util/function/BiFunction<-TK;-TV;+TV;>;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334384L), wrap("compute"), wrap("(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"), wrap("(TK;Ljava/util/function/BiFunction<-TK;-TV;+TV;>;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334352L), wrap("merge"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"), wrap("(TK;TV;Ljava/util/function/BiFunction<-TV;-TV;+TV;>;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334408L), wrap("forEach"), wrap("(Ljava/util/function/BiConsumer;)V"), wrap("(Ljava/util/function/BiConsumer<-TK;-TV;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334336L), wrap("replaceAll"), wrap("(Ljava/util/function/BiFunction;)V"), wrap("(Ljava/util/function/BiFunction<-TK;-TV;+TV;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334296L), wrap("clone"), wrap("()Ljava/lang/Object;"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334472L), wrap("loadFactor"), wrap("()F"), wrap(""), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334480L), wrap("capacity"), wrap("()I"), wrap(""), wrap(16)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334400L), wrap("writeObject"), wrap("(Ljava/io/ObjectOutputStream;)V"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334392L), wrap("readObject"), wrap("(Ljava/io/ObjectInputStream;)V"), wrap(""), wrap(2)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141122664L), wrap("newNode"), wrap("(ILjava/lang/Object;Ljava/lang/Object;Ljava/util/HashMap$Node;)Ljava/util/HashMap$Node;"), wrap("(ITK;TV;Ljava/util/HashMap$Node<TK;TV;>;)Ljava/util/HashMap$Node<TK;TV;>;"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334224L), wrap("replacementNode"), wrap("(Ljava/util/HashMap$Node;Ljava/util/HashMap$Node;)Ljava/util/HashMap$Node;"), wrap("(Ljava/util/HashMap$Node<TK;TV;>;Ljava/util/HashMap$Node<TK;TV;>;)Ljava/util/HashMap$Node<TK;TV;>;"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334232L), wrap("newTreeNode"), wrap("(ILjava/lang/Object;Ljava/lang/Object;Ljava/util/HashMap$Node;)Ljava/util/HashMap$TreeNode;"), wrap("(ITK;TV;Ljava/util/HashMap$Node<TK;TV;>;)Ljava/util/HashMap$TreeNode<TK;TV;>;"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334160L), wrap("replacementTreeNode"), wrap("(Ljava/util/HashMap$Node;Ljava/util/HashMap$Node;)Ljava/util/HashMap$TreeNode;"), wrap("(Ljava/util/HashMap$Node<TK;TV;>;Ljava/util/HashMap$Node<TK;TV;>;)Ljava/util/HashMap$TreeNode<TK;TV;>;"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334184L), wrap("reinitialize"), wrap("()V"), wrap(""), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141122672L), wrap("afterNodeAccess"), wrap("(Ljava/util/HashMap$Node;)V"), wrap("(Ljava/util/HashMap$Node<TK;TV;>;)V"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141122680L), wrap("afterNodeInsertion"), wrap("(Z)V"), wrap(""), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334176L), wrap("afterNodeRemoval"), wrap("(Ljava/util/HashMap$Node;)V"), wrap("(Ljava/util/HashMap$Node<TK;TV;>;)V"), wrap(0)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(5099334192L), wrap("internalWriteEntries"), wrap("(Ljava/io/ObjectOutputStream;)V"), wrap(""), wrap(0))))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276393, klass(884L)), new ReplyOrError<>(2276393,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276393, new ListValue<>(Type.LIST,
                                List.of(interfaceType(1015L), interfaceType(1045L), interfaceType(1057L)))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276394, klass(1015L)), new ReplyOrError<>(2276394,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276394, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276395, klass(1015L)),
                        new ReplyOrError<>(2276395, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276395,
                                new ListValue<>(Type.LIST,
                                        List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132744L), wrap("size")
                                                        , wrap("()I"), wrap(""), wrap(1025)),
                                                new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132712L), wrap("isEmpty"),
                                                        wrap("()Z"), wrap(""), wrap(1025)),
                                                new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132896L), wrap("containsKey"
                                                ), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)),
                                                new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132920L), wrap(
                                                        "containsValue"), wrap("(Ljava/lang/Object;)Z"), wrap(""),
                                                         wrap(1025)),
                                                new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132656L), wrap(
                                                        "get"), wrap("(Ljava/lang/Object;)Ljava/lang/Object;"), wrap(
                                                                "(Ljava/lang" +
                                                        "/Object;)TV;"), wrap(1025)),
                                                new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132664L), wrap("put"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(TK;TV;)TV;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132640L), wrap("remove"), wrap("(Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(Ljava/lang/Object;)TV;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132856L), wrap("putAll"), wrap("(Ljava/util/Map;)V"), wrap("(Ljava/util/Map<+TK;+TV;>;)V"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132704L), wrap("clear"), wrap("()V"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132912L), wrap("keySet"), wrap("()Ljava/util/Set;"), wrap("()Ljava/util/Set<TK;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132680L), wrap("values"), wrap("()Ljava/util/Collection;"), wrap("()Ljava/util/Collection<TV;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132848L), wrap("entrySet"), wrap("()Ljava/util/Set;"), wrap("()Ljava/util/Set<Ljava/util/Map$Entry<TK;TV;>;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132672L), wrap("equals"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132688L), wrap("hashCode"), wrap("()I"), wrap(""), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132928L), wrap("getOrDefault"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(Ljava/lang/Object;TV;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132888L), wrap("forEach"), wrap("(Ljava/util/function/BiConsumer;)V"), wrap("(Ljava/util/function/BiConsumer<-TK;-TV;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132736L), wrap("replaceAll"), wrap("(Ljava/util/function/BiFunction;)V"), wrap("(Ljava/util/function/BiFunction<-TK;-TV;+TV;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132864L), wrap("putIfAbsent"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(TK;TV;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132648L), wrap("remove"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132728L), wrap("replace"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z"), wrap("(TK;TV;TV;)Z"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132720L), wrap("replace"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(TK;TV;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132904L), wrap("computeIfAbsent"), wrap("(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"), wrap("(TK;Ljava/util/function/Function<-TK;+TV;>;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132936L), wrap("computeIfPresent"), wrap("(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"), wrap("(TK;Ljava/util/function/BiFunction<-TK;-TV;+TV;>;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132872L), wrap("compute"), wrap("(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"), wrap("(TK;Ljava/util/function/BiFunction<-TK;-TV;+TV;>;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132840L), wrap("merge"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"), wrap("(TK;TV;Ljava/util/function/BiFunction<-TV;-TV;+TV;>;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132776L), wrap("of"), wrap("()Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>()Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132792L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132784L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132768L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132760L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132752L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132832L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132824L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132816L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132808L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132800L), wrap("of"), wrap("(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;TK;TV;)Ljava/util/Map<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132944L), wrap("ofEntries"), wrap("([Ljava/util/Map$Entry;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>([Ljava/util/Map$Entry<+TK;+TV;>;)Ljava/util/Map<TK;TV;>;"), wrap(137)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132880L), wrap("entry"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/Map$Entry;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(TK;TV;)Ljava/util/Map$Entry<TK;TV;>;"), wrap(9)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(4557132696L), wrap("copyOf"), wrap("(Ljava/util/Map;)Ljava/util/Map;"), wrap("<K:Ljava/lang/Object;V:Ljava/lang/Object;>(Ljava/util/Map<+TK;+TV;>;)Ljava/util/Map<TK;TV;>;"), wrap(9))))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276396, klass(1045L)), new ReplyOrError<>(2276396,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276396, new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276397, klass(1045L)),
                        new ReplyOrError<>(2276397, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276397,
                                new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276398, klass(1057L)),
                        new ReplyOrError<>(2276398, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276398,
                                new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276399, classType(884L)), new ReplyOrError<>(2276399,
                        new jdwp.ClassTypeCmds.SuperclassReply(2276399, classType(969L)))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(2276400, klass(969L)), new ReplyOrError<>(2276400,
                        new jdwp.ReferenceTypeCmds.InterfacesReply(2276400, new ListValue<>(Type.LIST,
                                List.of(interfaceType(1015L)))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(2276401, classType(969L)), new ReplyOrError<>(2276401, new jdwp.ClassTypeCmds.SuperclassReply(2276401, classType(1058L)))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(2276402, klass(969L)), new ReplyOrError<>(2276402, new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(2276402, new ListValue<>(Type.LIST, List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553141047016L), wrap("<init>"), wrap("()V"), wrap(""), wrap(4)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167178000L), wrap("size"), wrap("()I"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177992L), wrap("isEmpty"), wrap("()Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167178040L), wrap("containsValue"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167178024L), wrap("containsKey"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177928L), wrap("get"), wrap("(Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(Ljava/lang/Object;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177936L), wrap("put"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(TK;TV;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177920L), wrap("remove"), wrap("(Ljava/lang/Object;)Ljava/lang/Object;"), wrap("(Ljava/lang/Object;)TV;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167178016L), wrap("putAll"), wrap("(Ljava/util/Map;)V"), wrap("(Ljava/util/Map<+TK;+TV;>;)V"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177984L), wrap("clear"), wrap("()V"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167178032L), wrap("keySet"), wrap("()Ljava/util/Set;"), wrap("()Ljava/util/Set<TK;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177960L), wrap("values"), wrap("()Ljava/util/Collection;"), wrap("()Ljava/util/Collection<TV;>;"), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167178008L), wrap("entrySet"), wrap("()Ljava/util/Set;"), wrap("()Ljava/util/Set<Ljava/util/Map$Entry<TK;TV;>;>;"), wrap(1025)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177944L), wrap("equals"), wrap("(Ljava/lang/Object;)Z"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177968L), wrap("hashCode"), wrap("()I"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177952L), wrap("toString"), wrap("()Ljava/lang/String;"), wrap(""), wrap(1)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167177976L), wrap("clone"), wrap("()Ljava/lang/Object;"), wrap(""), wrap(4)), new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(method(105553167178048L), wrap("eq"), wrap("(Ljava/lang/Object;Ljava/lang/Object;)Z"), wrap(""), wrap(10))))))),
                p(new jdwp.ReferenceTypeCmds.SourceDebugExtensionRequest(2276403, klass(884L)), new ReplyOrError<>(2276403, ReferenceTypeCmds.SourceDebugExtensionRequest.METADATA, 101)),
                p(new jdwp.MethodCmds.LineTableRequest(2276404, klass(884L), method(105553141047024L)), new ReplyOrError<>(2276404, new jdwp.MethodCmds.LineTableReply(2276404, wrap(0L), wrap(10L), new ListValue<>(Type.LIST, List.of(new MethodCmds.LineTableReply.LineInfo(wrap(0L), wrap(469)), new MethodCmds.LineTableReply.LineInfo(wrap(4L), wrap(470)), new MethodCmds.LineTableReply.LineInfo(wrap(10L), wrap(471))))))),
                p(new jdwp.ReferenceTypeCmds.SourceFileRequest(2276405, klass(884L)), new ReplyOrError<>(2276405, new jdwp.ReferenceTypeCmds.SourceFileReply(2276405, wrap("HashMap.java"))))));
        assertEquals("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 1) (\"suspendPolicy\")=(wrap" +
                " \"byte\" 2) (\"modifiers\" 0 \"depth\")=(wrap \"int\" 0) (\"modifiers\" 0 \"kind\")=(wrap \"string\" " +
                "\"Step\") (\"modifiers\" 0 \"size\")=(wrap \"int\" 1) (\"modifiers\" 0 \"thread\")=(wrap \"thread\" 1) " +
           "(\"modifiers\" 1 \"count\")=(wrap \"int\" 1) (\"modifiers\" 1 \"kind\")=(wrap \"string\" \"Count\")))\n" +
                "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 1) (\"suspendPolicy\")=(wrap " +
                 "\"byte\" 2) (\"modifiers\" 0 \"depth\")=(wrap \"int\" 0) (\"modifiers\" 0 \"kind\")=(wrap " +
                  "\"string\" \"Step\") (\"modifiers\" 0 \"size\")=(wrap \"int\" 1) (\"modifiers\" 0 \"thread\")=" +
                   "(wrap \"thread\" 1) (\"modifiers\" 1 \"count\")=(wrap \"int\" 1) (\"modifiers\" 1 \"kind\")=(wrap" +
                " \"string\" \"Count\")))\n" +
                "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                "  (= var2 (request ThreadReference Status (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                "  (= var3 (request ThreadReference Frames (\"length\")=(wrap \"int\" 1) (\"startFrame\")=(wrap " +
                 "\"int\" 0) (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))\n" +
                "  (= var4 (request ThreadReference FrameCount (\"thread\")=(get cause \"modifiers\" 0 \"thread\")))" +
                 "\n" +
                "  (= var5 (request ReferenceType SourceFile (\"refType\")=(get var3 \"frames\" 0 \"location\" " +
                 "\"declaringType\")))\n" +
                "  (= var7 (request ReferenceType Interfaces (\"refType\")=(get var3 \"frames\" 0 \"location\" " +
                 "\"declaringType\")))\n" +
                "  (for iter1 (get var7 \"interfaces\") \n" +
                "    (switch iter1\n" +
                "      (case (wrap \"interface-type\" 1015)\n" +
                "        (= var8 (request ReferenceType Interfaces (\"refType\")=iter1))\n" +
                "        (= var9 (request ReferenceType MethodsWithGeneric (\"refType\")=iter1)))\n" +
                "      (case (wrap \"interface-type\" 1045)\n" +
                "        (= var8 (request ReferenceType Interfaces (\"refType\")=iter1))\n" +
                "        (= var9 (request ReferenceType MethodsWithGeneric (\"refType\")=iter1)))\n" +
                "      (case (wrap \"interface-type\" 1057)\n" +
                "        (= var8 (request ReferenceType MethodsWithGeneric (\"refType\")=iter1)))\n" +
                "      (default\n" +
                "        (= var9 (request ReferenceType MethodsWithGeneric (\"refType\")=iter1)))))\n" +
                "  (= var8 (request ReferenceType SourceDebugExtension (\"refType\")=(get var3 \"frames\" 0 " +
                 "\"location\" \"declaringType\")))\n" +
                "  (= var9 (request ReferenceType MethodsWithGeneric (\"refType\")=(get var3 \"frames\" 0 " +
                 "\"location\" \"declaringType\")))\n" +
                "  (rec recursion1 1000 var10 (request ClassType Superclass (\"clazz\")=(get var3 \"frames\" 0 " +
                 "\"location\" \"declaringType\"))\n" +
                "    (= var11 (request ReferenceType Interfaces (\"refType\")=(get var10 \"superclass\")))\n" +
                "    (= var12 (request ReferenceType MethodsWithGeneric (\"refType\")=(get var10 \"superclass\")))\n" +
                "    (reccall var13 recursion1 (\"clazz\")=(get var10 \"superclass\")))\n" +
                "  (= var12 (request Method LineTable (\"methodID\")=(get var3 \"frames\" 0 \"location\" " +
                 "\"methodRef\") (\"refType\")=(get var3 \"frames\" 0 \"location\" \"declaringType\"))))",
                Synthesizer.synthesizeProgram(partition).toPrettyString());
    }

    private static void assertNodeListEquals(List<Node> first, List<Node> second) {
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getOrigin(), second.get(i).getOrigin());
        }
    }
}