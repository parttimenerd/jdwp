package tunnel.synth;

import jdwp.*;
import jdwp.ClassTypeCmds.SuperclassReply;
import jdwp.ClassTypeCmds.SuperclassRequest;
import jdwp.EventCmds.Events;
import jdwp.PrimitiveValue.VoidValue;
import jdwp.Reference.ClassTypeReference;
import jdwp.Reference.ThreadReference;
import jdwp.ReferenceTypeCmds.InterfacesReply;
import jdwp.ReferenceTypeCmds.InterfacesRequest;
import jdwp.StackFrameCmds.GetValuesRequest.SlotInfo;
import jdwp.TunnelCmds.UpdateCacheRequest;
import jdwp.Value.BasicValue;
import jdwp.Value.ByteList;
import jdwp.Value.ListValue;
import jdwp.Value.Type;
import jdwp.VirtualMachineCmds.ClassesBySignatureRequest;
import jdwp.VirtualMachineCmds.DisposeObjectsRequest;
import jdwp.util.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.program.*;
import tunnel.synth.program.Evaluator.EvaluationAbortException;
import tunnel.synth.program.Evaluator.MapCallResult;
import tunnel.synth.program.Evaluator.MapCallResultEntry;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.*;
import static tunnel.synth.program.AST.*;
import static tunnel.synth.program.Functions.GET_FUNCTION;

/**
 * Test for {@link Program}, {@link Evaluator} and {@link Functions}.
 */
public class ProgramTest {

    private static final VM vm = new VM(0);

    @BeforeEach
    void clean() {
        vm.reset();
    }

    static class
    RecordingFunctions extends Functions {
        final List<Request<?>> requests = new ArrayList<>();
        final List<Value> values = new ArrayList<>();

        @Override
        protected Optional<Value> processRequest(Request<?> request) {
            requests.add(request);
            return Optional.of(wrap(10));
        }

        @Override
        public Function getFunction(String name) {
            if (name.equals("collect")) {
                return new Function("collect") {
                    @Override
                    protected Value evaluate(List<Value> arguments) {
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
                        "((for iter 1\n  (= ret 1)))",
                        List.of(
                                new Loop(
                                        ident("iter"),
                                        literal(1),
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
            "(map var0 xs 0 x ()=(get x 'a'))",
            "(map var0 xs 0 x ('a')=(get x 'a'))",
            "(map var0 xs 0 x ('a')=(get x 'a') ('b')=(get x 'b'))",
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
            "((= xs 1) (map var0 xs 0 x ()=(get x 'a')))",
            "((= xs 1) (map var0 xs 0 x ('a')=(get x 'a')))",
            "((= xs 1) (map var0 xs 0 x ('a')=(get x 'a') ('b')=(get x 'b')))",
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
            "((= ret (func2))),((= ret (func))),((= ret (func2)) (= var1 (func)))",
            "((= ret (func2)) (for iter 1 (= iter2 1)))," +
                    "((= ret (func)))," +
                    "((= ret (func2)) (for iter 1 (= iter2 1)) (= var1 (func)))",
            "((for iter 1 (= iter2 1)))," +
                    "((for iter 1 (= iter2 2)))," +
                    "((for iter 1 (= iter2 1) (= var2 2)))",
            "((for iter 1 (= iter1 1) (for iter2 2)))," +
                    "((for iter2 2) (for iter 1 (= iter1 2)))," +
                    "((for var1 2) (for iter 1 (= iter1 1) (for iter2 2) (= var3 2)))",
            "((= x 1) (switch x (case 'a'))),((= x 1) (switch x (case 'b'))),((= x 1) (switch x (case \"a\") (case " +
                    "\"b\")))",
            "((= x 1) (switch x (case 'a'))),((= x 1) (switch x (case 'a' (= y 1)))),((= x 1) (switch x (case \"a\" " +
                    "(= var2 1))))",
            "((= x 1) (switch x (case 'a')) (switch (const 1) (case 'a')))," +
                    "((= x 1) (switch x (case 'b')))," +
                    "((= x 1) (switch x (case \"a\") (case \"b\")) (switch (const 1) (case \"a\")))",
            "((rec r 10 var100 (request ClassType Superclass ('clazz')=(wrap 'class-type' 10)) " +
                    "(= y (request ReferenceType Interfaces ('refType')=(wrap 'klass' 11))) " +
                    "(reccall u r ('clazz')=(get var100 'superclass'))))," +
                    "((rec r 10 var101 (request ClassType Superclass ('clazz')=(wrap 'class-type' 10))" +
                    "(= y (request ReferenceType Interfaces ('refType')=(get var101 'superclass')))" +
                    "(reccall u r ('clazz')=(get var101 'superclass'))))," +
                    "((rec r 10 var100 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 10)) (= y " +
                    "(request ReferenceType Interfaces (\"refType\")=(wrap \"klass\" 11))) (= var102 (request " +
                    "ReferenceType Interfaces (\"refType\")=(get var100 \"superclass\"))) (reccall u r (\"clazz\")=" +
                    "(get var100 \"superclass\"))))"

    })
    public void testMerge(String program1, String program2, String merge) {
        var p1 = Program.parse(merge);
        var p2 = Program.parse(program1);
        var p3 = Program.parse(program2);
        assertEquals(p1.toString(), p2.merge(p3).toString());
    }

    @ParameterizedTest
    @CsvSource({
            "((= ret 1)),((= ret 1)),((= ret 1))",
            "((= ret 2)),((= ret 1)),()",
            "((= ret2 1) (= y (2)) (= r ret2)),((= ret 1) (= x (1)) (= r2 ret)),((= ret2 1) (= r " +
                    "ret2))",
            "((= ret2 1) (= r ret2)),((= ret 1) (= r2 ret)),((= ret2 1) (= r ret2))",
            "((for iter 1 (= var1 1))),((for iter 1 (= var1 2))),()",
            "((for iter 1 (= var1 1))),((for iter 1 (= var1 1)))," +
                    "((for iter 1 (= var1 1)))",
            "((= x 1) (switch x (case 'a'))),((= x 1) (switch x (case 'a'))),((= x 1))",
            "((= x 1) (switch x (case 'a' (= y 1)))),((= x 1) (switch x (case 'a' (= y 1)))),((= x 1) (switch x (case" +
                    " 'a' (= y 1))))",
            "((= x 1) (switch x (case 'a'))),((= x 1) (switch x (case 'b'))),((= x 1))",
            "((= x 1) (switch x (case 'a') (case 'b'))),((= x 1) (switch x (case 'a'))),((= x 1))",
            "((= x 1) (switch x (case 'a' (= v1 1) (= v2 2)))),((= x 1) (switch x (case 'a' (= v2 2)))),((= x 1) " +
                    "(switch" +
                    " x (case 'a' (= v2 2))))",
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
                {"(wrap 'class-type' 1129)", new ClassTypeReference(1129L)},
                {"(wrap 'void' 0)", new VoidValue()}
        };
    }

    @ParameterizedTest
    @MethodSource("wrapFunctionTestSource")
    public void testWrapFunction(String statement, BasicValue expectedValue) {
        assertEquals(
                new Evaluator(vm, new RecordingFunctions()).evaluate(Expression.parse(statement)),
                expectedValue);
        // round trip again
        assertEquals(
                new Evaluator(vm, new RecordingFunctions())
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
        assertEquals("\"", ((StringLiteral) Expression.parse("\"\\\"\"")).get());
    }

    @Test
    public void testGetFunctionCreation() {
        assertEquals("(get x 1 \"a\" 2)", Functions.createGetFunctionCall("x", new AccessPath(1, "a", 2)).toString());
    }

    @Test
    public void testParseGetFunctionWithExpression() {
        assertEquals(
                FunctionCall.<FunctionCall>parse("(get (get x 1) 1 \"a\" 2)"),
                GET_FUNCTION.createCall(GET_FUNCTION.createCall("x", new AccessPath(1)),
                        new AccessPath(1, "a", 2)));
    }

    private static Object[][] requestParsingTestSource() {
        return new Object[][]{
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
        new Evaluator(vm, funcs).evaluate(Expression.parse(requestStmt));
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
        assertEquals(new ClassesBySignatureRequest(0, wrap("test")), new Evaluator(vm, functions).evaluatePacketCall(
                PacketCall.parse(
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
                new Evaluator(vm, functions).evaluate(Program.parse("((= cause " + eventString + ") " +
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
        var ev = (Events) new Evaluator(vm, functions).evaluatePacketCall(PacketCall.parse(events));
        assertEquals(ev, new jdwp.EventCmds.Events(0, PrimitiveValue.wrap((byte) 2),
                new ListValue<>(new EventCmds.Events.VMStart(PrimitiveValue.wrap(0), new ThreadReference(0L)))));
        assertEquals("(events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")=(wrap " +
                "\"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 \"thread\")=" +
                "(wrap \"thread\" 0))", EventsCall.create(ev).toString());
    }

    private Request<?> evaluateRequestCall(RequestCall requestCall) {
        return (Request<?>) new Evaluator(vm, new RecordingFunctions()).evaluatePacketCall(new Scopes<>(), requestCall);
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
        var result = new Evaluator(vm, new RecordingFunctions())
                .evaluate(Program.parse("((= cause (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                        "(= var10 (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                        "(= var0 (get 'programs' 0)) (= var1 (get 'programs' 1)) " +
                        "(= var2 1))"));
        assertEquals(2, result.second.size());
    }

    @Test
    public void testEvaluatorInvalidPropertyAccess() {
        var result = new Evaluator(vm, new RecordingFunctions())
                .evaluate(Program.parse("((= cause (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                        "(= var10 (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                        "(= var0 (get 'programs')) (= var1 (get var0 'progx')) " +
                        "(= var2 2))"));
        assertEquals(1, result.second.size());
    }

    @Test
    public void testEvaluatorNoDiscardRequestHandling() {
        var result = new Evaluator(vm, new Functions() {
            @Override
            protected Optional<Value> processRequest(Request<?> request) {
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
        assertThrows(EvaluationAbortException.class, () -> new Evaluator(vm, new Functions() {
            @Override
            protected Optional<Value> processRequest(Request<?> request) {
                throw new EvaluationAbortException(true);
            }
        }).evaluate(Program.parse("((= cause (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a'))) " +
                "(= var0 (request Tunnel UpdateCache ('programs' 0)=(wrap 'string' 'a')))" +
                "(= var2 1))")));
    }

    @Test
    public void testUpdateCacheRequestEvaluation() {
        var pc = (UpdateCacheRequest) new Evaluator(vm, new RecordingFunctions())
                .evaluatePacketCall(PacketCall.parse("(request Tunnel UpdateCache " +
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
                "(map xs (get cause 'slots') 0 x ('a')=(get x 'sigbyte') ('b')=(get x 'slot')))");
        var scopes = new Evaluator(vm, new RecordingFunctions()).evaluate(program).first;
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
                "(map xs (get cause 'slots') 0 x ('x')=(get x 'sigbyte')))");
        var scopes = new Evaluator(vm, new RecordingFunctions()).evaluate(program).first;
        assertEquals(new MapCallResult(List.of(new MapCallResultEntry(Map.of("x", wrap((byte) 91))),
                new MapCallResultEntry(Map.of("x", wrap((byte) 73))))), scopes.get("xs"));
    }

    @Test
    public void testEvaluateSwitchStatement() {
        var program = Program.parse("((= x 1) (switch (const x) (case 1 (= v (collect 2))) (case 2 (= v (collect 1)))" +
                "))");
        var funcs = new RecordingFunctions();
        new Evaluator(vm, funcs).evaluate(program);
        assertEquals(List.of(wrap(2L)), funcs.values);
    }

    @Test
    public void testEvaluateSwitchStatementWithDefault() {
        var program = Program.parse("((= x 1) (switch (const x) (case 0 (= v (collect 2))) (default (= v (collect 1)))" +
                "))");
        var funcs = new RecordingFunctions();
        new Evaluator(vm, funcs).evaluate(program);
        assertEquals(List.of(wrap(1L)), funcs.values);
    }

    @Test
    public void testEvaluateSwitchStatement2() {
        var program = Program.parse("((= x 3) (switch (const x) (case 1 (= v (collect 2))) (case 2 (= v (collect 3)))" +
                "))");
        var funcs = new RecordingFunctions();
        new Evaluator(vm, funcs).evaluate(program);
        assertEquals(List.of(), funcs.values);
    }

    @Test
    public void testUseVariablesInCallProperties() {
        var program = Program.parse("((= x 'a') (= var1 (request Tunnel UpdateCache ('programs' 0)=x)))");
        var funcs = new RecordingFunctions();
        new Evaluator(vm, funcs).evaluate(program);
        assertEquals(List.of(new UpdateCacheRequest(0, new ListValue<>(wrap("a")))), funcs.requests);
    }

    /**
     * evaluates the synthesis result of {@link SynthesizerTest#testMapCallSynthesisForGetValuesRequest}
     */
    @Test
    public void testEvaluateMapCallGetValuesRequest() {
        var program = Program.parse("(\n" +
                "  (= var0 (request Method VariableTable ('methodID')=(wrap 'method' 32505856) " +
                "     ('refType')=(wrap 'klass' 10)))\n" +
                "  (map map0 (get var0 'slots') 0 iter0 ('sigbyte')=(getTagForSignature (get iter0 'signature')) " +
                "     ('slot')=(get iter0 'slot'))\n" +
                "  (= var1 (request StackFrame GetValues ('frame')=(wrap 'frame' 1) ('slots')=map0 " +
                "     ('thread')=(wrap 'thread' 1))))");
        var funcs = new RecordingFunctions();
        new Evaluator(vm, funcs).evaluate(program);
    }


    @Test
    public void testEvaluatePacketCallWithNonBasicValueExpression() {
        new Evaluator(new VM(9), new RecordingFunctions() {
            @Override
            public Function getFunction(String name) {
                return name.equals("slots") ? new Function("slots") {
                    @Override
                    protected Value evaluate(List<Value> arguments) {
                        return new ListValue<>(new SlotInfo(wrap(1), wrap((byte) 2)));
                    }
                } : super.getFunction(name);
            }
        }).evaluatePacketCall(new Scopes<>(), PacketCall.parse("(request StackFrame GetValues ('frame')=(wrap 'frame'" +
                " 1) ('slots')" +
                "=(slots) ('thread')=(wrap 'thread' 1))"));
    }

    @Test
    public void testParseRecursionStatement() {
        var name = ident("r");
        var requestVar = ident("var100");
        var recursion = new Program(null, null, List.of(new Recursion(name, 10, requestVar,
                RequestCall.create(new SuperclassRequest(0, Reference.classType(10))), new Body(
                new AssignmentStatement(ident("y"), RequestCall.create(new InterfacesRequest(1,
                        Reference.klass(11)))),
                new RecRequestCall(ident("x"), name, List.of(new CallProperty(new AccessPath("clazz"),
                        GET_FUNCTION.createCall(requestVar, new AccessPath("superclass")))))
        )))).initHashes(null);
        assertEquals("(\n" +
                "  (rec r 10 var100 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 10))\n" +
                "    (= y (request ReferenceType Interfaces (\"refType\")=(wrap \"klass\" 11)))\n" +
                "    (reccall x r (\"clazz\")=(get var100 \"superclass\"))))", recursion.toPrettyString());
        assertEquals(recursion, Program.parse(recursion.toPrettyString()));
    }

    private static RecordingFunctions createRecordingFunctions(Partition partition) {
        return new RecordingFunctions() {
            @Override
            protected Optional<Value> processRequest(Request<?> request) {
                super.processRequest(request);
                return Optional.of(partition.stream().filter(p -> p.first.equals(request))
                        .map(p -> p.second).findFirst().get().asCombined());
            }
        };
    }

    @Test
    public void testEvaluateRecursionWithLoop() {
        var program = Program.parse("(\n" +
                "  (rec recursion0 1000 var0 (request ReferenceType Interfaces (\"refType\")=(wrap \"klass\" 1))\n" +
                "    (for iter0 (get var0 \"interfaces\") \n" +
                "      (reccall u recursion0 (\"refType\")=iter0))))");
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
        var funcs = createRecordingFunctions(partition);
        new Evaluator(vm, funcs).evaluate(program);
        assertEquals(partition.stream().map(p -> p.first).collect(Collectors.toSet()), new HashSet<>(funcs.requests));
    }

    @Test
    public void testEvaluateRecursionWithoutLoop() {
        var program = Program.parse("(\n" +
                "  (rec recursion0 1000 var0 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 1))\n" +
                "    (reccall u recursion0 (\"clazz\")=(get var0 \"superclass\"))))");
        BiFunction<Integer, Long, Pair<SuperclassRequest, SuperclassReply>> interfacesCreator = (id, superClass) ->
                p(new SuperclassRequest(id, Reference.classType(id)), new SuperclassReply(id,
                        Reference.classType(superClass)));
        var partition = new Partition(null, List.of(
                interfacesCreator.apply(1, 2L),
                interfacesCreator.apply(2, 3L),
                interfacesCreator.apply(3, 0L)));
        var funcs = createRecordingFunctions(partition);
        new Evaluator(vm, funcs).evaluate(program);
        assertEquals(partition.stream().map(p -> p.first).collect(Collectors.toList()), funcs.requests);
    }

    @Test
    public void testEvaluateRecursionWithoutLoop2() {
        var program = Program.parse("(\n" +
                "  (rec recursion0 1000 var0 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 1))\n" +
                "    (reccall u recursion0 (\"clazz\")=(get var0 \"superclass\"))" +
                "    (= x (collect u))))");
        BiFunction<Integer, Long, Pair<SuperclassRequest, SuperclassReply>> interfacesCreator = (id, superClass) ->
                p(new SuperclassRequest(id, Reference.classType(id)), new SuperclassReply(id,
                        Reference.classType(superClass)));
        var partition = new Partition(null, List.of(
                interfacesCreator.apply(1, 2L),
                interfacesCreator.apply(2, 3L),
                interfacesCreator.apply(3, 0L)));
        var funcs = createRecordingFunctions(partition);
        new Evaluator(vm, funcs).evaluate(program);
        assertEquals(partition.stream().map(p -> p.first).collect(Collectors.toList()), funcs.requests);
        assertEquals(List.of(partition.get(1).second.asCombined()), funcs.values);
    }

    @Test
    public void testMergeWithDifferentNames() {
        assertEquals("((= x 1) (= var2 x))", Program.parse("((= x 1))")
                .merge(Program.parse("((= y 1) (= z y))")).toString());
    }

    @Test
    public void testMergeWithSameNames() {
        assertEquals("((= y 2) (= var1 1) (= var2 var1))", Program.parse("((= y 2))")
                .merge(Program.parse("((= y 1) (= z y))")).toString());
    }

    private static Object[][] mergeOfProgramsTestSource() {
        return new Object[][]{
                {"((= cause (request VirtualMachine Resume))\n" +
                        "  (= var0 (request VirtualMachine Resume)))",
                        "((= cause (request VirtualMachine Resume))\n" +
                                "  (= var0 (request VirtualMachine Resume)))",
                        "((= cause (request VirtualMachine Resume))\n" +
                                "  (= var0 (request VirtualMachine Resume)))"},
                {
                        "((= cause (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var0 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"thread\"))))",
                        "((= cause (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var0 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"thread\"))))",
                        "((= cause (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var0 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"thread\"))))"
                }, {
                "((= cause (request VirtualMachine IDSizes))\n" +
                        "  (= var0 (request VirtualMachine IDSizes))\n" +
                        "  (= var2 (request VirtualMachine TopLevelThreadGroups)))",
                "((= cause (request VirtualMachine IDSizes))\n" +
                        "  (= var0 (request VirtualMachine IDSizes))\n" +
                        "  (= var2 (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" " +
                        "\"Lcom/springsource/loaded/agent/SpringLoadedAgent;\")))\n" +
                        "  (= var3 (request VirtualMachine TopLevelThreadGroups)))",
                "((= cause (request VirtualMachine IDSizes))\n" +
                        "  (= var0 (request VirtualMachine IDSizes))\n" +
                        "  (= var4 (request VirtualMachine ClassesBySignature (\"signature\")=(wrap \"string\" " +
                        "\"Lcom/springsource/loaded/agent/SpringLoadedAgent;\")))\n" +
                        "  (= var2 (request VirtualMachine TopLevelThreadGroups)))"
        }, {
                "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 1) (\"events\" 0 \"kind\")=" +
                        "(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 " +
                        "\"thread\")=(wrap \"thread\" 0)" +
                        "(\"events\" 1 \"kind\")=(wrap \"string\" \"VMStart\") (\"events\" 1 \"requestID\")=(wrap" +
                        " \"int\" 0) (\"events\" 1 \"thread\")=(wrap \"thread\" 1)))\n" +
                        "  (= var0 (request ReferenceType SourceFile (\"refType\")=(get cause \"events\" 0 " +
                        "\"typeID\")))\n" +
                        "  (= var1 (request ReferenceType SourceDebugExtension (\"refType\")=(get cause " +
                        "\"events\" 0 \"typeID\")))\n" +
                        "  (= var2 (request ReferenceType MethodsWithGeneric (\"refType\")=(get cause \"events\" " +
                        "0 \"typeID\")))\n" +
                        "  (for iter0 (get var2 \"declared\") \n" +
                        "    (= var3 (request Method LineTable (\"methodID\")=(get iter0 \"methodID\") " +
                        "(\"refType\")=(get cause \"events\" 0 \"typeID\")))))",
                "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 1) (\"events\" 0 \"kind\")=" +
                        "(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 " +
                        "\"thread\")=(wrap \"thread\" 1) " +
                        "(\"events\" 1 \"kind\")=(wrap \"string\" \"VMStart\") (\"events\" 1 \"requestID\")=(wrap " +
                        "\"int\" 0) (\"events\" 1 \"thread\")=(wrap \"thread\" 0)))\n" +
                        "  (= var0 (request ReferenceType SourceFile (\"refType\")=(get cause \"events\" 0 " +
                        "\"typeID\")))\n" +
                        "  (= var1 (request ReferenceType SourceDebugExtension (\"refType\")=(get cause " +
                        "\"events\" 0 \"typeID\")))\n" +
                        "  (= var2 (request ReferenceType MethodsWithGeneric (\"refType\")=(get cause \"events\" " +
                        "0 \"typeID\")))\n" +
                        "  (for iter0 (get var2 \"declared\") \n" +
                        "    (= var3 (request Method LineTable (\"methodID\")=(get iter0 \"methodID\") " +
                        "(\"refType\")=(get cause \"events\" 0 \"typeID\")))))",
                "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 1) (\"events\" 0 \"kind\")=" +
                        "(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 " +
                        "\"thread\")=(wrap \"thread\" 0) (\"events\" 1 \"kind\")=(wrap \"string\" \"VMStart\") " +
                        "(\"events\" 1 \"requestID\")=(wrap \"int\" 0) (\"events\" 1 \"thread\")=(wrap \"thread\" 1))" +
                        ")\n" +
                        "  (= var0 (request ReferenceType SourceFile (\"refType\")=(get cause \"events\" 0 " +
                        "\"typeID\")))\n" +
                        "  (= var1 (request ReferenceType SourceDebugExtension (\"refType\")=(get cause \"events\" 0 " +
                        "\"typeID\")))\n" +
                        "  (= var2 (request ReferenceType MethodsWithGeneric (\"refType\")=(get cause \"events\" 0 " +
                        "\"typeID\")))\n" +
                        "  (for iter0 (get var2 \"declared\") \n" +
                        "    (= var3 (request Method LineTable (\"methodID\")=(get iter0 \"methodID\") (\"refType\")=" +
                        "(get cause \"events\" 0 \"typeID\")))))"
        },
                {
                        "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                                "\"kind\")=" +
                                "(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) " +
                                "(\"events\" " +
                                "0 \"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var0 (request VirtualMachine IDSizes))\n" +
                                "  (= var3 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 2)))\n" +
                                "  (= var5 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var2 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 3)))\n" +
                                "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 " +
                                "\"thread\"))))",
                        "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                                "\"kind\")=(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" " +
                                "0) (\"events\" 0 \"thread\")=(wrap \"thread\" 3)))\n" +
                                "  (= var0 (request VirtualMachine IDSizes))\n" +
                                "  (= var3 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 2)))\n" +
                                "  (= var1 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var2 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 " +
                                "\"thread\"))))",
                        "((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 " +
                                "\"kind\")=(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" " +
                                "0) (\"events\" 0 \"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var0 (request VirtualMachine IDSizes))\n" +
                                "  (= var3 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 2)))\n" +
                                "  (= var5 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var2 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 3)))\n" +
                                "  (= var1 (request ThreadReference Name (\"thread\")=(get cause \"events\" 0 " +
                                "\"thread\"))))"
                },
                {
                        "((= cause (request EventRequest Clear (\"eventKind\")=(wrap \"byte\" 1) (\"requestID\")=" +
                                "(wrap " +
                                "\"int\" 68)))\n" +
                                "  (= var0 (request EventRequest Clear (\"eventKind\")=(wrap \"byte\" 1) " +
                                "(\"requestID\")=" +
                                "(wrap \"int\" 68)))\n" +
                                "  (= var1 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var2 (request ThreadReference Status (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var3 (request ThreadReference Frames (\"length\")=(wrap \"int\" 1) " +
                                "(\"startFrame\")" +
                                "=(wrap \"int\" 0) (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var4 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var5 (request Method LineTable (\"methodID\")=(get var3 \"frames\" 0 " +
                                "\"location\" " +
                                "\"methodRef\") (\"refType\")=(get var3 \"frames\" 0 \"location\" \"declaringType\"))" +
                                ")\n" +
                                "  (= var6 (request Method IsObsolete (\"methodID\")=(get var3 \"frames\" 0 " +
                                "\"location\" " +
                                "\"methodRef\") (\"refType\")=(get var3 \"frames\" 0 \"location\" \"declaringType\"))" +
                                ")\n" +
                                "  (= var7 (request ThreadReference Frames (\"length\")=(get var4 \"frameCount\") " +
                                "(\"startFrame\")=(wrap \"int\" 0) (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (rec recursion0 1000 var9 (request ReferenceType Interfaces (\"refType\")=(wrap " +
                                "\"klass\" 658))\n" +
                                "    (for var10 (get var9 \"interfaces\") \n" +
                                "      (= var11 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var3 " +
                                "\"interfaces\" 0)))))\n" +
                                "  (= var12 (request ReferenceType SourceFile (\"refType\")=(wrap \"klass\" 658)))\n" +
                                "  (= var11 (request ReferenceType SourceDebugExtension (\"refType\")=(wrap \"klass\"" +
                                " " +
                                "658)))\n" +
                                "  (= var13 (request ReferenceType SignatureWithGeneric (\"refType\")=(wrap \"klass\"" +
                                " " +
                                "658)))\n" +
                                "  (= var14 (request ReferenceType FieldsWithGeneric (\"refType\")=(wrap \"klass\" " +
                                "658)))" +
                                "\n" +
                                "  (= var15 (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" " +
                                "658))" +
                                ")\n" +
                                "  (= var16 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 658)))\n" +
                                "  (= var17 (request ThreadReference Frames (\"length\")=(wrap \"int\" 2) " +
                                "(\"startFrame\")=(wrap \"int\" 0) (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var18 (request ReferenceType Interfaces (\"refType\")=(get var16 " +
                                "\"superclass\")))" +
                                "\n" +
                                "  (= var19 (request ReferenceType SignatureWithGeneric (\"refType\")=(get var16 " +
                                "\"superclass\")))\n" +
                                "  (= var20 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var16 " +
                                "\"superclass\")))\n" +
                                "  (= var21 (request ClassType Superclass (\"clazz\")=(get var16 \"superclass\")))\n" +
                                "  (= var22 (request ReferenceType Interfaces (\"refType\")=(get var18 \"interfaces\"" +
                                " 0))))",
                        "((= cause (request EventRequest Clear (\"eventKind\")=(wrap \"byte\" 1) (\"requestID\")=" +
                                "(wrap \"int\" 15)))\n" +
                                "  (= var0 (request EventRequest Clear (\"eventKind\")=(wrap \"byte\" 1) " +
                                "(\"requestID\")=(wrap \"int\" 15)))\n" +
                                "  (= var1 (request ReferenceType SourceFile (\"refType\")=(wrap \"klass\" 658)))\n" +
                                "  (= var2 (request ReferenceType Interfaces (\"refType\")=(wrap \"klass\" 658)))\n" +
                                "  (= var3 (request ReferenceType SourceDebugExtension (\"refType\")=(wrap \"klass\" " +
                                "658)))\n" +
                                "  (= var4 (request ReferenceType SignatureWithGeneric (\"refType\")=(wrap \"klass\" " +
                                "658)))\n" +
                                "  (= var5 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 658)))\n" +
                                "  (= var6 (request Method LineTable (\"methodID\")=(wrap \"method\" 105553141894768)" +
                                " (\"refType\")=(wrap \"klass\" 658)))\n" +
                                "  (= var7 (request ThreadReference Status (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var9 (request ReferenceType Interfaces (\"refType\")=(get var2 \"interfaces\" " +
                                "0)))\n" +
                                "  (= var8 (request ReferenceType Interfaces (\"refType\")=(get var5 \"superclass\"))" +
                                ")\n" +
                                "  (= var10 (request ClassType Superclass (\"clazz\")=(get var5 \"superclass\")))\n" +
                                "  (= var11 (request ReferenceType Interfaces (\"refType\")=(get var8 \"interfaces\" " +
                                "0)))\n" +
                                "  (= var12 (request ReferenceType SignatureWithGeneric (\"refType\")=(get var8 " +
                                "\"interfaces\" 0))))",
                        "((= cause (request EventRequest Clear (\"eventKind\")=(wrap \"byte\" 1) (\"requestID\")=" +
                                "(wrap \"int\" 68)))\n" +
                                "  (= var0 (request EventRequest Clear (\"eventKind\")=(wrap \"byte\" 1) " +
                                "(\"requestID\")=(wrap \"int\" 68)))\n" +
                                "  (= var1 (request ThreadReference Name (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var2 (request ThreadReference Status (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var3 (request ThreadReference Frames (\"length\")=(wrap \"int\" 1) " +
                                "(\"startFrame\")=(wrap \"int\" 0) (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var4 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var5 (request Method LineTable (\"methodID\")=(get var3 \"frames\" 0 " +
                                "\"location\" \"methodRef\") (\"refType\")=(get var3 \"frames\" 0 \"location\" " +
                                "\"declaringType\")))\n" +
                                "  (= var6 (request Method IsObsolete (\"methodID\")=(get var3 \"frames\" 0 " +
                                "\"location\" \"methodRef\") (\"refType\")=(get var3 \"frames\" 0 \"location\" " +
                                "\"declaringType\")))\n" +
                                "  (= var7 (request ThreadReference Frames (\"length\")=(get var4 \"frameCount\") " +
                                "(\"startFrame\")=(wrap \"int\" 0) (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (rec recursion0 1000 var9 (request ReferenceType Interfaces (\"refType\")=(wrap " +
                                "\"klass\" 658))\n" +
                                "    (for var10 (get var9 \"interfaces\") \n" +
                                "      (= var11 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var3 " +
                                "\"interfaces\" 0)))))\n" +
                                "  (= var12 (request ReferenceType SourceFile (\"refType\")=(wrap \"klass\" 658)))\n" +
                                "  (= var11 (request ReferenceType SourceDebugExtension (\"refType\")=(wrap \"klass\"" +
                                " 658)))\n" +
                                "  (= var13 (request ReferenceType SignatureWithGeneric (\"refType\")=(wrap \"klass\"" +
                                " 658)))\n" +
                                "  (= var14 (request ReferenceType FieldsWithGeneric (\"refType\")=(wrap \"klass\" " +
                                "658)))\n" +
                                "  (= var15 (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" " +
                                "658)))\n" +
                                "  (= var16 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 658)))\n" +
                                "  (= var17 (request ThreadReference Frames (\"length\")=(wrap \"int\" 2) " +
                                "(\"startFrame\")=(wrap \"int\" 0) (\"thread\")=(wrap \"thread\" 1)))\n" +
                                "  (= var18 (request ReferenceType Interfaces (\"refType\")=(get var16 " +
                                "\"superclass\")))\n" +
                                "  (= var19 (request ReferenceType SignatureWithGeneric (\"refType\")=(get var16 " +
                                "\"superclass\")))\n" +
                                "  (= var20 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var16 " +
                                "\"superclass\")))\n" +
                                "  (= var21 (request ClassType Superclass (\"clazz\")=(get var16 \"superclass\")))\n" +
                                "  (= var22 (request ReferenceType Interfaces (\"refType\")=(get var18 \"interfaces\"" +
                                " 0))))"
                }
        };
    }

    @ParameterizedTest
    @MethodSource("mergeOfProgramsTestSource")
    public void testMergeOfPrograms(String program1, String program2, String expected) {
        assertEquals(Program.parse(expected).toPrettyString(),
                Program.parse(program1).merge(Program.parse(program2)).toPrettyString());
    }

    private static Object[][] removeStatementTestSource() {
        return new Object[][]{
                {0, "(\n" +
                        "  (= var1 (request ClassObjectReference ReflectedType (\"classObject\")=(wrap " +
                        "\"classObject\" 1135)))\n" +
                        "  (= var2 (request ReferenceType Interfaces (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var3 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var4 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\")))\n" +
                        "  (= var5 (request ReferenceType GetValues (\"refType\")=(get var1 \"typeID\") (\"fields\" 0" +
                        " " +
                        "\"fieldID\")=(get var3 \"declared\" 1 \"fieldID\")))\n" +
                        "  (= var7 (request ReferenceType Interfaces (\"refType\")=(get var4 \"superclass\")))\n" +
                        "  (for iter1 (get var7 \"interfaces\"))\n" +
                        "  (= var8 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var4 \"superclass\")))" +
                        "\n" +
                        "  (= var9 (request ClassType Superclass (\"clazz\")=(get var4 \"superclass\"))))"},
                {1, "((= cause (request VirtualMachine Resume))\n" +
                        "  (= var0 (request VirtualMachine Resume)))"},
                {2, "((= cause (request VirtualMachine Resume))\n" + // simple as statement has no dependents
                        "  (= var0 (request VirtualMachine Resume))\n" +
                        "  (= var1 (request ClassObjectReference ReflectedType (\"classObject\")=(wrap " +
                        "\"classObject\" 1135)))\n" +
                        "  (= var3 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var4 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\")))\n" +
                        "  (= var5 (request ReferenceType GetValues (\"refType\")=(get var1 \"typeID\") (\"fields\" 0" +
                        " \"fieldID\")=(get var3 \"declared\" 1 \"fieldID\")))\n" +
                        "  (= var7 (request ReferenceType Interfaces (\"refType\")=(get var4 \"superclass\")))\n" +
                        "  (for iter1 (get var7 \"interfaces\"))\n" +
                        "  (= var8 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var4 \"superclass\")))" +
                        "\n" +
                        "  (= var9 (request ClassType Superclass (\"clazz\")=(get var4 \"superclass\"))))"},
                {3, "((= cause (request VirtualMachine Resume))\n" +
                        "  (= var0 (request VirtualMachine Resume))\n" +
                        "  (= var1 (request ClassObjectReference ReflectedType (\"classObject\")=(wrap " +
                        "\"classObject\" 1135)))\n" +
                        "  (= var2 (request ReferenceType Interfaces (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var4 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\")))\n" +
                        "  (= var7 (request ReferenceType Interfaces (\"refType\")=(get var4 \"superclass\")))\n" +
                        "  (for iter1 (get var7 \"interfaces\"))\n" +
                        "  (= var8 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var4 \"superclass\")))" +
                        "\n" +
                        "  (= var9 (request ClassType Superclass (\"clazz\")=(get var4 \"superclass\"))))"},
                {4, "((= cause (request VirtualMachine Resume))\n" +
                        "  (= var0 (request VirtualMachine Resume))\n" +
                        "  (= var1 (request ClassObjectReference ReflectedType (\"classObject\")=(wrap " +
                        "\"classObject\" 1135)))\n" +
                        "  (= var2 (request ReferenceType Interfaces (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var3 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var5 (request ReferenceType GetValues (\"refType\")=(get var1 \"typeID\") (\"fields\" 0" +
                        " \"fieldID\")=(get var3 \"declared\" 1 \"fieldID\"))))"},
                {6, "((= cause (request VirtualMachine Resume))\n" +
                        "  (= var0 (request VirtualMachine Resume))\n" +
                        "  (= var1 (request ClassObjectReference ReflectedType (\"classObject\")=(wrap " +
                        "\"classObject\" 1135)))\n" +
                        "  (= var2 (request ReferenceType Interfaces (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var3 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var4 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\")))\n" +
                        "  (= var5 (request ReferenceType GetValues (\"refType\")=(get var1 \"typeID\") (\"fields\" 0" +
                        " \"fieldID\")=(get var3 \"declared\" 1 \"fieldID\")))\n" +
                        "  (= var8 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var4 \"superclass\")))" +
                        "\n" +
                        "  (= var9 (request ClassType Superclass (\"clazz\")=(get var4 \"superclass\"))))"},
                {7, "((= cause (request VirtualMachine Resume))\n" +
                        "  (= var0 (request VirtualMachine Resume))\n" +
                        "  (= var1 (request ClassObjectReference ReflectedType (\"classObject\")=(wrap " +
                        "\"classObject\" 1135)))\n" +
                        "  (= var2 (request ReferenceType Interfaces (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var3 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var1 \"typeID\")))\n" +
                        "  (= var4 (request ClassType Superclass (\"clazz\")=(get var1 \"typeID\")))\n" +
                        "  (= var5 (request ReferenceType GetValues (\"refType\")=(get var1 \"typeID\") (\"fields\" 0" +
                        " \"fieldID\")=(get var3 \"declared\" 1 \"fieldID\")))\n" +
                        "  (= var7 (request ReferenceType Interfaces (\"refType\")=(get var4 \"superclass\")))\n" +
                        "  (= var8 (request ReferenceType FieldsWithGeneric (\"refType\")=(get var4 \"superclass\")))" +
                        "\n" +
                        "  (= var9 (request ClassType Superclass (\"clazz\")=(get var4 \"superclass\"))))"}
        };
    }

    @ParameterizedTest
    @MethodSource("removeStatementTestSource")
    public void testRemoveStatement(int removedStatement, String expectedResult) {
        var program = Program.parse("((= cause (request VirtualMachine Resume)) " +
                "(= var0 (request VirtualMachine Resume)) \n" +
                "        (= var1 (request ClassObjectReference ReflectedType ('classObject')=(wrap 'classObject' " +
                "1135)" +
                ")) \n" +
                "        (= var2 (request ReferenceType Interfaces ('refType')=(get var1 'typeID'))) \n" +
                "        (= var3 (request ReferenceType FieldsWithGeneric ('refType')=(get var1 'typeID'))) \n" +
                "        (= var4 (request ClassType Superclass ('clazz')=(get var1 'typeID'))) \n" + // this can fail
                "        (= var5 (request ReferenceType GetValues ('refType')=(get var1 'typeID') ('fields' 0 " +
                "'fieldID')=(get var3 'declared' 1 'fieldID'))) \n" + // this can fail
                "        (= var7 (request ReferenceType Interfaces ('refType')=(get var4 'superclass'))) \n" +
                "        (for iter1 (get var7 'interfaces')) \n" + // leading this to crash
                "            (= var8 (request ReferenceType FieldsWithGeneric ('refType')=(get var4 'superclass'))) " +
                "\n" +
                "            (= var9 (request ClassType Superclass ('clazz')=(get var4 'superclass'))))");
        var statement = program.getBody().get(removedStatement);
        System.out.println(statement);
        assertEquals(expectedResult, program.removeStatements(Set.of(statement)).toPrettyString());
    }

    @Test
    public void testCollectStatementsAndRemoveStatements() {
        var program = Program.parse("((= cause (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" 649)))\n" +
                "  (= var0 (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" 649)))\n" +
                "  (= var1 (request ReferenceType SourceFile (\"refType\")=(get cause \"refType\")))\n" +
                "  (= var2 (request ReferenceType SourceDebugExtension (\"refType\")=(get cause \"refType\")))\n" +
                "  (= var3 (request Method LineTable (\"methodID\")=(get var0 \"declared\" 1 \"methodID\") " +
                "(\"refType\")=(get cause \"refType\")))\n" +
                "  (= var4 (request Method LineTable (\"methodID\")=(get var0 \"declared\" 0 \"methodID\") " +
                "(\"refType\")=(get cause \"refType\"))))");
        assertFalse(program.collectBodyStatements().contains(program));
        assertEquals(program.toPrettyString(), program.removeStatements(Set.of()).toPrettyString());
    }

    @Test
    public void testCollectStatementsAndRemoveStatementsSmall() {
        var program = Program.parse("((= cause (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" 649)))\n" +
                "  (= var0 (request ReferenceType MethodsWithGeneric (\"refType\")=(wrap \"klass\" 649)))\n" +
                "  (= var1 (request ReferenceType SourceFile (\"refType\")=(get cause \"refType\"))))");
        assertFalse(program.collectBodyStatements().contains(program));
        assertEquals(program.toPrettyString(), program.removeStatements(Set.of()).toPrettyString());
    }

    @Test
    public void testRemoveVariableUsedInMapStatement() {
        var program = Program.parse("((= cause (request ReferenceType FieldsWithGeneric (\"refType\")=(wrap \"klass\"" +
                " 649)))\n" +
                "(= var0 (request ReferenceType FieldsWithGeneric (\"refType\")=(wrap \"klass\" 649)))" +
                "  (= var4 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 470)))\n" +
                "  (= var5 (request Method VariableTableWithGeneric (\"methodID\")=(wrap \"method\" 105553173813640) " +
                "(\"refType\")=(get cause \"refType\")))\n" +
                "  (map map0 (get var5 \"slots\") 0 iter1 (\"sigbyte\")=(getTagForSignature (get iter1 \"signature\")" +
                ") (\"slot\")=(get iter1 \"slot\"))\n" +
                "  (= var6 (request StackFrame GetValues (\"frame\")=(wrap \"frame\" 131072) (\"slots\")=map0 " +
                "(\"thread\")=(wrap \"thread\" 1))))");
        assertEquals("((= cause (request ReferenceType FieldsWithGeneric (\"refType\")=(wrap \"klass\" 649)))\n" +
                        "  (= var0 (request ReferenceType FieldsWithGeneric (\"refType\")=(wrap \"klass\" 649)))\n" +
                        "  (= var4 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 470))))",
                program.removeStatements(Set.of(program.getBody().get(2))).toPrettyString());
        assertEquals("(\n" +
                        "  (= var0 (request ReferenceType FieldsWithGeneric (\"refType\")=(wrap \"klass\" 649)))\n" +
                        "  (= var4 (request ClassType Superclass (\"clazz\")=(wrap \"class-type\" 470))))",
                program.removeStatements(Set.of(program.getCauseStatement())).toPrettyString());
    }

    @Test
    public void testObjectFunction() {
        var program = FunctionCall.parse("(object (0)=1)");
        var list = new ListValue<>(Type.LONG, wrap(1L));
        assertEquals(list, new Evaluator(vm, new RecordingFunctions()).evaluate(program));
        assertEquals("(object (0)=(wrap \"long\" 1))", Functions.createObjectFunctionCall(list).toString());
    }

    @ParameterizedTest
    @CsvSource({
            "(object ('a')=(wrap 'long' 1))",
            "(object ('a')=(wrap 'long' 1) ('b')=(wrap 'long' 2) ('c' 0)=(wrap 'long' 3) ('c' 1)=(wrap 'long' 3))",
            "(object (0 'a')=(wrap 'long' 1) (1 'a')=(wrap 'long' 1) (1 'b')=(wrap 'long' 2) (2 'c' 0)=(wrap 'long' " +
                    "3) (2 'c' 1)=(wrap 'long' 3))",
            "(wrap 'thread' 1)"
    })
    public void testObjectFunctionRoundTrip(String functionCall) {
        var program = FunctionCall.parse(functionCall);
        var ret = new Evaluator(vm, new RecordingFunctions()).evaluate(program);
        assertEquals(functionCall,
                Functions.createWrappingFunctionCall(ret).toString().replace("\"", "'"));
    }

    @Test
    public void testRemoveDirectPointerRelatedStatementsWithoutCause() {
        var program = Program.parse("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 2) " +
                "(\"suspendPolicy\")=(wrap \"byte\" 2) (\"modifiers\" 0 \"kind\")=(wrap \"string\" \"LocationOnly\") " +
                "(\"modifiers\" 0 \"loc\" \"codeIndex\")=(wrap \"long\" 9) (\"modifiers\" 0 \"loc\" " +
                "\"declaringType\")=(wrap \"class-type\" 649) (\"modifiers\" 0 \"loc\" \"methodRef\")=(wrap " +
                "\"method\" 105553138414288)))\n" +
                "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 2) (\"suspendPolicy\")=(wrap " +
                "\"byte\" 2) (\"modifiers\" 0 \"kind\")=(wrap \"string\" \"LocationOnly\") (\"modifiers\" 0 \"loc\" " +
                "\"codeIndex\")=(wrap \"long\" 9) (\"modifiers\" 0 \"loc\" \"declaringType\")=(wrap \"class-type\" " +
                "649) (\"modifiers\" 0 \"loc\" \"methodRef\")=(wrap \"method\" 105553138414288)))\n" +
                "  (= var1 (request ReferenceType Interfaces (\"refType\")=(get cause \"modifiers\" 0 \"loc\" " +
                "\"declaringType\"))))");
        assertEquals(program.toPrettyString(),
                program.removeDirectPointerRelatedStatementsTransitivelyWithoutCause().toPrettyString());
    }

    @Test
    public void testShadowingInParser() {
        var program = Program.parse("((= var18 (request ReferenceType Interfaces (\"refType\")=(get var14 \"typeID\")" +
                ")" +
                ")\n" +
                "        (= var19 (request ReferenceType SourceDebugExtension (\"refType\")=(get var14 \"typeID\")))" +
                "\n" +
                "        (for iter2 (get var18 \"interfaces\") \n" +
                "          (= var19 (request ReferenceType Interfaces (\"refType\")=iter2)))))");
    }

    @Test
    public void testBodyLayers() {
        var program = Program.parse("((= x 1) (= y x) (= z 2))");
        var layers = program.getBody().computeLayers();
        assertEquals(2, layers.size());
    }

    @Test
    public void testBodySort() {
        var program = Program.parse("((= x 1) (= y x) (= z 2))");
        var sorted = program.sort();
        assertEquals("((= x 1) (= z 2) (= y x))", sorted.toString());
    }

    @Test
    public void testParseCauseOnlyProgram() {
        var program = new Program(ident("cause"), new EventsCall("Event", "Composite", List.of()), List.of());
        assertEquals(program, Program.parse(program.toPrettyString()));
    }
}
