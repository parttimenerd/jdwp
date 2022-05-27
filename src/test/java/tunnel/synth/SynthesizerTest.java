package tunnel.synth;

import jdwp.EventRequestCmds;
import jdwp.PrimitiveValue;
import jdwp.Reference.ClassReference;
import jdwp.Reference.MethodReference;
import jdwp.ReferenceTypeCmds;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.program.Program;
import tunnel.util.Either;

import java.util.List;

import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SynthesizerTest {

    private static Object[][] realPartitionsTestSource() {
        return new Object[][]{{"((= var0 (request VirtualMachine IDSizes)))", new Partition(null,
                List.of(p(new jdwp.VirtualMachineCmds.IDSizesRequest(174),
                        new jdwp.VirtualMachineCmds.IDSizesReply(174, PrimitiveValue.wrap(8), PrimitiveValue.wrap(8),
                                PrimitiveValue.wrap(8), PrimitiveValue.wrap(8), PrimitiveValue.wrap(8)))))},
                {"((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) (\"suspendPolicy\")=(wrap " +
                        "\"byte\" 0))))",
                        new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(12006, PrimitiveValue.wrap((byte) 8),
                        PrimitiveValue.wrap((byte) 0), new ListValue<>(Type.LIST, List.of()))), List.of())},
                {"((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) (\"suspendPolicy\")=(wrap " +
                        "\"byte\" 1) (\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"sun.instrument" +
                        ".InstrumentationImpl\")))\n" +
                        "  (= var0 (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" 426)))\n" +
                        "  (= var1 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) (\"suspendPolicy\")=" +
                        "(wrap \"byte\" 1) (\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"sun.instrument" +
                        ".InstrumentationImpl\"))))",
                        new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(12090, PrimitiveValue.wrap((byte) 8), PrimitiveValue.wrap((byte) 1), new ListValue<>(Type.LIST, List.of(new EventRequestCmds.SetRequest.ClassMatch(PrimitiveValue.wrap("sun" + ".instrument.InstrumentationImpl")))))), List.of(p(new jdwp.EventRequestCmds.SetRequest(12090, PrimitiveValue.wrap((byte) 8), PrimitiveValue.wrap((byte) 1), new ListValue<>(Type.LIST, List.of(new EventRequestCmds.SetRequest.ClassMatch(PrimitiveValue.wrap("sun" + ".instrument.InstrumentationImpl"))))), new jdwp.EventRequestCmds.SetReply(12090, PrimitiveValue.wrap(6))), p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(12094, new ClassReference(426L)), new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(12094, new ListValue<>(Type.LIST, List.of(new ReferenceTypeCmds.MethodsWithGenericReply.MethodInfo(new MethodReference(105553140349016L), PrimitiveValue.wrap("<init>"), PrimitiveValue.wrap("(JZZ)V"), PrimitiveValue.wrap(""), PrimitiveValue.wrap(2))))))))
                },
                {
                    "((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) (\"suspendPolicy\")=(wrap " +
                            "\"byte\" 1) (\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"build.tools.jdwpgen" +
                            ".CodeGeneration$genToString$1$*\")))\n" +
                            "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8) " +
                            "(\"suspendPolicy\")=(wrap \"byte\" 1) (\"modifiers\" 0 \"classPattern\")=(wrap " +
                            "\"string\" \"build.tools.jdwpgen.CodeGeneration$genToString$1$*\"))))",
                        new Partition(Either.left(new jdwp.EventRequestCmds.SetRequest(16165, PrimitiveValue.wrap((byte)8), PrimitiveValue.wrap((byte)1), new ListValue<>(Type.LIST, List.of(new EventRequestCmds.SetRequest.ClassMatch(PrimitiveValue.wrap("build.tools.jdwpgen.CodeGeneration$genToString$1$*")))))), List.of(
                                p(new jdwp.EventRequestCmds.SetRequest(16165, PrimitiveValue.wrap((byte)8), PrimitiveValue.wrap((byte)1), new ListValue<>(Type.LIST, List.of(new EventRequestCmds.SetRequest.ClassMatch(PrimitiveValue.wrap("build.tools.jdwpgen.CodeGeneration$genToString$1$*"))))), new jdwp.EventRequestCmds.SetReply(16165, PrimitiveValue.wrap(45)))))
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
}
