package tunnel.synth.program;

import jdwp.AbstractParsedPacket;
import jdwp.PacketError;
import jdwp.Request;
import jdwp.Value;
import jdwp.Value.BasicValue;
import jdwp.Value.TaggedBasicValue;
import jdwp.Value.WalkableValue;
import jdwp.util.Pair;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import tunnel.synth.program.AST.*;
import tunnel.synth.program.Visitors.ReturningExpressionVisitor;
import tunnel.synth.program.Visitors.StatementVisitor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static tunnel.synth.Synthesizer.CAUSE_NAME;

public class Evaluator {

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

    private final Functions functions;
    private final Consumer<EvaluationAbortException> errorConsumer;

    public Evaluator(Functions functions) {
        this(functions, e -> {
        });
    }

    public Evaluator(Functions functions, Consumer<EvaluationAbortException> errorConsumer) {
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
    public Pair<Scopes<Value>, Set<Statement>> evaluate(Scopes<Value> scope, Body body) {
        Set<Statement> notEvaluated = new HashSet<>();
        body.accept(
                new StatementVisitor() {

                    private void addToNotEvaluated(Statement statement) {
                        notEvaluated.addAll(body.getDependentStatementsAndAnchor(statement));
                    }

                    @Override
                    public void visit(Loop loop) {
                        if (notEvaluated.contains(loop)) {
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
                        if (notEvaluated.contains(assignment)) {
                            return;
                        }
                        try {
                            if (assignment.isCause()) {
                                scope.put(CAUSE_NAME, evaluatePacketCall(scope,
                                        (PacketCall) assignment.getExpression()));
                            } else {
                                scope.put(assignment.getVariable().getName(), evaluate(scope,
                                        assignment.getExpression()));
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
                        if (notEvaluated.contains(body)) {
                            return;
                        }
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
                    }
                });
        return p(scope, notEvaluated);
    }

    public Value evaluate(Expression expression) {
        return evaluate(new Scopes<>(), expression);
    }

    public Value evaluate(Scopes<Value> scope, Expression expression) {
        return expression.accept(
                new ReturningExpressionVisitor<>() {
                    @Override
                    public Value visit(FunctionCall functionCall) {
                        return functions
                                .getFunction(functionCall.getFunctionName())
                                .evaluate(
                                        functionCall.getArguments().stream()
                                                .map(a -> a.accept(this))
                                                .collect(Collectors.toList()));
                    }

                    @Override
                    public Value visit(RequestCall requestCall) {
                        return functions.processRequest((Request<?>) evaluatePacketCall(scope, requestCall));
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
                });
    }

    @SuppressWarnings("unchecked")
    public AbstractParsedPacket evaluatePacketCall(Scopes<Value> scope, PacketCall packetCall) {
        Stream<TaggedBasicValue<?>> values =
                evaluateValues(scope, packetCall);
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
    private Stream<TaggedBasicValue<?>> evaluateValues(Scopes<Value> scope, PacketCall packetCall) {
        return packetCall.getProperties().stream()
                .map(
                        p -> {
                            var value = evaluate(scope, p.getAccessor());
                            if (!(value instanceof BasicValue)) {
                                throw new AssertionError();
                            }
                            return new TaggedBasicValue<>(p.getPath(), (BasicValue) value);
                        });
    }

    public AbstractParsedPacket evaluatePacketCall(PacketCall packetCall) {
        return evaluatePacketCall(new Scopes<>(), packetCall);
    }
}
