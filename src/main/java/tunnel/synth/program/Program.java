package tunnel.synth.program;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import tunnel.synth.program.AST.Parser;
import tunnel.synth.program.AST.Statement;
import tunnel.synth.program.AST.StatementVisitor;
import tunnel.synth.program.AST.Body;

import java.util.List;

/**
 * Represents programs of the untyped debugging language.
 * The language consists of function calls and for loops.
 * It supports literals for the following types
 * <li>
 *     <ul>integer: 64 bit integer to represent all numbers and references</ul>
 *     <ul>string</ul>
 * </li>
 * All other types require the usage of functions. The syntax is s-expression based:
 * <code>
 *     program := statement*
 *     statement := function_call | loop
 *     argument := string | "string" | integer
 *     function_call := ("=" $return $function_name argument*)
 *     loop := ("for" $iter $iterable statement+)
 * </code>
 * Variables are alphanumerical strings.
 * There are currently three implemented functions:
 * <li>
 *     <ul>get(value, path...): obtain the sub module with the given path</ul>
 *     <ul>request("command set", "command", args...): place a request</ul>
 *     <ul>const(literal): returns the value of the literal</ul>
 * </li>
 */
@EqualsAndHashCode(callSuper = false)
@Getter
public class Program {

    public static final String INDENT = "  ";

    private final Body body;

    public Program(Body body) {
        this.body = body;
    }

    public Program(List<Statement> body) {
        this(new Body(body));
    }

    /** run on each sub statement */
    public void accept(StatementVisitor visitor) {
        body.forEach(s -> s.accept(visitor));
    }

    public String toPrettyString() {
        return String.format("(\n%s)", body.toPrettyString());
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
