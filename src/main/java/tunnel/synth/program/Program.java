package tunnel.synth.program;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.Visitors.StatementVisitor;

import java.util.List;

/**
 * Represents programs of the untyped debugging language. The language consists of function calls
 * and for loops
 */
@EqualsAndHashCode(callSuper = false)
@Getter
public class Program extends Statement {

    public static final String INDENT = "  ";

    private final Body body;

    public Program(Body body) {
        this.body = body;
    }

    public Program(List<Statement> body) {
        this(new Body(body));
    }

    public Program(Statement... body) {
        this(new Body(List.of(body)));
    }

    /** run on each sub statement */
    public void accept(StatementVisitor visitor) {
        visitor.visit(this);
    }

    @Override
    public String toPrettyString(String indent, String innerIndent) {
        return String.format(
                "%s(\n%s)", indent, body.toPrettyString(indent + innerIndent, innerIndent));
    }

    @Override
    public String toString() {
        return String.format("(%s)", body.toString());
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

}
