package tunnel.synth.program;

import tunnel.synth.program.AST.*;

public interface Visitors {

    interface ASTVisitor extends StatementVisitor, ExpressionVisitor {}

    interface ReturningASTVisitor<R> extends ReturningStatementVisitor<R>, ReturningExpressionVisitor<R> {}

    interface RecursiveASTVisitor extends RecursiveStatementVisitor, RecursiveExpressionVisitor {}

    interface StatementVisitor {

        default void visit(Statement statement) {
        }

        default void visit(AssignmentStatement assignment) {
            visit((Statement) assignment);
        }

        default void visit(Loop loop) {
            visit((Statement) loop);
        }

        default void visit(Body body) {
            visit((Statement) body);
        }
    }

    interface ReturningStatementVisitor<R> {

        default R visit(Statement statement) {
            throw new AssertionError();
        }

        default R visit(AssignmentStatement assignment) {
            return visit((Statement) assignment);
        }

        default R visit(Loop loop) {
            return visit((Statement) loop);
        }

        default R visit(Body body) {
            return visit((Statement) body);
        }
    }

    interface RecursiveStatementVisitor extends StatementVisitor {
        default void visit(Statement statement) {
            statement.getSubStatements().forEach(s -> s.accept(this));
            if (this instanceof ExpressionVisitor) {
                statement.getSubExpressions().forEach(s -> s.accept((ExpressionVisitor) this));
            }
        }
    }

    interface ReturningPrimitiveVisitor<R> {

        default R visit(Primitive primitive) {
            return null;
        }

        default R visit(Literal<?> literal) {
            return visit((Primitive) literal);
        }

        default R visit(StringLiteral string) {
            return visit((Literal<?>) string);
        }

        default R visit(IntegerLiteral integer) {
            return visit((Literal<?>) integer);
        }

        default R visit(Identifier name) {
            return visit((Primitive) name);
        }
    }

    interface PrimitiveVisitor {

        default void visit(Primitive primitive) {}

        default void visit(Literal<?> literal) {
            visit((Primitive) literal);
        }

        default void visit(StringLiteral string) {
            visit((Literal<?>) string);
        }

        default void visit(IntegerLiteral integer) {
            visit((Literal<?>) integer);
        }

        default void visit(Identifier name) {
            visit((Primitive) name);
        }
    }

    interface ReturningExpressionVisitor<R> extends ReturningPrimitiveVisitor<R> {

        default R visit(Expression expression) {
            return null;
        }

        default R visit(Primitive primitive) {
            return visit((Expression) primitive);
        }

        default R visit(FunctionCall call) {
            return visit((Expression) call);
        }

        default R visit(PacketCall packet) {
            return visit((Expression) packet);
        }

        default R visit(RequestCall request) {
            return visit((PacketCall) request);
        }

        default R visit(EventsCall events) {
            return visit((PacketCall) events);
        }

        default R visit(CallProperty property) {
            return visit((Expression) property);
        }
    }

    interface ExpressionVisitor extends PrimitiveVisitor {

        default void visit(Expression expression) {}

        default void visit(Primitive primitive) {
            visit((Expression) primitive);
        }

        default void visit(FunctionCall call) {
            visit((Expression) call);
        }

        default void visit(PacketCall packet) {
            visit((Expression) packet);
        }

        default void visit(RequestCall request) {
            visit((PacketCall) request);
        }

        default void visit(EventsCall events) {
            visit((PacketCall) events);
        }

        default void visit(CallProperty property) {
            visit((Expression) property);
        }
    }

    interface RecursiveExpressionVisitor extends ExpressionVisitor {
        default void visit(Expression call) {
            call.getSubExpressions().forEach(s -> s.accept(this));
        }
    }
}
