package tunnel.synth;

import jdwp.ArrayReferenceCmds.LengthReply;
import jdwp.ArrayReferenceCmds.LengthRequest;
import jdwp.ClassTypeCmds.SuperclassReply;
import jdwp.ClassTypeCmds.SuperclassRequest;
import jdwp.*;
import jdwp.ObjectReferenceCmds.ReferenceTypeReply;
import jdwp.ObjectReferenceCmds.ReferenceTypeRequest;
import jdwp.Reference.*;
import jdwp.ReferenceTypeCmds.*;
import jdwp.StackFrameCmds.GetValuesReply;
import jdwp.StackFrameCmds.GetValuesRequest;
import jdwp.StackFrameCmds.GetValuesRequest.SlotInfo;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.wrap;
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
                        p(new InstancesRequest(0, Reference.klass(110L), wrap(8)),
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
                        p(new ClassFileVersionRequest(2, Reference.klass(110L)),
                                new ClassFileVersionReply(2, wrap(8), wrap(8))),
                        p(new InstancesRequest(3, Reference.klass(110L), wrap(8)),
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
                "  (= var0 (request Method VariableTable ('methodID')=(wrap 'method' 32505856) " +
                "     ('refType')=(wrap 'klass' 10)))\n" +
                "  (map map0 (get var0 'slots') iter0 ('sigbyte')=(getTagForSignature (get iter0 'signature')) " +
                "     ('slot')=(get iter0 'slot'))\n" +
                "  (= var1 (request StackFrame GetValues ('frame')=(wrap 'frame' 1) ('slots')=map0 " +
                "     ('thread')=(wrap 'thread' 1))))").toPrettyString(), program.toPrettyString());
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
                p(new InterfacesRequest(id, Reference.klass(id)),
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
                "      (reccall recursion0 (\"refType\")=iter0))))", program.toPrettyString());
    }

    @Test
    public void testRecursionSynthesis() {
        BiFunction<Integer, Long, Pair<SuperclassRequest, SuperclassReply>> interfacesCreator = (id, superClass) ->
                p(new SuperclassRequest(id, Reference.classType(id)), new SuperclassReply(id,
                        Reference.classType(superClass)));
        var partition = new Partition(null, List.of(
                interfacesCreator.apply(1, 2L),
                interfacesCreator.apply(2, 3L),
                interfacesCreator.apply(3, 0L)));
        var program = Synthesizer.synthesizeProgram(partition, Synthesizer.DEFAULT_OPTIONS);
        assertEquals("(\n" +
                "  (rec recursion0 1000 var0 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 1))\n" +
                "    (reccall recursion0 (\"clazz\")=(get var0 \"superclass\"))))", program.toPrettyString());
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
                        new ReferenceTypeReply(2, wrap((byte) '['), Reference.klass(3L))),
                p(new ReferenceTypeRequest(3, objectReference),
                        new ReferenceTypeReply(3, wrap((byte) 'L'), Reference.klass(3L))),
                p(new LengthRequest(4, arrayReference), new LengthReply(4, wrap(0))),
                p(new SuperclassRequest(5, Reference.classType(3L)), new SuperclassReply(5, Reference.classType(4L)))
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
                        "        (= var2 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\")))))))",
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
        Supplier<Partition> partition = () -> new Partition(Either.right(new jdwp.EventCmds.Events(5,
                PrimitiveValue.wrap((byte) 2),
                new ListValue<>(Type.LIST, List.of(new
                        EventCmds.Events.Breakpoint(PrimitiveValue.wrap(42), new ThreadReference(1L),
                        new Location(new ClassTypeReference(1070L), new MethodReference(105553136387016L),
                                PrimitiveValue.wrap((long) 5))))))), List.of(
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(20408, new ThreadReference(1L)),
                        new jdwp.ThreadReferenceCmds.FrameCountReply(20408, PrimitiveValue.wrap(1))),
                p(new jdwp.ThreadReferenceCmds.NameRequest(20409, new ThreadReference(1L)),
                        new jdwp.ThreadReferenceCmds.NameReply(20409, PrimitiveValue.wrap("main"))),
                p(new jdwp.ThreadReferenceCmds.StatusRequest(20410, new ThreadReference(1L)),
                        new jdwp.ThreadReferenceCmds.StatusReply(20410, PrimitiveValue.wrap(1),
                                PrimitiveValue.wrap(1))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(20411, new ThreadReference(1L), PrimitiveValue.wrap(0),
                        PrimitiveValue.wrap(1)), new
                        jdwp.ThreadReferenceCmds.FramesReply(20411, new ListValue<>(Type.LIST,
                        List.of(new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(131072L), new Location
                                (new ClassTypeReference(1070L), new MethodReference(105553136387016L),
                                        PrimitiveValue.wrap((long) 5))))))),
                p(new jdwp.ThreadReferenceCmds.ThreadGroupRequest(20412, new ThreadReference(1L)),
                        new jdwp.ThreadReferenceCmds.ThreadGroupReply(20412, new ThreadGroupReference(
                                1071L))),
                p(new jdwp.ThreadGroupReferenceCmds.NameRequest(20413, new ThreadGroupReference(1071L)),
                        new jdwp.ThreadGroupReferenceCmds.NameReply(20413, PrimitiveValue.wrap(
                                "main"))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20414, new ClassTypeReference(1070L)),
                        new jdwp.ClassTypeCmds.SuperclassReply(20414, new ClassTypeReference(1058L))),
                p(new jdwp.MethodCmds.IsObsoleteRequest(20415, new ClassReference(1070L),
                        new MethodReference(105553136387016L)), new jdwp.MethodCmds.IsObsoleteReply(20415,
                        PrimitiveValue.wrap(false))),
                p(new jdwp.ReferenceTypeCmds.FieldsWithGenericRequest(20416, new ClassReference(1070L)),
                        new jdwp.ReferenceTypeCmds.FieldsWithGenericReply(20416, new ListValue<>
                                (Type.LIST, List.of()))),
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
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20418, new ClassReference(1070L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20418, new ListValue<>(Type.LIST,
                                List.of()))),
                p(new jdwp.ReferenceTypeCmds.FieldsWithGenericRequest(20419, new ClassReference(1058L)),
                        new jdwp.ReferenceTypeCmds.FieldsWithGenericReply(20419, new ListValue<>
                                (Type.LIST, List.of()))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20420, new ClassTypeReference(1058L)),
                        new jdwp.ClassTypeCmds.SuperclassReply(20420, new ClassTypeReference(0L))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20421, new ClassReference(1058L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20421, new ListValue<>(Type.LIST,
                                List.of()))),
                p(new jdwp.StackFrameCmds.GetValuesRequest(20422, new ThreadReference(1L),
                        new FrameReference(131072L), new ListValue<>(Type.LIST, List.of(new
                                StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(0),
                                PrimitiveValue.wrap((byte) 91)),
                        new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(
                                1), PrimitiveValue.wrap((byte) 76)),
                        new StackFrameCmds.GetValuesRequest.SlotInfo(PrimitiveValue.wrap(2),
                                PrimitiveValue.wrap((byte) 73))))), new
                        jdwp.StackFrameCmds.GetValuesReply(20422, new ListValue<>(Type.LIST,
                        List.of(new ArrayReference(1072L), new ObjectReference(1073L), PrimitiveValue.wrap(0))))),
                p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(20423, new ObjectReference(1072L)),
                        new jdwp.ObjectReferenceCmds.ReferenceTypeReply(20423,
                                PrimitiveValue.wrap((byte) 3), new ClassReference(920L))),
                p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(20424, new ObjectReference(1073L)),
                        new jdwp.ObjectReferenceCmds.ReferenceTypeReply(20424,
                                PrimitiveValue.wrap((byte) 1), new ClassReference(1052L))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(20425, new ArrayReference(1072L)),
                        new jdwp.ArrayReferenceCmds.LengthReply(20425, PrimitiveValue.wrap(0))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(20426, new ArrayReference(1072L)),
                        new jdwp.ArrayReferenceCmds.LengthReply(20426, PrimitiveValue.wrap(0))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(20427, new ArrayReference(1072L)),
                        new jdwp.ArrayReferenceCmds.LengthReply(20427, PrimitiveValue.wrap(0))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20428, new ClassTypeReference(1052L)),
                        new jdwp.ClassTypeCmds.SuperclassReply(20428, new ClassTypeReference(1058L))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20429, new ClassReference(1052L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20429, new ListValue<>(Type.LIST,
                                List.of(new InterfaceTypeReference(1057L), new InterfaceTypeReference(1056L),
                                        new InterfaceTypeReference(1055L), new InterfaceTypeReference(1054L), new
                                                InterfaceTypeReference(1053L))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20432, new ClassTypeReference(1052L)),
                        new jdwp.ClassTypeCmds.SuperclassReply(20432, new ClassTypeReference(1058L))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20433, new ClassReference(1052L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20433, new ListValue<>(Type.LIST,
                                List.of(new InterfaceTypeReference(1057L), new InterfaceTypeReference(1056L),
                                        new InterfaceTypeReference(1055L), new InterfaceTypeReference(1054L), new
                                                InterfaceTypeReference(1053L))))),
                p(new jdwp.ClassTypeCmds.SuperclassRequest(20434, new ClassTypeReference(1052L)),
                        new jdwp.ClassTypeCmds.SuperclassReply(20434, new ClassTypeReference(1058L))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20435, new ClassReference(1052L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20435, new ListValue<>(Type.LIST,
                                List.of(new InterfaceTypeReference(1057L), new InterfaceTypeReference(1056L),
                                        new InterfaceTypeReference(1055L), new InterfaceTypeReference(1054L), new
                                                InterfaceTypeReference(1053L))))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20445, new ClassReference(1057L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20445, new ListValue<>(Type.LIST,
                                List.of()))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20446, new ClassReference(1056L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20446, new ListValue<>(Type.LIST,
                                List.of()))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20447, new ClassReference(1055L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20447, new ListValue<>(Type.LIST,
                                List.of()))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20457, new ClassReference(1055L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20457, new ListValue<>(Type.LIST,
                                List.of()))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20458, new ClassReference(1054L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20458, new ListValue<>(Type.LIST,
                                List.of()))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20459, new ClassReference(1053L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20459, new ListValue<>(Type.LIST,
                                List.of()))),
                p(new jdwp.ReferenceTypeCmds.InterfacesRequest(20454, new ClassReference(1053L)),
                        new jdwp.ReferenceTypeCmds.InterfacesReply(20454, new ListValue<>(Type.LIST,
                                List.of())))));
        assertEquals("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")" +
                        "=(wrap \"string\" \"Breakpoint\") (\"events\" 0 \"requestID\")=(wrap \"int\" 42) (\"events\"" +
                        " 0 \"thread\")=(wrap \"thread\" 1) (\"events\" 0 \"location\" \"codeIndex\")=(wrap \"long\" " +
                        "5) (\"events\" 0 \"location\" \"declaringType\")=(wrap \"class-type\" 1070) (\"events\" 0 " +
                        "\"location\" \"methodRef\")=(wrap \"method\" 105553136387016)))\n" +
                        "  (= var0 (request ReferenceType Interfaces (\"refType\")=(get cause \"events\" 0 " +
                        "\"location\" \"declaringType\")))\n" +
                        "  (= var1 (request ReferenceType FieldsWithGeneric (\"refType\")=(get cause \"events\" 0 " +
                        "\"location\" \"declaringType\")))\n" +
                        "  (rec recursion0 1000 var2 (request ClassType Superclass (\"clazz\")=(get cause \"events\" " +
                        "0 \"location\" \"declaringType\"))\n" +
                        "    (= var3 (request ReferenceType Interfaces (\"refType\")=(get var2 \"superclass\")))\n" +
                        "    (= var4 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var2 \"superclass\")" +
                        "))\n" +
                        "    (reccall recursion0 (\"clazz\")=(get var2 \"superclass\")))\n" +
                        "  (= var4 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                        "  (= var5 (request ThreadReference Status (\"thread\")=(get cause \"events\" 0 \"thread\")))" +
                        "\n" +
                        "  (= var6 (request ThreadReference ThreadGroup (\"thread\")=(get cause \"events\" 0 " +
                        "\"thread\")))\n" +
                        "  (= var7 (request ThreadReference Frames (\"length\")=(wrap \"int\" 1) (\"startFrame\")=" +
                        "(wrap \"int\" 0) (\"thread\")=(get cause \"events\" 0 \"thread\")))\n" +
                        "  (= var8 (request ThreadReference FrameCount (\"thread\")=(get cause \"events\" 0 " +
                        "\"thread\")))\n" +
                        "  (= var9 (request Method IsObsolete (\"methodID\")=(get var7 \"frames\" 0 \"location\" " +
                        "\"methodRef\") (\"refType\")=(get cause \"events\" 0 \"location\" \"declaringType\")))\n" +
                        "  (= var10 (request Method VariableTableWithGeneric (\"methodID\")=(get var7 \"frames\" 0 " +
                        "\"location\" \"methodRef\") (\"refType\")=(get cause \"events\" 0 \"location\" " +
                        "\"declaringType\")))\n" +
                        "  (= var11 (request ThreadGroupReference Name (\"group\")=(get var6 \"group\")))\n" +
                        "  (= var12 (request StackFrame GetValues (\"frame\")=(get var7 \"frames\" 0 \"frameID\") " +
                        "(\"thread\")=(get cause \"events\" 0 \"thread\") (\"slots\" 0 \"sigbyte\")=" +
                        "(getTagForSignature (get var10 \"slots\" 0 \"signature\")) (\"slots\" 0 \"slot\")=(get var10" +
                        " \"slots\" 0 \"slot\") (\"slots\" 1 \"sigbyte\")=(getTagForSignature (get var10 \"slots\" 1 " +
                        "\"signature\")) (\"slots\" 1 \"slot\")=(get var10 \"slots\" 1 \"slot\") (\"slots\" 2 " +
                        "\"sigbyte\")=(getTagForSignature (get var10 \"slots\" 2 \"signature\")) (\"slots\" 2 " +
                        "\"slot\")=(get var10 \"slots\" 2 \"slot\")))\n" +
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
                        "        (= var16 (request ClassType Superclass (\"clazz\")=(get var13 \"typeID\")))))))",
                Synthesizer.synthesizeProgram(partition.get()).toPrettyString());
    }

    private static void assertNodeListEquals(List<Node> first, List<Node> second) {
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).getOrigin(), second.get(i).getOrigin());
        }
    }
}