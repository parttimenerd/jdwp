package tunnel.synth.program;

import tunnel.synth.program.AST.*;

public abstract class Visitors {

    interface ASTVisitor extends StatementVisitor, ExpressionVisitor {}

    interface RecursiveASTVisitor extends RecursiveStatementVisitor, RecursiveExpressionVisitor {}

    interface StatementVisitor {

        default void visit(Statement statement) {}

        default void visit(AssignmentStatement assignment) {
            visit((Statement) assignment);
        }

        default void visit(Loop loop) {
            visit((Statement) loop);
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

        default R visit(RequestCall request) {
            return visit((Expression) request);
        }

        default R visit(RequestCallProperty property) {
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

        default void visit(RequestCall request) {
            visit((Expression) request);
        }

        default void visit(RequestCallProperty property) {
            visit((Expression) property);
        }
    }

    interface RecursiveExpressionVisitor extends ExpressionVisitor {
        default void visit(Expression call) {
            call.getSubExpressions().forEach(s -> s.accept(this));
        }
    }
}
