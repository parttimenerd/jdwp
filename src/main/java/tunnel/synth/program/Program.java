package tunnel.synth.program;

import lombok.EqualsAndHashCode;

import java.util.List;
import java.util.stream.Collectors;

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
 * Variables are alphanumerical strings and are neither scoped nor strongly typed.
 */
@EqualsAndHashCode(callSuper = false)
public class Program extends AST {

    public static final String INDENT = "  ";

    private final List<Statement> body;

    public Program(List<Statement> body) {
        this.body = body;
    }

    @Override
    public String toString() {
        return String.format("(%s)", body.stream().map(Statement::toString).collect(Collectors.joining("\n")));
    }

    public String toPrettyString() {
        return String.format("(\n%s)",
                body.stream().map(s -> s.toPrettyString(INDENT)).collect(Collectors.joining("\n")));
    }

    public static tunnel.synth.program.Program parse(String string) {
        return new Parser(string).parseProgram();
    }

    public void accept(StatementVisitor visitor) {
        body.forEach(s -> s.accept(visitor));
    }

}
