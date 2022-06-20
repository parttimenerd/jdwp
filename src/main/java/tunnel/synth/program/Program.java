package tunnel.synth.program;

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

import static tunnel.synth.Synthesizer.CAUSE_NAME;

/**
 * Represents programs of the untyped debugging language. The language consists of function calls
 * and for loops
 */
@EqualsAndHashCode(callSuper = false)
@Getter
public class Program extends Statement implements CompoundStatement<Program> {

    public static final String INDENT = "  ";

    private final @Nullable PacketCall cause;
    private final Body body;

    public Program(@Nullable PacketCall cause, Body body) {
        this.cause = cause;
        this.body = new Body(Collections.unmodifiableList(body.getSubStatements()));
        checkInvariant();
        ProgramHashes.setInProgram(this);
    }

    public Program(PacketCall cause, List<Statement> body) {
        this(cause, new Body(body));
    }

    public Program(Body body) {
        this(null, body);
    }

    public Program(List<Statement> body) {
        this(null, body);
    }

    public Program(Statement... body) {
        this(new Body(List.of(body)));
    }

    private void checkInvariant() {
        if (cause != null && cause instanceof RequestCall && body.size() > 0 &&
                (!(body.get(0) instanceof AssignmentStatement) ||
                        !(((AssignmentStatement) body.get(0)).getExpression().equals(cause)))) {
            throw new AssertionError(String.format("Program has a cause but the first statement is not an assignment " +
                    "of the cause: %s", this.toPrettyString()));
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
        throw new AssertionError();
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
        return new Parser(string).parseProgram();
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
        return cause != null ? new AssignmentStatement(AST.ident(CAUSE_NAME), cause) : body.getFirstCallAssignment();
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
        return hasCause() ? new AssignmentStatement(AST.ident(CAUSE_NAME), getCause()) : null;
    }

    public int getNumberOfDistinctCalls() {
        return getNumberOfAssignments() + (hasCause() && getCause() instanceof EventsCall ? 1 : 0);
    }

    public Program removeStatements(Set<Statement> statements) {
        // check whether the cause has to be removed
        if (cause != null && statements.stream().anyMatch(s -> s instanceof AssignmentStatement &&
                ((AssignmentStatement) s).getExpression() instanceof PacketCall &&
                ((AssignmentStatement)s).getExpression().equals(cause))) {
            return new Program(null, body.removeStatements(statements));
        }
        return new Program(cause, body.removeStatements(statements));
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
        if (body.isEmpty() || cause == null) {
            return new Program(packet, body);
        }

        var newBody = new Body(new ArrayList<>(body.getSubStatements()));
        AssignmentStatement newCauseStatement = new AssignmentStatement(AST.ident(CAUSE_NAME), packet);
        if (cause instanceof EventsCall) {
            newBody.replaceSource(getCauseStatement(), newCauseStatement);
            return new Program(packet, newBody);
        }
        // we have to do more for request causes: we have to replace the first statement with the new cause
        var firstStatement = (AssignmentStatement) body.get(0);
        var newFirstStatement = new AssignmentStatement(firstStatement.getVariable(), packet);
        newBody.set(0, newFirstStatement);
        newBody.replaceSource(firstStatement, newFirstStatement);
        newBody.replaceSource(getCauseStatement(), newCauseStatement);
        return new Program(packet, newBody);
    }

    @Override
    public AST replaceIdentifiers(Function<Identifier, Identifier> identifierReplacer) {
        return new Program(cause == null ? null : cause.replaceIdentifiersConv(identifierReplacer),
                body.replaceIdentifiersConv(identifierReplacer));
    }
}
