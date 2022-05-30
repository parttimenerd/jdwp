package tunnel.synth.program;

import jdwp.Value;
import jdwp.Value.BasicValue;
import jdwp.Value.TaggedBasicValue;
import jdwp.Value.WalkableValue;
import jdwp.util.Pair;
import tunnel.synth.program.AST.*;
import tunnel.synth.program.Visitors.ReturningExpressionVisitor;
import tunnel.synth.program.Visitors.StatementVisitor;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;

public class Evaluator {

    /** default id for requests */
    public static final int DEFAULT_ID = 0;

    private final Functions functions;

    public Evaluator(Functions functions) {
        this.functions = functions;
    }

    public Scopes<Value> evaluate(Program program) {
        return evaluate(new Scopes<>(), program.getBody());
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
                        var commandSet = requestCall.getCommandSet();
                        var command = requestCall.getCommand();
                        Stream<TaggedBasicValue<?>> values =
                                requestCall.getProperties().stream()
                                        .map(
                                                p -> {
                                                    var value = p.getAccessor().accept(this);
                                                    if (!(value instanceof BasicValue)) {
                                                        throw new AssertionError();
                                                    }
                                                    return new TaggedBasicValue<>(p.getPath(), (BasicValue) value);
                                                });
                        return functions.processRequest(commandSet, command, values);
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
}
