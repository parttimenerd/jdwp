package tunnel.synth.program;

import jdwp.AbstractParsedPacket;
import jdwp.PacketError;
import jdwp.Request;
import jdwp.Value;
import jdwp.Value.BasicValue;
import jdwp.Value.TaggedBasicValue;
import jdwp.Value.WalkableValue;
import jdwp.util.Pair;
import org.jetbrains.annotations.NotNull;
import tunnel.synth.program.AST.*;
import tunnel.synth.program.Visitors.ReturningExpressionVisitor;
import tunnel.synth.program.Visitors.StatementVisitor;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;
import static tunnel.synth.Synthesizer.CAUSE_NAME;

public class Evaluator {

    /** default id for requests */
    public static final int DEFAULT_ID = 0;

    private final Functions functions;

    public Evaluator(Functions functions) {
        this.functions = functions;
    }

    public Scopes<Value> evaluate(Program program) {
        var scope = new Scopes<Value>();
        if (program.hasCause()) {
            evaluate(scope, new Body(List.of(program.getCauseStatement())));
        }
        return evaluate(scope, program.getBody());
    }

    public Scopes<Value> evaluate(Scopes<Value> scope, Body body) {
        body.accept(
                new StatementVisitor() {

                    @Override
                    public void visit(Loop loop) {
                        Value iterable = evaluate(scope, loop.getIterable());
                        if (!(iterable instanceof WalkableValue)) {
                            throw new AssertionError(String.format("Iterable %s not walkable in loop %s", iterable, loop));
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
                        if (assignment.isCause()) {
                            scope.put(CAUSE_NAME, evaluatePacketCall(scope, (PacketCall) assignment.getExpression()));
                        } else {
                            scope.put(assignment.getVariable().getName(), evaluate(scope, assignment.getExpression()));
                        }
                    }

                    @Override
                    public void visit(Body body){
                        body.getSubStatements().forEach(s -> s.accept(this));
                    }
                });
        return scope;
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
