package tunnel.synth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.synth.program.AST;
import tunnel.synth.program.AST.FunctionCall;
import tunnel.synth.program.AST.Loop;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.Program;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tunnel.synth.program.Program.ident;
import static tunnel.synth.program.Program.literal;

public class ProgramTest {

    private static Object[][] testToStringMethodSource() {
        return new Object[][] {
                new Object[]{literal(100), "100"},
                new Object[]{literal("a"), "\"a\""},
                new Object[]{ident("a"), "a"},
                new Object[]{new FunctionCall(ident("ret"), ident("func"), List.of()), "(= ret func)"},
                new Object[]{new Loop(ident("iter"), ident("iterable"),
                        List.of(new FunctionCall(ident("ret"), ident("func"), List.of()))),
                        "(for iter iterable (= ret func))"}
        };
    }

    @ParameterizedTest
    @MethodSource("testToStringMethodSource")
    public void testToString(AST node, String expected) {
        assertEquals(expected, node.toString());
    }

    private static Object[][] testParseProgramMethodSource() {
        return new Object[][] {
                new Object[]{"()", List.<Statement>of()},
                new Object[]{"((= ret func))", List.of(new FunctionCall(ident("ret"), ident("func"), List.of()))},
                new Object[]{"((for iter iterable\n  (= ret func)))",
                        List.of(new Loop(ident("iter"), ident("iterable"),
                                List.of(new FunctionCall(ident("ret"), ident("func"), List.of()))))}
        };
    }

    @ParameterizedTest
    @MethodSource("testParseProgramMethodSource")
    public void testParseProgram(String string, List<Statement> statements) {
        var program = new Program(statements);
        assertEquals(program, Program.parse(string));
        assertEquals(program, Program.parse(program.toString())); // round-trip for good measure
    }
}
