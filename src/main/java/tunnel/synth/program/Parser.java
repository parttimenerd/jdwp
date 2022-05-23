package tunnel.synth.program;

import jdwp.AccessPath;
import lombok.SneakyThrows;
import org.apache.commons.text.StringEscapeUtils;
import tunnel.synth.program.AST.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class Parser {
    private final InputStream stream;
    private int current;
    private int line = 1;
    private int column = 1;
    private final Scopes<Identifier> sourceExpressions;

    @SneakyThrows
    private Parser(InputStream stream) {
        this.stream = stream;
        this.current = stream.read();
        this.sourceExpressions = new Scopes<>();
    }

    public Parser(String input) {
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
            throw new SyntaxError(
                    line,
                    column,
                    String.format("Expected '%s' but got '%s'", expected, Character.toString(current)));
        }
        next();
    }

    private void expect(String expected) {
        expected.chars().forEach(c -> expect((char) c));
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
        sourceExpressions.push();
        List<Statement> statements = new ArrayList<>();
        while (current != ')') {
            skipWhitespace();
            statements.add(parseStatement());
        }
        sourceExpressions.pop();
        return statements;
    }

    Statement parseStatement() {
        expect('(');
        Statement ret;
        switch (current) {
            case '=':
                ret = parseAssignment();
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

    AssignmentStatement parseAssignment() {
        expect("= ");
        skipWhitespace();
        var ret = parseIdentifier();
        skipWhitespace();
        var expression = parseExpression();
        ret.setSource(expression);
        return new AssignmentStatement(ret, expression);
    }

    Loop parseLoop() {
        sourceExpressions.push();
        expect("for ");
        skipWhitespace();
        var iter = parseIdentifier();
        skipWhitespace();
        var iterable = parseExpression();
        iter.setSource(iterable);
        iter.setLoopIterableRelated(true);
        skipWhitespace();
        var body = parseBlock();
        sourceExpressions.pop();
        return new Loop(iter, iterable, body);
    }

    Expression parseExpression() {
        if (current == '(') {
            return parseFunctionCall();
        }
        return parsePrimitive();
    }

    Expression parseFunctionCall() {
        expect('(');
        skipWhitespace();
        var functionName = parseIdentifier().getName();
        if (functionName.equals("request")) {
            return parseRequestCall();
        }
        skipWhitespace();
        List<Expression> arguments = new ArrayList<>();
        while (current != ')') {
            arguments.add(parseExpression());
            skipWhitespace();
        }
        expect(')');
        return new FunctionCall(functionName, arguments);
    }

    /** request commandSet command ("p1" p2)=(wrap type primitive) or ... (p1 p2)=(get obj p1 p2) */
    RequestCall parseRequestCall() {
        skipWhitespace();
        var commandSet = parseIdentifier().getName();
        skipWhitespace();
        var command = parseIdentifier().getName();
        skipWhitespace();
        List<RequestCallProperty> arguments = new ArrayList<>();
        while (current != ')') {
            arguments.add(parseRequestCallProperty());
            skipWhitespace();
        }
        expect(')');
        return new RequestCall(commandSet, command, arguments);
    }

    RequestCallProperty parseRequestCallProperty() {
        AccessPath path = parseAccessPath();
        expect('=');
        FunctionCall accessor = (FunctionCall) parseFunctionCall();
        return new RequestCallProperty(path, accessor);
    }

    public AccessPath parseAccessPath() {
        expect('(');
        skipWhitespace();
        List<Literal<?>> path = new ArrayList<>();
        while (current != ')') {
            path.add(parseLiteral());
            skipWhitespace();
        }
        expect(')');
        return new AccessPath(
                path.stream()
                        .map(l -> l.value instanceof Long ? ((Long) l.value).intValue() : l.value)
                        .toArray());
    }

    Identifier parseIdentifier() {
        StringBuilder buf = new StringBuilder();
        while (!isEOF() && current != ' ' && current != ')' && current != '(' && current != '\n') {
            buf.append(currentChar());
            next();
        }
        if (buf.length() == 0) {
            throw new SyntaxError(line, column, "Empty identifier not supported");
        }
        var str = buf.toString();
        if (sourceExpressions.contains(str)) {
            return sourceExpressions.get(str);
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
        char usedQuote = current == '\'' ? '\'' : '"';
        expect(usedQuote);
        while (!isEOF() && current != usedQuote) {
            buf.append(currentChar());
            next();
            if (current == '\\') {
                buf.append(currentChar());
                next();
            }
        }
        expect(usedQuote);
        return new StringLiteral(StringEscapeUtils.unescapeEcmaScript(buf.toString()));
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
