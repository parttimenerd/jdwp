package tunnel.synth.program;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.text.StringEscapeUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class AST {

    public static StringLiteral literal(String string) {
        return new StringLiteral(string);
    }

    public static IntegerLiteral literal(long integer) {
        return new IntegerLiteral(integer);
    }

    public static Identifier ident(String identifier) {
        return new Identifier(identifier);
    }

    public interface StatementVisitor {
        void visit(FunctionCall functionCall);

        void visit(Loop loop);
    }

    public interface PrimitiveVisitor<R> {
        default R visit(StringLiteral string) {
            return visit((Literal) string);
        }

        default R visit(IntegerLiteral integer) {
            return visit((Literal) integer);
        }

        default R visit(Literal literal) { return null; }

        R visit(Identifier name);
    }

    public static abstract class Primitive extends AST {
        public abstract <R> R accept(PrimitiveVisitor<R> visitor);
    }

    @EqualsAndHashCode(callSuper = false)
    public static abstract class Literal<T> extends Primitive {

        protected final T value;

        protected Literal(T value) {
            this.value = value;
        }

        public T get() {
            return value;
        }
    }

    public static class StringLiteral extends Literal<String> {
        public StringLiteral(String string) {
            super(string);
        }

        @Override
        public <R> R accept(PrimitiveVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public String toString() {
            return "\"" + StringEscapeUtils.escapeJava(value) + "\"";
        }
    }

    public static class IntegerLiteral extends Literal<Long> {
        public IntegerLiteral(Long integer) {
            super(integer);
        }

        @Override
        public <R> R accept(PrimitiveVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }


    @EqualsAndHashCode(callSuper = false)
    public static class Identifier extends Primitive {
        private final String name;

        public Identifier(String name) {
            this.name = name;
        }

        @Override
        public <R> R accept(PrimitiveVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public String toString() {
            return name;
        }

        public String getName() {
            return name;
        }
    }

    public static abstract class Statement extends AST {

        public abstract void accept(StatementVisitor visitor);

        @Override
        public String toString() {
            return toPrettyString("", "");
        }

        public String toPrettyString() {
            return toPrettyString("");
        }

        public String toPrettyString(String indent) {
            return toPrettyString(indent, Program.INDENT);
        }

        public abstract String toPrettyString(String indent, String innerIndent);
    }

    @Getter
    @EqualsAndHashCode(callSuper = false)
    public static class FunctionCall extends Statement {

        private final Identifier returnVariable;
        private final Identifier function;
        private final List<Primitive> arguments;

        public FunctionCall(Identifier returnVariable, Identifier function, List<Primitive> arguments) {
            this.returnVariable = returnVariable;
            this.function = function;
            this.arguments = arguments;
        }

        @Override
        public void accept(StatementVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public String toPrettyString(String indent, String innerIndent) {
            return String.format("%s(= %s %s%s%s)", indent, returnVariable, function,
                    arguments.size() > 0 ? " " : "", arguments.stream().map(Object::toString).collect(Collectors.joining(" ")));
        }
    }

    @Getter
    @EqualsAndHashCode(callSuper = false)
    public static class Loop extends Statement {
        private final Identifier iter;
        private final Identifier iterable;
        private final List<Statement> body;

        public Loop(Identifier iter, Identifier iterable, List<Statement> body) {
            this.iter = iter;
            this.iterable = iterable;
            this.body = body;
        }

        @Override
        public void accept(StatementVisitor visitor) {
            visitor.visit(this);
        }

        public String toPrettyString(String indent, String innerIndent) {
            String subIndent = innerIndent + indent;
            return String.format("%s(for %s %s %s%s)", indent, iter, iterable, innerIndent.isEmpty() ? "" : "\n",
                    body.stream().map(s -> s.toPrettyString(subIndent, innerIndent)).collect(Collectors.joining("\n")));
        }
    }

    public static class SyntaxError extends RuntimeException {
        public SyntaxError(int line, int column, String msg) {
            super(String.format("Error at %d.%d: %s", line, column, msg));
        }
    }

    static class Parser {
        private final InputStream stream;
        private int current = 0;
        private int line = 1;
        private int column = 1;

        @SneakyThrows
        private Parser(InputStream stream) {
            this.stream = stream;
            this.current = stream.read();
        }

        Parser(String input) {
            this(new ByteArrayInputStream(input.getBytes()));
        }

        @SneakyThrows
        private int next() {
            current = stream.read();
            if (current == '\n') {
                column = 0;
                line++;
            } else {
                column++;
            }
            return current;
        }

        private int current() {
            return current;
        }

        private boolean isEOF() {
            return current == -1;
        }

        private char currentChar() {
            return (char) current;
        }

        private void expect(char expected) {
            if (current != expected) {
                throw new SyntaxError(line, column,
                        String.format("Expected '%s' but got '%s'", expected, Character.toString(current)));
            }
            next();
        }

        private void skipWhitespace() {
            while (!isEOF() && Character.isWhitespace(current)) {
                next();
            }
        }

        Program parseProgram() {
            expect('(');
            skipWhitespace();
            var program = new Program(parseBlock());
            skipWhitespace();
            expect(')');
            return program;
        }

        List<Statement> parseBlock() {
            List<Statement> statements = new ArrayList<>();
            while (current != ')') {
                skipWhitespace();
                statements.add(parseStatement());
            }
            return statements;
        }

        Statement parseStatement() {
            expect('(');
            Statement ret;
            switch (current) {
                case '=':
                    ret = parseFunctionCall();
                    break;
                case 'f':
                    ret = parseLoop();
                    break;
                default:
                    throw new SyntaxError(line, column, String.format("Unexpected %s", (char) current));
            }
            expect(')');
            return ret;
        }

        FunctionCall parseFunctionCall() {
            expect('=');
            expect(' ');
            skipWhitespace();
            var ret = parseIdentifier();
            skipWhitespace();
            var function = parseIdentifier();
            skipWhitespace();
            List<Primitive> arguments = new ArrayList<>();
            while (current != ')') {
                arguments.add(parsePrimitive());
                skipWhitespace();
            }
            return new FunctionCall(ret, function, arguments);
        }

        Loop parseLoop() {
            expect('f');
            expect('o');
            expect('r');
            expect(' ');
            skipWhitespace();
            var iter = parseIdentifier();
            skipWhitespace();
            var iterable = parseIdentifier();
            skipWhitespace();
            var body = parseBlock();
            return new Loop(iter, iterable, body);
        }

        Identifier parseIdentifier() {
            StringBuffer buf = new StringBuffer();
            while (!isEOF() && Character.isJavaIdentifierPart(current)) {
                buf.append(currentChar());
                next();
            }
            return new Identifier(buf.toString());
        }

        IntegerLiteral parseInteger() {
            StringBuffer buf = new StringBuffer();
            while (!isEOF() && Character.isDigit(current)) {
                buf.append(currentChar());
                next();
            }
            return new IntegerLiteral(Long.parseLong(buf.toString()));
        }

        StringLiteral parseString() {
            StringBuffer buf = new StringBuffer();
            expect('"');
            while (!isEOF() && current != '"') {
                buf.append(currentChar());
                next();
                if (current == '\\') {
                    buf.append(currentChar());
                    next();
                }
            }
            expect('"');
            return new StringLiteral(StringEscapeUtils.unescapeJava(buf.toString()));
        }

        Literal parseLiteral() {
            if (Character.isDigit(current)) {
                return parseInteger();
            }
            return parseString();
        }

        Primitive parsePrimitive() {
            if (Character.isAlphabetic(current)) {
                return parseIdentifier();
            }
            return parseLiteral();
        }
    }

}
