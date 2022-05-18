package tunnel.synth.program;

import jdwp.util.Pair;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.SneakyThrows;
import org.apache.commons.text.StringEscapeUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static jdwp.util.Pair.p;

public interface AST {

    static StringLiteral literal(String string) {
        return new StringLiteral(string);
    }

    static IntegerLiteral literal(long integer) {
        return new IntegerLiteral(integer);
    }

    static Identifier ident(String identifier) {
        return new Identifier(identifier);
    }

    interface StatementVisitor {
        void visit(FunctionCall functionCall);

        void visit(Loop loop);
    }

    interface PrimitiveVisitor<R> {
        default R visit(StringLiteral string) {
            return visit((Literal<?>) string);
        }

        default R visit(IntegerLiteral integer) {
            return visit((Literal<?>) integer);
        }

        default R visit(Literal<?> literal) { return null; }

        R visit(Identifier name);
    }

    abstract class Primitive implements AST {
        public abstract <R> R accept(PrimitiveVisitor<R> visitor);
    }

    @EqualsAndHashCode(callSuper = false)
    abstract class Literal<T> extends Primitive {

        protected final T value;

        protected Literal(T value) {
            this.value = value;
        }

        public T get() {
            return value;
        }
    }

    class StringLiteral extends Literal<String> {
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

    class IntegerLiteral extends Literal<Long> {
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
    class Identifier extends Primitive {
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

    abstract class Statement implements AST {

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
    class FunctionCall extends Statement {

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
    class Loop extends Statement {
        private final Identifier iter;
        private final Identifier iterable;
        private final Body body;

        public Loop(Identifier iter, Identifier iterable, List<Statement> body) {
            this(iter, iterable, new Body(body));
        }

        public Loop(Identifier iter, Identifier iterable, Body body) {
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
                    body.toPrettyString(subIndent, innerIndent));
        }

        private boolean isHeaderEqual(Loop other) {
            return other.iter.equals(iter) && other.iterable.equals(iterable);
        }

        public List<Loop> merge(Loop other) {
            if (isHeaderEqual(other)) {
                return List.of(new Loop(iter, iterable, body.merge(other.body)));
            }
            return List.of(this, other);
        }

        public List<Loop> overlap(Loop other) {
            if (isHeaderEqual(other)) {
                var newBody = body.overlap(other.body);
                if (newBody.size() > 0) {
                    return List.of(new Loop(iter, iterable, newBody));
                }
            }
            return List.of();
        }

        public Pair<Identifier, Identifier> getHeader() {
            return p(iter, iterable);
        }
    }

    class SyntaxError extends RuntimeException {
        public SyntaxError(int line, int column, String msg) {
            super(String.format("Error at %d.%d: %s", line, column, msg));
        }
    }

    class Parser {
        private final InputStream stream;
        private int current;
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
            StringBuilder buf = new StringBuilder();
            while (!isEOF() && current != ' ' && current != ')' && current != '(' && current != '\n') {
                buf.append(currentChar());
                next();
            }
            return new Identifier(buf.toString());
        }

        IntegerLiteral parseInteger() {
            StringBuilder buf = new StringBuilder();
            while (!isEOF() && Character.isDigit(current)) {
                buf.append(currentChar());
                next();
            }
            return new IntegerLiteral(Long.parseLong(buf.toString()));
        }

        StringLiteral parseString() {
            StringBuilder buf = new StringBuilder();
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

        Literal<?> parseLiteral() {
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

    class Body extends AbstractList<Statement> implements AST {
        private final List<Statement> body;

        public Body(List<Statement> body) {
            this.body = body;
        }

        @Override
        public String toString() {
            return body.stream().map(Statement::toString).collect(Collectors.joining(" "));
        }

        public String toPrettyString() {
            return body.stream().map(s -> s.toPrettyString(Program.INDENT)).collect(Collectors.joining("\n"));
        }

        public String toPrettyString(String indent, String innerIndent) {
            return body.stream().map(s -> s.toPrettyString(indent, innerIndent)).collect(Collectors.joining("\n"));
        }

        /** run on each sub statement */
        public void accept(StatementVisitor visitor) {
            body.forEach(s -> s.accept(visitor));
        }

        @Override
        public int size() {
            return body.size();
        }

        @Override
        public boolean isEmpty() {
            return body.isEmpty();
        }

        public Map<Statement, Integer> getIndexesOfStatements() {
            return IntStream.range(0, body.size()).boxed().collect(Collectors.toMap(body::get, i -> i));
        }

        public Map<Pair<Identifier, Identifier>, List<Integer>> getLoopIndexes() {
            return IntStream.range(0, body.size()).boxed().filter(i -> get(i) instanceof Loop).collect(Collectors.groupingBy(i -> {
                var loop = (Loop)get(i);
                return p(loop.getIter(), loop.getIterable());
            }));
        }

        /**
         * Merge both programs using a simple algorithm that works if the program generation is highly deterministic.
         */
        public Body merge(Body other) {
            if (other.isEmpty()) {
                return this;
            }
            if (isEmpty()) {
                return other;
            }
            var indexes = other.getIndexesOfStatements();
            var loopIndexes = other.getLoopIndexes();
            List<Statement> newStatements = new ArrayList<>();
            int prevIndex = 0;
            for (Statement statement : body) {
                final var pi = prevIndex;
                List<Integer> loopInd = statement instanceof Loop ? loopIndexes.getOrDefault(((Loop) statement).getHeader(), List.of()).stream().filter(i -> i >= pi).collect(Collectors.toList()) : List.of();
                if (indexes.containsKey(statement) || loopInd.size() > 0) {
                    int sIndex;
                    if (loopInd.size() > 0) {
                        sIndex = loopInd.get(0);
                    } else {
                        sIndex = indexes.get(statement);
                    }
                    while (prevIndex < sIndex) { // prevIndex == index: we add the statement later
                        newStatements.add(other.body.get(prevIndex));
                        prevIndex++;
                    }
                    prevIndex++;
                    if (loopInd.size() > 0) {
                        newStatements.addAll(((Loop)statement).merge((Loop) other.body.get(sIndex)));
                    } else {
                        newStatements.add(statement);
                    }
                } else {
                    newStatements.add(statement);
                }
            }
            while (prevIndex < other.body.size()) {
                newStatements.add(other.body.get(prevIndex));
                prevIndex++;
            }
            return new Body(newStatements);
        }

        public Body overlap(Body other) {
            if (isEmpty() || other.isEmpty()) {
                return new Body(List.of());
            }
            var statementIndexes = getIndexesOfStatements();
            var loopIndexes = getLoopIndexes();
            List<Statement> newStatements = new ArrayList<>();
            int lastIndex = -1;
            for (Statement statement : other) {
                if (statement instanceof Loop) {
                    var loop = (Loop)statement;
                    var header = p(loop.getIter(), loop.getIterable());
                    if (loopIndexes.containsKey(header)) {
                        for (Integer index : loopIndexes.get(header)) {
                            if (index > lastIndex) {
                                newStatements.addAll(((Loop)get(index)).overlap(loop));
                                lastIndex = index;
                            }
                        }
                    }
                } else if (statementIndexes.containsKey(statement)) {
                    var index = statementIndexes.get(statement);
                    if (index > lastIndex) {
                        newStatements.add(statement);
                        lastIndex = index;
                    }
                }
            }
            return new Body(newStatements);
        }

        @Override
        public Statement get(int index) {
            return body.get(index);
        }
    }
}
