package tunnel.synth.program;

import jdwp.*;
import jdwp.Value.CombinedValue;
import jdwp.Value.ListValue;
import jdwp.Value.TaggedValue;
import jdwp.Value.WalkableValue;
import jdwp.util.Pair;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.program.AST.*;
import tunnel.synth.program.Visitors.ReturningExpressionVisitor;
import tunnel.synth.program.Visitors.StatementVisitor;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.Value.Type.OBJECT;
import static jdwp.util.Pair.p;
import static tunnel.synth.Analyser.LOG;
import static tunnel.synth.Synthesizer.CAUSE_NAME;

public class Evaluator {

    /** entry of the list stored by an evaluation of {@link MapCallStatement} */
    public static class MapCallResultEntry extends CombinedValue {

        private final Map<String, Value> entries;

        public MapCallResultEntry(Map<String, Value> entries) {
            super(OBJECT);
            this.entries = entries;
        }

        @Override
        public boolean containsKey(String key) {
            return entries.containsKey(key);
        }

        @Override
        public List<String> getKeys() {
            return entries.keySet().stream().sorted().collect(Collectors.toList());
        }

        @Override
        public Value get(String key) {
            return entries.get(key);
        }

        @Override
        public String toString() {
            return entries.toString();
        }

        public List<TaggedValue<?>> toTaggedValues(AccessPath basePath) {
            return entries.entrySet().stream()
                    .map(e -> new TaggedValue<>(basePath.append(e.getKey()), e.getValue()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * list stored by an evaluation of {@link MapCallStatement}
     */
    public static class MapCallResult extends ListValue<MapCallResultEntry> {

        public MapCallResult(List<MapCallResultEntry> values) {
            super(OBJECT, values);
        }

        public List<TaggedValue<?>> toTaggedValues(AccessPath basePath) {
            return getValues().stream()
                    .flatMap(e -> ((MapCallResultEntry) e.second).toTaggedValues(basePath.append(e.first)).stream())
                    .collect(Collectors.toList());
        }
    }

    /**
     * Can be thrown be functions (and request/event call handlers) to signify that something
     * went wrong
     */
    @Getter
    public static class EvaluationAbortException extends RuntimeException {
        /**
         * discard whole evaluation (and not only the current call)
         */
        private final boolean discard;

        public EvaluationAbortException(boolean discard) {
            this.discard = discard;
        }

        public EvaluationAbortException() {
            this(false);
        }

        public EvaluationAbortException(boolean discard, String message) {
            super(message);
            this.discard = discard;
        }

        public EvaluationAbortException(boolean discard, String message, Throwable cause) {
            super(message, cause);
            this.discard = discard;
        }
    }

    /**
     * default id for requests
     */
    public static final int DEFAULT_ID = 0;
    private final VM vm;
    private final Functions functions;
    private final Consumer<EvaluationAbortException> errorConsumer;

    public Evaluator(VM vm, Functions functions) {
        this(vm, functions, e -> {
        });
    }

    public Evaluator(VM vm, Functions functions, Consumer<EvaluationAbortException> errorConsumer) {
        this.vm = vm;
        this.functions = functions;
        this.errorConsumer = errorConsumer;
    }

    /**
     * @return (scope, [not evaluated statements])
     * @throws EvaluationAbortException with discard=true if the evaluation had severe problems
     */
    public Pair<Scopes<Value>, Set<Statement>> evaluate(Program program) {
        var scope = new Scopes<Value>();
        if (program.hasCause()) {
            evaluate(scope, new Body(List.of(program.getCauseStatement())));
        }
        return evaluate(scope, program.getBody());
    }

    /**
     * @throws EvaluationAbortException with discard=true if the evaluation had severe problems
     */
    private Pair<Scopes<Value>, Set<Statement>> evaluate(Scopes<Value> scope, Body body) {
        Map<Recursion, Integer> recursiveEvaluations = new HashMap<>();
        Stack<Set<Statement>> notEvaluatedScopes = new Stack<>();
        notEvaluatedScopes.push(new HashSet<>());
        var notEvaluated = new HashSet<Statement>();
        body.accept(
                new StatementVisitor() {

                    private void pushNotEvaluatedScope() {
                        notEvaluatedScopes.push(new HashSet<>());
                    }

                    private void popNotEvaluatedScope() {
                        notEvaluatedScopes.pop();
                    }

                    private void addToNotEvaluated(Statement statement) {
                        notEvaluatedScopes.peek().addAll(body.getDependentStatementsAndAnchor(statement));
                        notEvaluated.addAll(notEvaluatedScopes.peek());
                    }

                    private boolean isNotEvaluated(Statement statement) {
                        return notEvaluatedScopes.stream().anyMatch(s -> s.contains(statement));
                    }

                    @Override
                    public void visit(Loop loop) {
                        if (isNotEvaluated(loop)) {
                            return;
                        }
                        Value iterable;
                        try {
                            iterable = evaluate(scope, loop.getIterable());
                        } catch (AssertionError | Exception e) {
                            addToNotEvaluated(loop);
                            throw new EvaluationAbortException(
                                    e instanceof EvaluationAbortException && ((EvaluationAbortException) e).discard,
                                    "loop header evaluation failed", e);
                        }
                        if (!(iterable instanceof WalkableValue)) {
                            addToNotEvaluated(loop);
                            throw new EvaluationAbortException(false,
                                    String.format("Iterable %s not walkable in loop %s", iterable, loop));
                        }
                        for (Pair<?, Value> pair : ((WalkableValue<?>) iterable).getValues()) {
                            scope.push();
                            scope.put(loop.getIter().getName(), pair.second());
                            loop.getBody().forEach(s -> s.accept(this));
                            scope.pop();
                        }
                    }

                    @Override
                    public void visit(AssignmentStatement assignment) {
                        if (isNotEvaluated(assignment)) {
                            return;
                        }
                        try {
                            if (assignment.isCause()) {
                                scope.put(CAUSE_NAME, evaluatePacketCall(scope,
                                        (PacketCall) assignment.getExpression()));
                            } else {
                                var value = evaluate(scope, assignment.getExpression());
                                if (value == null) {
                                    addToNotEvaluated(assignment);
                                    return;
                                }
                                scope.put(assignment.getVariable().getName(), value);
                            }
                        } catch (AssertionError | Exception e) {
                            addToNotEvaluated(assignment);
                            throw new EvaluationAbortException(
                                    e instanceof EvaluationAbortException && ((EvaluationAbortException) e).discard,
                                    String.format("evaluation of assignment %s failed", assignment), e);
                        }
                    }

                    @Override
                    public void visit(Body body) {
                        if (isNotEvaluated(body)) {
                            return;
                        }
                        pushNotEvaluatedScope();
                        try {
                            for (int i = 0; i < body.getSubStatements().size(); i++) {
                                var s = body.getSubStatements().get(i);
                                try {
                                    s.accept(this);
                                } catch (EvaluationAbortException e) {
                                    errorConsumer.accept(e);
                                    addToNotEvaluated(s);
                                    if (e.discard) {
                                        for (int j = i + 1; j < body.getSubStatements().size(); j++) {
                                            addToNotEvaluated(body.getSubStatements().get(j));
                                        }
                                        throw e;
                                    }
                                }
                            }
                        } finally {
                            popNotEvaluatedScope();
                        }
                    }

                    public void visit(MapCallStatement mapCall) {
                        if (isNotEvaluated(mapCall)) {
                            return;
                        }
                        Value iterable;
                        try {
                            iterable = evaluate(scope, mapCall.getIterable());
                        } catch (AssertionError | Exception e) {
                            addToNotEvaluated(mapCall);
                            throw new EvaluationAbortException(
                                    e instanceof EvaluationAbortException && ((EvaluationAbortException) e).discard,
                                    "map header evaluation failed", e);
                        }
                        if (!(iterable instanceof WalkableValue)) {
                            addToNotEvaluated(mapCall);
                            throw new EvaluationAbortException(false,
                                    String.format("Iterable %s not walkable in map %s", iterable, mapCall));
                        }
                        MapCallResult result;
                        scope.push();
                        try {
                            var vals = ((WalkableValue<?>) iterable).getValues().stream()
                                    .map(p -> {
                                        var firstArgument = mapCall.getArguments().get(0);
                                        if (firstArgument.getPath().size() == 0) { // a basic map
                                            try {
                                                scope.push();
                                                scope.put(mapCall.getIter().getName(), p.second);
                                                return new MapCallResultEntry(Map.of("", evaluate(scope,
                                                        firstArgument.getAccessor())));
                                            } finally {
                                                scope.pop();
                                            }
                                        }
                                        return new MapCallResultEntry(mapCall.getArguments().stream().map(arg -> {
                                            try {
                                                scope.push();
                                                scope.put(mapCall.getIter().getName(), p.second);
                                                return Map.entry((String) arg.getPath().get(0), evaluate(scope,
                                                        arg.getAccessor()));
                                            } finally {
                                                scope.pop();
                                            }
                                        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)));
                                    })
                                    .collect(Collectors.toList());
                            result = new MapCallResult(vals);
                        } catch (AssertionError | Exception e) {
                            e.printStackTrace();
                            addToNotEvaluated(mapCall);
                            throw new EvaluationAbortException(
                                    e instanceof EvaluationAbortException && ((EvaluationAbortException) e).discard,
                                    String.format("evaluation of assignment %s failed", mapCall), e);
                        } finally {
                            scope.pop();
                        }
                        scope.put(mapCall.getVariable().getName(), result);
                    }

                    @Override
                    public void visit(SwitchStatement switchStatement) {
                        if (isNotEvaluated(switchStatement)) {
                            return;
                        }
                        Value expression;
                        try {
                            expression = evaluate(scope, switchStatement.getExpression());
                        } catch (AssertionError | Exception e) {
                            addToNotEvaluated(switchStatement);
                            throw new EvaluationAbortException(
                                    e instanceof EvaluationAbortException && ((EvaluationAbortException) e).discard,
                                    "switch expression evaluation failed", e);
                        }
                        for (CaseStatement aCase : switchStatement.getCases()) {
                            try {
                                if (aCase.getExpression() == null || evaluate(scope, aCase.getExpression()).equals(expression)) {
                                    aCase.getBody().accept(this);
                                    return;
                                }
                            } catch (AssertionError | Exception e) {
                                addToNotEvaluated(aCase);
                                throw new EvaluationAbortException(
                                        e instanceof EvaluationAbortException &&
                                                ((EvaluationAbortException) e).discard,
                                        "switch case expression evaluation failed", e);
                            }
                        }
                    }

                    @Override
                    public void visit(Recursion recursion) {
                        visit(recursion, null, null);
                    }

                    private void visit(Recursion recursion, @Nullable List<CallProperty> newProperties, @Nullable Identifier headerAssignmentVar) {
                        if (isNotEvaluated(recursion)) {
                            return;
                        }
                        scope.push();
                        Reply reply;
                        try {
                            var request = recursion.getRequest();
                            if (newProperties != null) {
                                request = request.withProperties(newProperties);
                            }
                            reply = (Reply) evaluate(scope, request);
                            scope.put(recursion.getRequestVariable().getName(), reply.asCombined());
                            if (headerAssignmentVar != null && scope.hasParent()) {
                                scope.getParent().put(headerAssignmentVar.getName(), reply.asCombined());
                            }
                        } catch (AssertionError | Exception e) {
                            addToNotEvaluated(recursion);
                            scope.pop();
                            throw new EvaluationAbortException(
                                    e instanceof EvaluationAbortException && ((EvaluationAbortException) e).discard,
                                    "recursive header evaluation failed", e);
                        }
                        if (recursiveEvaluations.getOrDefault(recursion, 0) >= recursion.getMaxNumberOfCalls()) {
                            scope.pop();
                            addToNotEvaluated(recursion);
                            throw new EvaluationAbortException(false,
                                    String.format("Recursion depth exceeded in %s", recursion));
                        }
                        recursiveEvaluations.put(recursion, recursiveEvaluations.getOrDefault(recursion, 0) + 1);
                        if (reply.hasNullReference()) {
                            scope.pop();
                            return; // 0 indicates the termination of the recursion (a 0 reference is usually invalid)
                        }
                        pushNotEvaluatedScope();
                        try {
                            recursion.getBody().accept(this);
                        } catch(EvaluationAbortException ex){
                            if(ex.discard){
                                throw ex;
                            }
                            LOG.error("Evaluation of recursion body failed", ex);
                        } finally {
                            popNotEvaluatedScope();
                        }
                        scope.pop();
                    }

                    @Override
                    public void visit(RecRequestCall recCall) {
                        if (isNotEvaluated(recCall)) {
                            return;
                        }
                        var recursion = (Recursion) recCall.getName().getSource();
                        visit(recursion, recCall.getArguments(), recCall.getVariable());
                    }
                });
        return p(scope, notEvaluated);
    }

    public Value evaluate(Expression expression) {
        return evaluate(new Scopes<>(), expression);
    }

    public @Nullable Value evaluate(Scopes<Value> scope, Expression expression) {
        return expression.accept(
                new ReturningExpressionVisitor<>() {
                    @Override
                    public Value visit(FunctionCall functionCall) {
                        var function = functions.getFunction(functionCall.getFunctionName());
                        var args = function.evaluateArguments(scope, functionCall.getArguments(),
                                Evaluator.this, this);
                        return function.evaluate(vm, args);
                    }

                    @Override
                    public Value visit(RequestCall requestCall) {
                        var value = functions.processRequest(requestCall, (Request<?>) evaluatePacketCall(scope, requestCall));
                        if (value.isEmpty()) {
                            return null;
                        }
                        if (value.get() instanceof Reply && ((Reply) value.get()).hasNullReference()) {
                            throw new EvaluationAbortException(false,
                                    "null reference in reply " + value + " for " + requestCall);
                        }
                        return value.get();
                    }

                    @Override
                    public Value visit(EventsCall eventsCall) {
                        return evaluatePacketCall(scope, eventsCall);
                    }

                    @Override
                    public Value visit(IntegerLiteral integer) {
                        return wrap(integer.get());
                    }

                    @Override
                    public Value visit(StringLiteral string) {
                        return wrap(string.get());
                    }

                    @Override
                    public Value visit(Identifier name) {
                        return scope.get(name.getName());
                    }

                    @Override
                    public Value visit(CallProperty property) {
                        throw new UnsupportedOperationException("CallProperty not supported");
                    }
                });
    }

    @SuppressWarnings("unchecked")
    public AbstractParsedPacket evaluatePacketCall(Scopes<Value> scope, PacketCall packetCall) {
        Stream<TaggedValue<?>> values =
                evaluateCallProperties(scope, packetCall.getProperties());
        var name = packetCall instanceof RequestCall ?
                String.format("jdwp.%sCmds$%sRequest", packetCall.getCommandSet(),
                        packetCall.getCommand()) :
                "jdwp.EventCmds$Events";
        try {
            return AbstractParsedPacket.createForTagged(
                    DEFAULT_ID, (Class<AbstractParsedPacket>) Class.forName(name), values);
        } catch (ClassNotFoundException e) {
            throw new PacketError("Unknown class", e);
        } catch (AssertionError e) {
            throw new EvaluationAbortException(false,
                    String.format("Cannot evaluate packet call %s (name = %s)", packetCall, name), e);
        }
    }

    @NotNull
    Stream<TaggedValue<?>> evaluateCallProperties(Scopes<Value> scope, List<CallProperty> properties) {
        return properties.stream()
                .flatMap(
                        p -> {
                            var value = evaluate(scope, p.getAccessor());
                            if (value instanceof MapCallResult) {
                                return ((MapCallResult) value).toTaggedValues(p.getPath()).stream();
                            }
                            return Stream.of(new TaggedValue<>(p.getPath(), value));
                        });
    }

    public AbstractParsedPacket evaluatePacketCall(PacketCall packetCall) {
        return evaluatePacketCall(new Scopes<>(), packetCall);
    }
}
