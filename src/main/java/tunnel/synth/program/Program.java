package tunnel.synth.program;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.ProgramHashes;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.Visitors.RecursiveStatementVisitor;
import tunnel.synth.program.Visitors.ReturningStatementVisitor;
import tunnel.synth.program.Visitors.StatementVisitor;

import java.util.List;

import static tunnel.synth.Synthesizer.CAUSE_NAME;

/**
 * Represents programs of the untyped debugging language. The language consists of function calls
 * and for loops
 */
@EqualsAndHashCode(callSuper = false)
@Getter
public class Program extends Statement {

    public static final String INDENT = "  ";

    private final @Nullable PacketCall cause;
    private final Body body;
    private final ProgramHashes hashes;

    public Program(@Nullable PacketCall cause, Body body) {
        this.cause = cause;
        this.body = body;
        this.hashes = ProgramHashes.create(this);
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

    /** run on each sub statement */
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
        return new Program(body.merge(hashes, other.hashes, other.body));
    }

    public Program overlap(Program other) {
        return new Program(body.overlap(hashes, other.hashes, other.body));
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
}
