package tunnel.synth;

import jdwp.*;
import jdwp.EventCmds.Events;
import jdwp.Reference.ClassTypeReference;
import jdwp.Reference.ThreadReference;
import jdwp.TunnelCmds.UpdateCacheRequest;
import jdwp.Value.BasicValue;
import jdwp.Value.ByteList;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.ClassesBySignatureRequest;
import jdwp.VirtualMachineCmds.DisposeObjectsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.synth.program.*;
import tunnel.synth.program.Evaluator.EvaluationAbortException;
import tunnel.synth.program.Evaluator.MapCallResultEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static jdwp.PrimitiveValue.wrap;
import static org.junit.jupiter.api.Assertions.*;
import static tunnel.synth.program.AST.*;

/**
 * Test for {@link Program}, {@link Evaluator} and {@link Functions}.
 */
public class ProgramTest {

    private static class RecordingFunctions extends Functions {
        final List<Request<?>> requests = new ArrayList<>();
        final List<Value> values = new ArrayList<>();

        @Override
        protected Value processRequest(Request<?> request) {
            requests.add(request);
            return wrap(10);
        }

        @Override
        public Function getFunction(String name) {
            if (name.equals("collect")) {
                return new Function("collect") {
                    @Override
                    public Value evaluate(List<Value> arguments) {
                        values.addAll(arguments);
                        return wrap(0);
                    }
                };
            }
            return super.getFunction(name);
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
                                                        new CallProperty(
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
        return new Object[][]{
                {"()", List.<Statement>of()},
                {"((= ret 1))", List.of(new AssignmentStatement(ident("ret"), literal(1)))},
                {
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

    @ParameterizedTest
    @CsvSource({
            "(map var0 xs x ()=(get x 'a'))",
            "(map var0 xs x ('a')=(get x 'a'))",
            "(map var0 xs x ('a')=(get x 'a') ('b')=(get x 'b'))",
            "(switch x)",
            "(switch x (case 'a'))",
            "(switch x (case 'a' (= var0 1)))",
            "(switch x (case 'a' (= var0 1)) (case 'b' (= var0 1) (= var1 2)))"
    })
    public void testParseMapAndSwitch(String statement) {
        assertEquals(statement, Statement.parse(statement).toString().replace('"', '\''));
    }


    @ParameterizedTest
    @CsvSource({
            "((= xs 1) (map var0 xs x ()=(get x 'a')))",
            "((= xs 1) (map var0 xs x ('a')=(get x 'a')))",
            "((= xs 1) (map var0 xs x ('a')=(get x 'a') ('b')=(get x 'b')))",
            "((= x 1) (switch x))",
            "((= x 1) (switch x (case 'a')))",
            "((= x 1) (switch x (case 'a' (= var0 1))))",
            "((= x 1) (switch x (case 'a' (= var0 2))))",
            "((= x 1) (switch x (case 'a' (= var0 1)) (case 'b' (= var0 1) (= var1 2))))"
    })
    public void testParseMapAndSwitchProgramParse(String statement) {
        assertEquals(statement, Program.parse(statement).toString().replace('"', '\''));
    }

    @ParameterizedTest
    @CsvSource({
            "((= ret (func))),((= ret (func))),((= ret (func)))",
            "((= ret (func2))),((= ret (func))),((= ret (func2)) (= ret (func)))",
            "((= ret (func2)) (for iter iterable (= iter 1)))," +
                    "((= ret (func)))," +
                    "((= ret (func2)) (for iter iterable (= iter 1)) (= ret (func))))",
            "((for iter iterable (= iter 1)))," +
                    "((for iter iterable (= iter 2)))," +
                    "((for iter iterable (= iter 1) (= iter 2)))",
            "((for iter iterable (= iter 1) (for iter2 iterable2)))," +
                    "((for iter2 iterable2) (for iter iterable (= iter 2)))," +
                    "((for iter2 iterable2) (for iter iterable (= iter 1) (for iter2 iterable2) (= iter 2)))",
            "((= x 1) (switch x (case 'a'))),((= x 1) (switch x (case 'b'))),((= x 1) (switch x (case 'a') (case 'b')))",
            "((= x 1) (switch x (case 'a'))),((= x 1) (switch x (case 'a' (= y 1)))),((= x 1) (switch x (case 'a' (= y 1))))",
            "((= x 1) (switch x (case 'a')) (switch (const 1) (case 'a')))," +
                    "((= x 1) (switch x (case 'b')))," +
                    "((= x 1) (switch x (case 'a') (case 'b')) (switch (const 1) (case 'a')))",
    })
    public void testMerge(String program1, String program2, String merge) {
        var p1 = Program.parse(merge);
        var p2 = Program.parse(program1);
        var p3 = Program.parse(program2);
        assertEquals(p1, p2.merge(p3));
    }

    @ParameterizedTest
    @CsvSource({
            "((= ret func)),((= ret func)),((= ret func))",
            "((= ret func2)),((= ret func)),()",
            "((= ret2 func) (= y (func2)) (= r ret2)),((= ret func) (= x (func)) (= r2 ret)),((= ret2 func) (= r " +
                    "ret2))",
            "((= ret2 func) (= r ret2)),((= ret func) (= r2 ret)),((= ret2 func) (= r ret2))",
            "((for iter iterable (= iter 1))),((for iter iterable (= iter 2))),()",
            "((for iter iterable (= iter 1))),((for iter iterable (= iter 1)))," +
                    "((for iter iterable (= iter 1)))",
            "((= x 1) (switch x (case 'a'))),((= x 1) (switch x (case 'a'))),((= x 1))",
            "((= x 1) (switch x (case 'a' (= x 1)))),((= x 1) (switch x (case 'a' (= x 1)))),((= x 1) (switch x (case" +
                    " 'a' (= x 1))))",
            "((= x 1) (switch x (case 'a'))),((= x 1) (switch x (case 'b'))),((= x 1))",
            "((= x 1) (switch x (case 'a') (case 'b'))),((= x 1) (switch x (case 'a'))),((= x 1))",
            "((= x 1) (switch x (case 'a' (= v 1) (= v 2)))),((= x 1) (switch x (case 'a' (= v 2)))),((= x 1) (switch" +
                    " x (case 'a' (= v 2))))",
    })
    public void testOverlap(String program1, String program2, String overlap) {
        var overlapParse = Program.parse(overlap);
        var program1Parse = Program.parse(program1);
        var program2Parse = Program.parse(program2);
        assertEquals(overlapParse, program1Parse.overlap(program2Parse));
    }

    @Test
    public void testOverlap2() {
        var program1 = Program.parse("((= cause (request VirtualMachine Suspend))\n" +
                "  (= var0 (request VirtualMachine Suspend))\n" +
                "  (= var1 (request VirtualMachine AllThreads))\n" +
                "  (= var2 (request ObjectReference GetValues (\"object\")=(wrap \"object\" 1062) (\"fields\" 0 " +
                "\"fieldID\")=(wrap \"field\" 162)))\n" +
                "  (= var3 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 6)))\n" +
                "  (= var4 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 5)))\n" +
                "  (= var5 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 4)))\n" +
                "  (= var6 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 3)))\n" +
                "  (= var7 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 2)))\n" +
                "  (= var8 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 1)))\n" +
                "  (= var9 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 0))))");
        var program2 = Program.parse("((= cause (request VirtualMachine Suspend))\n" +
                "  (= var0 (request VirtualMachine Suspend))\n" +
                "  (= var1 (request VirtualMachine AllThreads))\n" +
                "  (= var2 (request ObjectReference GetValues (\"object\")=(wrap \"object\" 1061) (\"fields\" 0 " +
                "\"fieldID\")=(wrap \"field\" 162)))\n" +
                "  (= var3 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 6)))\n" +
                "  (= var4 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 5)))\n" +
                "  (= var5 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 4)))\n" +
                "  (= var6 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 3)))\n" +
                "  (= var7 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 2)))\n" +
                "  (= var8 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 1)))\n" +
                "  (= var9 (request ThreadReference Name (\"thread\")=(get var1 \"threads\" 0))))");
        System.out.println(program1.overlap(program2).toPrettyString());
        assertEquals(program1.getNumberOfAssignments() - 1, program1.overlap(program2).getNumberOfAssignments());
        var cache = new ProgramCollection(0.7);
        cache.accept(program1);
        assertEquals(1, cache.size());
        assertEquals(program1, cache.getOverlappingProgram(program2).get().getFirst());
    }

    private static Object[][] wrapFunctionTestSource() {
        return new Object[][]{
                {"(wrap 'bytes' '234')", new ByteList((byte) -37, (byte) 126)},
                {"(wrap 'string' '234')", wrap("234")},
                {"(wrap 'string' '\"')", wrap("\"")},
                {"(wrap 'string' \"\\\"\")", wrap("\"")},
                {"(wrap 'array-reference' 32)", Reference.array(32)},
                {"(wrap 'int' 10)", wrap(10)},
                {"(wrap 'object' 10)", Reference.object(10)},
                {"(wrap 'boolean' 1)", wrap(true)},
                {"(wrap 'class-type' 1129)", new ClassTypeReference(1129L)}
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

    @Test
    public void testFormatEscapedString() {
        assertEquals("(wrap \"string\" \"\\\"\")", Functions.createWrapperFunctionCall(wrap("\"")).toString());
    }

    @Test
    public void testParseEscapedString() {
        assertEquals("\"", ((StringLiteral)Expression.parse("\"\\\"\"")).get());
    }

    @Test
    public void testGetFunctionCreation() {
        assertEquals("(get x 1 \"a\" 2)", Functions.createGetFunctionCall("x", new AccessPath(1, "a", 2)).toString());
    }

    private static Object[][] requestParsingTestSource() {
        return new Object[][] {
                {
                        new RequestCall(
                                "VirtualMachine",
                                "ClassesBySignature",
                                List.of(
                                        new CallProperty(
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

    @Test
    public void testIdentifierWithAssignmentIsNotGlobal() {
        assertFalse(((AssignmentStatement)Statement.parse("(= ret func)")).getVariable().isGlobalVariable());
    }

    @Test
    public void testIdentifierWithAssignmentIsNotGlobal2() {
        assertFalse(((Identifier)((AssignmentStatement)Program.parse("((= ret func) (= x ret))")
                .getBody().getLastStatement()).getExpression()).isGlobalVariable());
    }

    @Test
    public void testGetNumberOfAssignments() {
        assertEquals(1, Program.parse("((= cause (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 32505856) " +
                "(\"thread\")=(wrap \"thread\" 1) (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 91) (\"slots\" 0 \"slot\")" +
                "=(wrap \"int\" 0) (\"slots\" 1 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 1 \"slot\")=(wrap \"int\" " +
                "1))) (= var0 (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 32505856) (\"thread\")=(wrap " +
                "\"thread\" 1) (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 91) (\"slots\" 0 \"slot\")=(wrap \"int\" 0) " +
                "(\"slots\" 1 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 1 \"slot\")=(wrap \"int\" 1))))").getNumberOfAssignments());
    }

    @Test
    public void testEvaluation() {
        var functions = new RecordingFunctions();
        assertEquals(new ClassesBySignatureRequest(0, wrap("test")), new Evaluator(functions).evaluatePacketCall(
                (PacketCall) PacketCall.parse(
                        "(request VirtualMachine ClassesBySignature ('signature')=(wrap 'string' 'test'))")));
        assertEquals(0, functions.requests.size());
    }

    @Test
    public void testCauseEvaluation() {
        var functions = new RecordingFunctions();
        var eventString = "(events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")=" +
                "(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 " +
                "\"thread\")=(wrap \"thread\" 0))";
        var scope =
                new Evaluator(functions).evaluate(Program.parse("((= cause " + eventString + ") " +
                        "(= var0 (request VirtualMachine ClassesBySignature ('signature')=(wrap 'string' 'bla'))))"));
        assertEquals(eventString, EventsCall.create((Events) scope.first.get("cause")).toString());
        assertEquals(1, functions.requests.size());
    }

    @Test
    public void testParseEvents() {
        var events = "(events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")=(wrap " +
                "\"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"thread\")=" +
                "(wrap \"thread\" 0))";
        var functions = new RecordingFunctions();
        var ev = (Events) new Evaluator(functions).evaluatePacketCall((PacketCall) PacketCall.parse(events));
        assertEquals(ev, new jdwp.EventCmds.Events(0, PrimitiveValue.wrap((byte) 2),
                new ListValue<>(new EventCmds.Events.VMStart(PrimitiveValue.wrap(0), new ThreadReference(0L)))));
        assertEquals("(events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")=(wrap " +
                "\"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"thread\")=" +
                "(wrap \"thread\" 0))", EventsCall.create(ev).toString());
    }

    private Request<?> evaluateRequestCall(RequestCall requestCall) {
        return (Request<?>) new Evaluator(new RecordingFunctions()).evaluatePacketCall(new Scopes<>(), requestCall);
    }

    @Test
    public void testEventRequestSetEvaluation() {
        var call = (RequestCall)PacketCall.parse("(request EventRequest Set (\"eventKind\")=(wrap \"byte\" 8)" +
                " (\"suspendPolicy\")=(wrap \"byte\" 1) (\"modifiers\" 0 \"kind\")=(wrap \"string\" \"ClassMatch\") " +
                "(\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"sun.instrument" +
                ".InstrumentationImpl\"))");
        var packet = evaluateRequestCall(call);
        assertEquals(packet, evaluateRequestCall(RequestCall.create(packet)));
    }

    @ParameterizedTest
    @CsvSource("(request Method VariableTableWithGeneric (\"methodID\")=(wrap " +
            "\"method\" 105553176478280) (\"refType\")=(wrap \"class-type\" 1129))")
    public void testEvaluateClassTypeWithRefType(String packetCall) {
        var call = (RequestCall)PacketCall.parse(packetCall);
        RequestCall.create(evaluateRequestCall(call));
    }

    @ParameterizedTest
    @CsvSource({"(request Method VariableTableWithGeneric (\"methodID\")=(wrap " +
            "\"method\" 105553176478280) (\"refType\")=(wrap \"class-type\" 1129)), true",
            "(wrap 'string-reference' 'a'), false"
    })
    public void testIsDirectPointerRelated(String expression, boolean isDirectPointerRelated) {
        var expr = Expression.parse(expression);
        assertEquals(isDirectPointerRelated, expr.isDirectPointerRelated());
    }

    @ParameterizedTest
    @CsvSource({"((= var0 (request Method VariableTableWithGeneric (\"methodID\")=(wrap " +
            "\"method\" 105553176478280) (\"refType\")=(wrap \"class-type\" 1129)))), ()",
            "((= var0 (request Method VariableTableWithGeneric (\"methodID\")=(wrap " +
                    "\"method\" 105553176478280) (\"refType\")=(wrap \"class-type\" 1129))) " +
                    "(= var1 (const var0)) (= var2 (const 1))) (= var3 (const var0)), ((= var2 (const 1)))"
    })
    public void testRemoveDirectPointerRelated(String testProgram, String expectedResultingProgram) {
        var prog = Program.parse(testProgram);
        assertEquals(Program.parse(expectedResultingProgram), prog.removeDirectPointerRelatedStatementsTransitively());
    }

    /**
     * tests that an array out of bounds exception in a get call
     */
    @Test
    public void testEvaluatorArrayOutOfBounds() {
        var result = new Evaluator(new RecordingFunctions())
                .evaluate(Program.parse("((= cause (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                        "(= va10 (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                        "(= var0 (get 'programs' 0)) (= var1 (get 'programs' 1)) " +
                        "(= var2 1))"));
        assertEquals(2, result.second.size());
    }

    @Test
    public void testEvaluatorInvalidPropertyAccess() {
        var result = new Evaluator(new RecordingFunctions())
                .evaluate(Program.parse("((= cause (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                        "(= var10 (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                        "(= var0 (get 'programs')) (= var1 (get var0 'progx')) " +
                        "(= var2 2))"));
        assertEquals(1, result.second.size());
    }

    @Test
    public void testEvaluatorNoDiscardRequestHandling() {
        var result = new Evaluator(new Functions() {
            @Override
            protected Value processRequest(Request<?> request) {
                throw new EvaluationAbortException(false);
            }
        }).evaluate(Program.parse("((= cause (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                "(= var0 (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a')))" +
                "(= var2 1)))"));
        assertEquals(1, result.second.size());
        assertEquals(wrap((long) 1), result.first.get("var2"));
    }

    @Test
    public void testEvaluatorDiscardRequestHandling() {
        assertThrows(EvaluationAbortException.class, () -> new Evaluator(new Functions() {
            @Override
            protected Value processRequest(Request<?> request) {
                throw new EvaluationAbortException(true);
            }
        }).evaluate(Program.parse("((= cause (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                "(= var0 (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a')))" +
                "(= var2 1))")));
    }

    @Test
    public void testUpdateCacheRequestEvaluation() {
        var pc = (UpdateCacheRequest) new Evaluator(new RecordingFunctions())
                .evaluatePacketCall((PacketCall) PacketCall.parse("(request Tunnel UpdateCache " +
                        "('programs' 0)=(wrap 'string' 'a'))"));
        assertEquals(wrap("a"), pc.programs.get(0));
    }

    @Test
    public void testSetEventCause() {
        var program = Program.parse("((= cause (events Event Composite (\"events\" 0 \"kind\")=(wrap \"string\" " +
                "\"ClassPrepare\") (\"events\" 1 \"kind\")=(wrap \"string\" \"ClassPrepare\") (\"suspendPolicy\")=" +
                "(wrap \"byte\" 1) (\"events\" 0 \"requestID\")=(wrap \"int\" 12) (\"events\" 0 \"thread\")=(wrap " +
                "\"thread\" 1) (\"events\" 0 \"refTypeTag\")=(wrap \"byte\" 1) (\"events\" 0 \"typeID\")=(wrap " +
                "\"klass\" 1129) (\"events\" 0 \"signature\")=(wrap \"string\" \"Ltunnel/EndlessLoop;\") (\"events\" " +
                "0 \"status\")=(wrap \"int\" 3) (\"events\" 1 \"requestID\")=(wrap \"int\" 2) (\"events\" 1 " +
                "\"thread\")=(wrap \"thread\" 1) (\"events\" 1 \"refTypeTag\")=(wrap \"byte\" 1) (\"events\" 1 " +
                "\"typeID\")=(wrap \"klass\" 1129) (\"events\" 1 \"signature\")=(wrap \"string\" " +
                "\"Ltunnel/EndlessLoop;\") (\"events\" 1 \"status\")=(wrap \"int\" 3)))\n" +
                "  (= var0 (request ReferenceType MethodsWithGeneric (\"refType\")=(get cause \"events\" 0 " +
                "\"typeID\"))))");
        var newCause = (PacketCall) PacketCall.parse("(events Event Composite (\"events\" 0 \"kind\")=(wrap " +
                "\"string\" \"ClassPrepare\") (\"suspendPolicy\")=(wrap \"byte\" 0) (\"events\" 0 \"requestID\")=" +
                "(wrap \"int\" 2) (\"events\" 0 \"thread\")=(wrap \"thread\" 1) (\"events\" 0 \"refTypeTag\")=(wrap " +
                "\"byte\" 1) (\"events\" 0 \"typeID\")=(wrap \"klass\" 1128) (\"events\" 0 \"signature\")=(wrap " +
                "\"string\" \"Lsun/launcher/LauncherHelper;\") (\"events\" 0 \"status\")=(wrap \"int\" 3))");
        assertEquals(program.getBody().get(0), program.setCause(newCause).getBody().get(0));
    }

    @Test
    public void testInvariantViolatingProgram() {
        assertThrows(AssertionError.class, () -> {
            Program.parse("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 9) (\"suspendPolicy\")=" +
                    "(wrap \"byte\" 0)))\n" +
                    "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 7) (\"suspendPolicy\")=(wrap " +
                    "\"byte\" 0)))\n" +
                    "  (= var1 (request VirtualMachine Version))\n" +
                    "  (= var2 (request VirtualMachine TopLevelThreadGroups))\n" +
                    "  (= var3 (request VirtualMachine Capabilities))\n" +
                    "  (= var4 (request VirtualMachine CapabilitiesNew))\n" +
                    "  (= var5 (request VirtualMachine AllClassesWithGeneric)))");
        });
    }

    @Test
    public void testEvaluateMapStatement() {
        var program = Program.parse("((= cause (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 32505856) " +
                "(\"thread\")=(wrap \"thread\" 1) (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 91) (\"slots\" 0 \"slot\")" +
                "=(wrap \"int\" 0) (\"slots\" 1 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 1 \"slot\")=(wrap \"int\" " +
                "1))) (= var0 (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 32505856) (\"thread\")=(wrap " +
                "\"thread\" 1) (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 91) (\"slots\" 0 \"slot\")=(wrap \"int\" 0) " +
                "(\"slots\" 1 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 1 \"slot\")=(wrap \"int\" 1)))\n" +
                "(map xs (get cause 'slots') x ('a')=(get x 'sigbyte') ('b')=(get x 'slot')))");
        var scopes = new Evaluator(new RecordingFunctions()).evaluate(program).first;
        assertEquals(new ListValue<>(new MapCallResultEntry(Map.of("a", wrap((byte) 91), "b", wrap(0))),
                new MapCallResultEntry(Map.of("a", wrap((byte) 73), "b", wrap(1)))), scopes.get("xs"));
    }

    @Test
    public void testEvaluateMapStatement2() {
        var program = Program.parse("((= cause (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 32505856) " +
                "(\"thread\")=(wrap \"thread\" 1) (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 91) (\"slots\" 0 \"slot\")" +
                "=(wrap \"int\" 0) (\"slots\" 1 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 1 \"slot\")=(wrap \"int\" " +
                "1))) (= var0 (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 32505856) (\"thread\")=(wrap " +
                "\"thread\" 1) (\"slots\" 0 \"sigbyte\")=(wrap \"byte\" 91) (\"slots\" 0 \"slot\")=(wrap \"int\" 0) " +
                "(\"slots\" 1 \"sigbyte\")=(wrap \"byte\" 73) (\"slots\" 1 \"slot\")=(wrap \"int\" 1)))\n" +
                "(map xs (get cause 'slots') x ()=(get x 'sigbyte')))");
        var scopes = new Evaluator(new RecordingFunctions()).evaluate(program).first;
        assertEquals(new ListValue<>(wrap((byte) 91), wrap((byte) 73)), scopes.get("xs"));
    }

    @Test
    public void testEvaluateSwitchStatement() {
        var program = Program.parse("((= x 1) (switch (const x) (case 1 (= v (collect 2))) (case 2 (= v (collect 1)))" +
                "))");
        var funcs = new RecordingFunctions();
        new Evaluator(funcs).evaluate(program);
        assertEquals(List.of(wrap(2L)), funcs.values);
    }

    @Test
    public void testEvaluateSwitchStatement2() {
        var program = Program.parse("((= x 3) (switch (const x) (case 1 (= v (collect 2))) (case 2 (= v (collect 3)))" +
                "))");
        var funcs = new RecordingFunctions();
        new Evaluator(funcs).evaluate(program);
        assertEquals(List.of(), funcs.values);
    }
}
