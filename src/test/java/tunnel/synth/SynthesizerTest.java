package tunnel.synth;

import jdwp.*;
import jdwp.Reference.*;
import jdwp.ReferenceTypeCmds.ClassFileVersionReply;
import jdwp.ReferenceTypeCmds.ClassFileVersionRequest;
import jdwp.ReferenceTypeCmds.InstancesReply;
import jdwp.ReferenceTypeCmds.InstancesRequest;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.util.Pair;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.program.Program;
import tunnel.util.Either;

import java.util.List;
import java.util.function.Function;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        var dep = DependencyGraph.compute(partition);
        assertEquals("((= cause (request VirtualMachine IDSizes)) " +
                        "(= var0 (request VirtualMachine IDSizes)) (= var1 " +
                        "(request ReferenceType Instances (\"maxInstances\")=(get var0 \"fieldIDSize\") (\"refType\")" +
                        "=(wrap \"klass\" 110))))",
                Synthesizer.synthesizeProgram(partition).toString());
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
        var dep = DependencyGraph.compute(partition);
        assertEquals("((= cause (request VirtualMachine IDSizes)) (= var0 (request VirtualMachine IDSizes)) (= var1 " +
                        "(request ReferenceType ClassFileVersion (\"refType\")=(wrap \"klass\" 110))) (= var2 " +
                        "(request ReferenceType Instances (\"maxInstances\")=(get var0 \"fieldIDSize\") (\"refType\")" +
                        "=(wrap \"klass\" 110))))",
                Synthesizer.synthesizeProgram(partition).toString());
    }
}
