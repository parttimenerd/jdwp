package tunnel.synth;

import jdwp.AccessPath;
import jdwp.Reference;
import jdwp.Request;
import jdwp.Value;
import jdwp.Value.BasicValue;
import jdwp.Value.ByteList;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.ClassesBySignatureRequest;
import jdwp.VirtualMachineCmds.DisposeObjectsRequest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.synth.program.*;

import java.util.ArrayList;
import java.util.List;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static tunnel.synth.program.AST.*;

public class ProgramTest {

    private static class RecordingFunctions extends Functions {
        final List<Request<?>> requests = new ArrayList<>();

        @Override
        protected Value processRequest(Request<?> request) {
            requests.add(request);
            return wrap(10);
        }
    }

    private static Object[][] testToStringMethodSource() {
        return new Object[][] {
                new Object[] {literal(100), "100"},
                new Object[] {literal("a"), "'a'"},
                new Object[] {ident("a"), "a"},
                new Object[] {new FunctionCall("func", List.of()), "(func)"},
                new Object[] {
                        new FunctionCall(
                                "func",
                                List.of(
                                        new FunctionCall("f", List.of(literal("a"))),
                                        new RequestCall(
                                                "a",
                                                "b",
                                                List.of(
                                                        new RequestCallProperty(
                                                                new AccessPath(1, "a"),
                                                                Functions.createWrapperFunctionCall(wrap(1))))))),
                        "(func (f 'a') (request a b (1 'a')=(wrap 'int' 1)))"
                },
                new Object[] {
                        new Loop(
                                ident("iter"),
                                ident("iterable"),
                                List.of(new AssignmentStatement(ident("ret"), new FunctionCall("func", List.of())))),
                        "(for iter iterable (= ret (func)))"
                }
        };
    }

    @ParameterizedTest
    @MethodSource("testToStringMethodSource")
    public void testToString(AST node, String expected) {
        assertEquals(expected, node.toString().replace('"', '\''));
    }

    private static Object[][] testParseProgramMethodSource() {
        return new Object[][] {
                new Object[] {"()", List.<Statement>of()},
                new Object[] {"((= ret 1))", List.of(new AssignmentStatement(ident("ret"), literal(1)))},
                new Object[] {
                        "((for iter iterable\n  (= ret 1)))",
                        List.of(
                                new Loop(
                                        ident("iter"),
                                        ident("iterable"),
                                        List.of(new AssignmentStatement(ident("ret"), literal(1)))))
                }
        };
    }

    @ParameterizedTest
    @MethodSource("testParseProgramMethodSource")
    public void testParseProgram(String string, List<Statement> statements) {
        var program = new Program(statements);
        assertEquals(program, Program.parse(string));
        assertEquals(program, Program.parse(program.toString()), program.toString()); // round-trip for good measure
    }

  /*@ParameterizedTest
  @CsvSource({
          "((= ret (func))),((= ret (func))),((= ret (func)))",
          "((= ret (func2))),((= ret (func))),((= ret (func2)) (= ret (func)))",
          "((= ret (func2)) (for iter iterable (= iter 1))," +
                  "((= ret (func)))," +
                  "((= ret (func2)) (for iter iterable (= iter 1)) (= ret (func)))",
          "((for iter iterable (= iter const 1)))," +
                  "((for iter iterable (= iter const 2)))," +
                  "((for iter iterable (= iter const 1) (= iter const 2)))",
          "((for iter iterable (= iter const 1) (for iter2 iterable)))," +
                  "((for iter2 iterable) (for iter iterable (= iter const 2)))," +
                  "((for iter2 iterable) (for iter iterable (= iter const 1) (for iter2 iterable) (= iter const 2)))"
  })
  public void testMerge(String program1, String program2, String merge) {
      assertEquals(Program.parse(merge), Program.parse(program1).merge(Program.parse(program2)));
  }*/

    /*@ParameterizedTest
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
    */
    private static Object[][] wrapFunctionTestSource() {
        return new Object[][] {
                {"(wrap 'bytes' '234')", new ByteList((byte) '2', (byte) '3', (byte) '4')},
                {"(wrap 'string' '234')", wrap("234")},
                {"(wrap 'array-reference' 32)", Reference.array(32)},
                {"(wrap 'int' 10)", wrap(10)},
                {"(wrap 'object' 10)", Reference.object(10)}
        };
    }

    @ParameterizedTest
    @MethodSource("wrapFunctionTestSource")
    public void testWrapFunction(String statement, BasicValue expectedValue) {
        assertEquals(
                new Evaluator(new RecordingFunctions()).evaluate(Expression.parse(statement)),
                expectedValue);
        // round trip again
        assertEquals(
                new Evaluator(new RecordingFunctions())
                        .evaluate(
                                Expression.parse(Functions.createWrapperFunctionCall(expectedValue).toString())),
                expectedValue);
    }

    private static Object[][] requestParsingTestSource() {
        return new Object[][] {
                {
                        new RequestCall(
                                "VirtualMachine",
                                "ClassesBySignature",
                                List.of(
                                        new RequestCallProperty(
                                                new AccessPath("signature"),
                                                Functions.createWrapperFunctionCall(wrap("test"))))),
                        "(request VirtualMachine ClassesBySignature ('signature')=(wrap 'string' 'test'))"
                }
        };
    }

    @ParameterizedTest
    @MethodSource("requestParsingTestSource")
    public void testRequestParsing(RequestCall expected, String requestCall) {
        assertEquals(expected, Expression.parse(requestCall));
        // round-trip parsing
        assertEquals(requestCall.replace('\'', '"'), Expression.parse(requestCall).toString());
    }

    private static Object[][] requestEvaluatorTestSource() {
        return new Object[][] {
                {
                        "(request VirtualMachine ClassesBySignature ('signature')=(wrap 'string' 'test'))",
                        new ClassesBySignatureRequest(Evaluator.DEFAULT_ID, wrap("test"))
                },
                {
                        "(request VirtualMachine DisposeObjects)",
                        new DisposeObjectsRequest(Evaluator.DEFAULT_ID, new ListValue<>(Type.OBJECT))
                },
                {
                        "(request VirtualMachine DisposeObjects "
                                + "('requests' 0 'object')=(wrap 'object' 1) "
                                + "('requests' 0 'refCnt')=(wrap 'int' 10))",
                        new DisposeObjectsRequest(
                                Evaluator.DEFAULT_ID,
                                new ListValue<>(new DisposeObjectsRequest.Request(Reference.object(1), wrap(10))))
                },
                {
                        "(request VirtualMachine DisposeObjects "
                                + "('requests' 0 'object')=(wrap 'object' 1) "
                                + "('requests' 0 'refCnt')=(wrap 'int' 10) "
                                + "('requests' 1 'object')=(wrap 'object' 2) "
                                + "('requests' 1 'refCnt')=(wrap 'int' 12))",
                        new DisposeObjectsRequest(
                                Evaluator.DEFAULT_ID,
                                new ListValue<>(
                                        new DisposeObjectsRequest.Request(Reference.object(1), wrap(10)),
                                        new DisposeObjectsRequest.Request(Reference.object(2), wrap(12))))
                }
        };
    }

    @ParameterizedTest
    @MethodSource("requestEvaluatorTestSource")
    public void testRequestEvaluator(String requestStmt, Request<?> request) {
        var funcs = new RecordingFunctions();
        new Evaluator(funcs).evaluate(Expression.parse(requestStmt));
        assertEquals(1, funcs.requests.size());
        assertEquals(request, funcs.requests.get(0));
    }

    @ParameterizedTest
    @MethodSource("requestEvaluatorTestSource")
    public void testRequestToStatement(String requestStmt, Request<?> request) {
        assertEquals(requestStmt, RequestCall.create(request).toString().replace('"', '\''));
    }

    private static Object[][] parsePathTestSource() {
        return new Object[][] {
                {new AccessPath(), "()"},
                {new AccessPath("a"), "('a')"},
                {new AccessPath(1), "(1)"},
                {new AccessPath("a", 1, "b"), "('a' 1 'b')"}
        };
    }

    @ParameterizedTest
    @MethodSource("parsePathTestSource")
    public void testParsePath(AccessPath expected, String str) {
        assertEquals(expected, new Parser(str).parseAccessPath());
    }
}
