package tunnel.synth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.synth.program.AST;
import tunnel.synth.program.AST.FunctionCall;
import tunnel.synth.program.AST.Loop;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.Program;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static tunnel.synth.program.AST.ident;
import static tunnel.synth.program.AST.literal;

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
        assertEquals(program, Program.parse(program.toString()), program.toString()); // round-trip for good measure
    }

    @ParameterizedTest
    @CsvSource({
            "((= ret func)),((= ret func)),((= ret func))",
            "((= ret func2)),((= ret func)),((= ret func2) (= ret func))",
            "((= ret func2) (for iter iterable (= iter const 1)))," +
                    "((= ret func))," +
                    "((= ret func2) (for iter iterable (= iter const 1)) (= ret func))",
            "((for iter iterable (= iter const 1)))," +
                    "((for iter iterable (= iter const 2)))," +
                    "((for iter iterable (= iter const 1) (= iter const 2)))",
            "((for iter iterable (= iter const 1) (for iter2 iterable)))," +
                    "((for iter2 iterable) (for iter iterable (= iter const 2)))," +
                    "((for iter2 iterable) (for iter iterable (= iter const 1) (for iter2 iterable) (= iter const 2)))"
    })
    public void testMerge(String program1, String program2, String merge) {
        assertEquals(Program.parse(merge), Program.parse(program1).merge(Program.parse(program2)));
    }

    @ParameterizedTest
    @CsvSource({
            "((= ret func)),((= ret func)),((= ret func))",
            "((= ret func2)),((= ret func)),()",
            "((for iter iterable (= iter const 1))),((for iter iterable (= iter const 2))),()",
            "((for iter iterable (= iter const 1))),((for iter iterable (= iter const 1)))," +
                    "((for iter iterable (= iter const 1)))"
    })
    public void testOverlap(String program1, String program2, String overlap) {
        assertEquals(Program.parse(overlap), Program.parse(program1).overlap(Program.parse(program2)));
    }
}
