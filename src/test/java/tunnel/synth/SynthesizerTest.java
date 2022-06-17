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
}