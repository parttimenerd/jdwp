package tunnel.synth.program;

import jdwp.JDWP;
import jdwp.JDWP.Metadata;
import jdwp.exception.TunnelException.UnsupportedOperationException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.ProgramHashes;
import tunnel.synth.program.AST.CompoundStatement;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.Visitors.RecursiveStatementVisitor;
import tunnel.synth.program.Visitors.ReturningStatementVisitor;
import tunnel.synth.program.Visitors.StatementVisitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static tunnel.synth.Synthesizer.CAUSE_NAME;

/**
 * Represents programs of the untyped debugging language. The language consists of function calls
 * and for loops
 */
@EqualsAndHashCode(callSuper = false)
@Getter
public class Program extends Statement implements CompoundStatement<Program> {

    public static final String INDENT = "  ";

    private final @Nullable Identifier causeIdent;
    private final @Nullable PacketCall cause;
    private final Body body;

    public Program(@Nullable Identifier causeIdent, @Nullable PacketCall cause, Body body) {
        this.causeIdent = causeIdent;
        this.cause = cause;
        this.body = new Body(Collections.unmodifiableList(body.getSubStatements()));
        checkInvariant();
        ProgramHashes.setInProgram(this);
    }

    public Program(@Nullable Identifier causeIdent, @Nullable PacketCall cause, List<Statement> body) {
        this(causeIdent, cause, new Body(body));
    }

    public Program(Body body) {
        this(null, null, body);
    }

    public Program(List<Statement> body) {
        this(null, null, body);
    }

    public Program(Statement... body) {
        this(new Body(List.of(body)));
    }

    private void checkInvariant() {
        if ((cause == null) != (causeIdent == null)) {
            throw new AssertionError("cause and causeIdent must have the same !=null property");
        }
        checkEveryIdentifierHasASingleSource();
        if (cause != null && cause instanceof RequestCall && body.size() > 0) {
            var firstStatement = body.get(0);
            if (firstStatement instanceof AssignmentStatement) {
                var firstAssignment = (AssignmentStatement)firstStatement;
                if (firstAssignment.getExpression().equals(cause) ||
                        (firstAssignment.getExpression() instanceof FunctionCall &&
                                ((FunctionCall) firstAssignment.getExpression()).getFunctionName().equals(Functions.OBJECT))) {
                    return;
                }
            }
            throw new AssertionError(String.format("Program has a cause but the first statement is not an assignment " +
                    "of the cause: %s (or an object call)", this.toPrettyString()));
        }
    }

    /**
     * run on each sub statement
     */
    public void accept(StatementVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public <R> R accept(ReturningStatementVisitor<R> visitor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toPrettyString(String indent, String innerIndent) {
        return String.format(
                "%s(%s\n%s)", indent, cause != null ? String.format("(= cause %s)", cause) : "",
                body.toPrettyString(indent + innerIndent, innerIndent));
    }

    @Override
    public String toString() {
        return String.format("(%s%s%s)", cause != null ? String.format("(= cause %s)", cause) : "",
                (hasCause() && body.size() > 0) ? " " : "",
                body.toString());
    }

    public static Program parse(String string) {
        try {
            return new Parser(string).parseProgram();
        } catch (SyntaxError e) {
            e.setProgram(string);
            throw e;
        }
    }

    public Program merge(Program other) {
        return new Program(body.merge(other.body));
    }

    public Program overlap(Program other) {
        return new Program(body.overlap(other.body));
    }

    public boolean hasCause() {
        return cause != null;
    }

    /** cause or first assignment in body (equivalent for non-events) */
    public @Nullable AssignmentStatement getFirstCallAssignment() {
        return cause != null ? new AssignmentStatement(causeIdent, cause) : body.getFirstCallAssignment();
    }

    public int getNumberOfAssignments() {
        int[] count = new int[]{ 0 };
        body.accept(new RecursiveStatementVisitor() {
            @Override
            public void visit(AssignmentStatement assignment) {
                count[0] += 1;
            }
        });
        return count[0];
    }

    public @Nullable AssignmentStatement getCauseStatement() {
        if (causeIdent != null && causeIdent.getSource() == null) {
            causeIdent.setSource(new AssignmentStatement(AST.ident(CAUSE_NAME), getCause()));
        }
        return hasCause() ? (AssignmentStatement) causeIdent.getSource() : null;
    }

    public int getNumberOfDistinctCalls() {
        return getNumberOfAssignments() + (hasCause() && getCause() instanceof EventsCall ? 1 : 0);
    }

    /** removes statements transitively */
    @Override
    public Program removeStatements(Set<Statement> statements) {
        // check whether the cause has to be removed
        if (cause != null && statements.stream().anyMatch(s -> s instanceof AssignmentStatement &&
                ((AssignmentStatement) s).getExpression() instanceof PacketCall &&
                ((AssignmentStatement)s).getExpression().equals(cause))) {
            return new Program(null, null, body.removeStatementsTransitively(statements));
        }
        return new Program(causeIdent, cause, body.removeStatementsTransitively(statements));
    }


    public Program removeStatementsIgnoreCause(Set<Statement> statements,
                                               List<Statement> prependStatements,
                                               boolean ignoreCauseStatement) {
        if (!(cause instanceof RequestCall)) {
            return new Program(causeIdent, cause, body.removeStatementsTransitively(statements));
        }
        // skip the cause request here, otherwise we would violate the invariant
        statements.removeIf(s -> s instanceof AssignmentStatement &&
                ((AssignmentStatement) s).getExpression() instanceof PacketCall &&
                (ignoreCauseStatement && ((AssignmentStatement)s).getExpression().equals(cause)));
        var newBody = body.removeStatementsTransitively(statements).getSubStatements();
        return new Program(causeIdent, cause,
                Stream.concat(prependStatements.stream(), newBody.stream()).collect(Collectors.toList()));
    }

    public Set<Statement> getDependentStatements(Set<Statement> statements) {
        return body.getDependentStatements(statements);
    }

    @Override
    public List<Statement> getSubStatements() {
        return body.getSubStatements();
    }

    public boolean isServerProgram() {
        return hasCause() && cause instanceof EventsCall;
    }

    public boolean isClientProgram() {
        return hasCause() && cause instanceof RequestCall;
    }

    public Program setCause(PacketCall packet) {
        assert cause == null || packet.getClass().equals(cause.getClass());
        Program copy = copy();
        if (copy.body.isEmpty() || copy.cause == null) {
            return new Program(copy.causeIdent, packet, copy.body);
        }

        var newBody = new Body(new ArrayList<>(copy.body.getSubStatements()));
        AssignmentStatement newCauseStatement = new AssignmentStatement(AST.ident(CAUSE_NAME), packet);
        if (copy.cause instanceof EventsCall) {
            newBody.replaceSource(getCauseStatement(), newCauseStatement);
            return new Program(copy.causeIdent, packet, newBody);
        }
        // we have to do more for request causes: we have to replace the first statement with the new cause
        var firstStatement = (AssignmentStatement) copy.body.get(0);
        var newFirstStatement = new AssignmentStatement(firstStatement.getVariable(), packet);
        newBody.set(0, newFirstStatement);
        newBody.replaceSource(firstStatement, newFirstStatement);
        newBody.replaceSource(getCauseStatement(), newCauseStatement);
        return new Program(copy.causeIdent, packet, newBody);
    }

    @Override
    public AST replaceIdentifiers(Function<Identifier, Identifier> identifierReplacer) {
        return new Program(cause == null ? null : identifierReplacer.apply(causeIdent),
                cause == null ? null : cause.replaceIdentifiersConv(identifierReplacer),
                body.replaceIdentifiersConv(identifierReplacer));
    }

    public List<Statement> collectBodyStatements() {
        List<Statement> statements = new ArrayList<>();
        body.accept(new StatementVisitor() {
            @Override
            public void visit(Statement statement) {
                statements.add(statement);
                statement.getSubStatements().forEach(s -> s.accept(this));
            }

            @Override
            public void visit(Body body) {
                body.getSubStatements().forEach(s -> s.accept(this));
            }
        });
        return statements;
    }

    public boolean isEmpty() {
        return getNumberOfAssignments() + (hasCause() ? 1 : 0) == 0;
    }

    public boolean hasEmptyBody() {
        return body.isEmpty();
    }

    public int getBodySize() {
        return body.size();
    }

    public @Nullable Metadata getCauseMetadata() {
        return cause == null ? null : JDWP.getMetadata(cause.getCommandSet(), cause.getCommand());
    }
}
