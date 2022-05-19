package tunnel.synth.program;

import jdwp.Value;
import jdwp.Value.WalkableValue;
import jdwp.util.Pair;
import tunnel.synth.program.AST.*;

import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.wrap;

public class Evaluator {

    private final Functions functions;

    public Evaluator(Functions functions) {
        this.functions = functions;
    }

    public Scope evaluate(Program program) {
        return evaluate(new Scope(), program);
    }

    public Scope evaluate(Scope scope, Program program) {
        program.accept(new StatementVisitor() {
            @Override
            public void visit(FunctionCall functionCall) {
                Value ret = evaluateFunction(scope, functionCall);
                scope.set(functionCall.getReturnVariable().getName(), ret);
            }

            @Override
            public void visit(Loop loop) {
                Value iterable = scope.get(loop.getIterable().getName());
                if (!(iterable instanceof WalkableValue)) {
                    throw new AssertionError();
                }
                for (Pair<?, Value> pair : ((WalkableValue<?>) iterable).getValues()) {
                    scope.push();
                    scope.set(loop.getIter().getName(), pair.second());
                    loop.getBody().forEach(s -> s.accept(this));
                    scope.pop();
                }
            }
        });
        return scope;
    }

    public Value evaluateFunction(Scope scope, FunctionCallLike functionCall) {
        return functions.getFunction(functionCall.getFunction().getName())
                .evaluate(functionCall.getArguments().stream().map(p ->
                        p.accept(new PrimitiveVisitor<Value>() {

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
                        })).collect(Collectors.toList()));
    }
}
