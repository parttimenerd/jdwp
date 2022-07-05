package tunnel.synth.program;

import jdwp.AccessPath;
import lombok.SneakyThrows;
import org.apache.commons.text.StringEscapeUtils;
import org.jetbrains.annotations.NotNull;
import tunnel.synth.program.AST.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * the language is constructed to work with an LL(1) PEG parser, making it feasible to write the parser by hand
 * without using a lexer
 */
public class Parser {
    private final InputStream stream;
    private int current;
    private int line = 1;
    private int column = 1;
    private final Scopes<Identifier> identifiers;
    private final Scopes<Identifier> currentRecs;

    @SneakyThrows
    private Parser(InputStream stream) {
        this.stream = stream;
        this.current = stream.read();
        this.identifiers = new Scopes<>();
        this.currentRecs = new Scopes<>();
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
                    String.format("Expected \"%s\" but got %s", StringEscapeUtils.escapeJava(Character.toString(expected)),
                            isEOF() ? "end of line" : "\"" + StringEscapeUtils.escapeJava(Character.toString(current)) + "\""));
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
        var block = parseBlock();
        AssignmentStatement cause = null;
        if (!block.isEmpty() && block.get(0) instanceof AssignmentStatement &&
                ((AssignmentStatement) block.get(0)).isCause()) {
            cause = (AssignmentStatement)block.remove(0);
        }
        var program = new Program(cause == null ? null : (PacketCall) cause.getExpression(), block);
        skipWhitespace();
        expect(')');
        return program;
    }

    List<Statement> parseBlock() {
        identifiers.push();
        List<Statement> statements = new ArrayList<>();
        while (current != ')') {
            skipWhitespace();
            statements.add(parseStatement());
            skipWhitespace();
        }
        identifiers.pop();
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
            case 'm':
                ret = parseMapCallStatement();
                break;
            case 's':
                ret = parseSwitchStatement();
                break;
            case 'r':
                ret = parseRecursionRelated();
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
        var ret = parseIdentifierAndRecord();
        skipWhitespace();
        var expression = parseExpression(true);
        var statement = new AssignmentStatement(ret, expression);
        ret.setSource(statement);
        return statement;
    }

    Loop parseLoop() {
        identifiers.push();
        expect("for ");
        skipWhitespace();
        var iter = parseIdentifierAndRecord();
        skipWhitespace();
        var iterable = parseExpression();
        iter.setLoopIterableRelated(true);
        skipWhitespace();
        var body = parseBlock();
        identifiers.pop();
        return new Loop(iter, iterable, body);
    }

    Statement parseRecursionRelated() {
        switch (parseIdentifier().getName()) {
            case "rec":
                return parseRecursion();
            case "reccall":
                return parseRecCall();
            default:
                throw new SyntaxError(line, column, String.format("Unexpected %s", (char) current));
        }
    }

    Recursion parseRecursion() {
        skipWhitespace();
        var name = parseIdentifier();
        skipWhitespace();
        int maxRec = (int) (long) parseInteger().value;
        identifiers.push();
        skipWhitespace();
        var requestVariable = parseIdentifierAndRecord();
        skipWhitespace();
        var request = parseFunctionCall(true);
        if (!(request instanceof RequestCall)) {
            throw new SyntaxError(line, column, "Expected request call in rec");
        }
        skipWhitespace();
        currentRecs.push();
        currentRecs.put(name.toString(), name);
        identifiers.put(requestVariable.toString(), requestVariable);
        var body = parseBlock();
        currentRecs.pop();
        identifiers.pop();
        return new Recursion(name, maxRec, requestVariable, (RequestCall) request, new Body(body));
    }

    RecRequestCall parseRecCall() {
        skipWhitespace();
        var variable = parseIdentifierAndRecord();
        skipWhitespace();
        var name = parseIdentifier();
        if (!currentRecs.contains(name.getName())) {
            throw new SyntaxError(line, column, "Unknown recursion " + name);
        }
        skipWhitespace();
        List<CallProperty> arguments = parseCallPropertyList();
        return new RecRequestCall(variable, name, arguments);
    }

    MapCallStatement parseMapCallStatement() {
        expect("map ");
        skipWhitespace();
        var variable = parseIdentifierAndRecord();
        skipWhitespace();
        var iterable = parseExpression();
        skipWhitespace();
        var skip = (int)(long)parseInteger().value;
        skipWhitespace();
        identifiers.push();
        var iter = parseIdentifier();
        skipWhitespace();
        List<CallProperty> arguments = parseCallPropertyList();
        identifiers.pop();
        return new MapCallStatement(variable, iterable, skip, iter, arguments);
    }

    SwitchStatement parseSwitchStatement() {
        expect("switch ");
        skipWhitespace();
        var expression = parseExpression();
        skipWhitespace();
        List<CaseStatement> cases = parseCaseStatements();
        return new SwitchStatement(expression, cases);
    }

    List<CaseStatement> parseCaseStatements() {
        List<CaseStatement> cases = new ArrayList<>();
        while (current != ')') {
            skipWhitespace();
            expect('(');
            cases.add(parseCaseStatement());
            expect(')');
            skipWhitespace();
        }
        return cases;
    }

    CaseStatement parseCaseStatement() {
        var start = parseIdentifier();
        Expression expression = null;
        if (start.getName().equals("case")) {
            skipWhitespace();
            expression = parseExpression();
        } else {
            if (!start.getName().equals("default")) {
                throw new SyntaxError(line, column, "Expected case or default");
            }
        }
        skipWhitespace();
        var body = parseBlock();
        return new CaseStatement(expression, body);
    }

    Expression parseExpression() {
        return parseExpression(false);
    }

    Expression parseExpression(boolean allowPacketCall) {
        if (current == '(') {
            return parseFunctionCall(allowPacketCall);
        }
        return parsePrimitive();
    }

    Expression parseFunctionCall(boolean allowPacketCall) {
        expect('(');
        skipWhitespace();
        var functionName = parseIdentifier().getName();
        if (functionName.equals("request") || functionName.equals("events")) {
            if (!allowPacketCall) {
                throw new SyntaxError(line, column, "request and events are not supported");
            }
            return parsePacketCall(functionName);
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

    /** request/events commandSet command ("p1" p2)=(wrap type primitive) or ... (p1 p2)=(get obj p1 p2) */
    PacketCall parsePacketCall(String name) {
        skipWhitespace();
        var commandSet = parseIdentifier().getName();
        skipWhitespace();
        var command = parseIdentifier().getName();
        skipWhitespace();
        List<CallProperty> arguments = parseCallPropertyList();
        expect(')');
        switch (name) {
            case "request":
                return new RequestCall(commandSet, command, arguments);
            case "events":
                return new EventsCall(commandSet, command, arguments);
            default:
                throw new AssertionError(String.format("Unknown packet call name %s", name));
        }
    }


    @NotNull
    private List<CallProperty> parseCallPropertyList() {
        List<CallProperty> arguments = new ArrayList<>();
        while (current != ')') {
            arguments.add(parseCallProperty());
            skipWhitespace();
        }
        return arguments;
    }

    CallProperty parseCallProperty() {
        AccessPath path = parseAccessPath();
        expect('=');
        return new CallProperty(path, parseExpression());
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

    Identifier parseIdentifierAndRecord() {
        Identifier identifier = parseIdentifier();
        if (identifiers.contains(identifier.getName())) {
            return identifiers.get(identifier.getName());
        }
        identifiers.put(identifier.getName(), identifier);
        return identifier;
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
        if (identifiers.contains(str)) {
            return identifiers.get(str);
        }
        var ident = new Identifier(buf.toString());
        identifiers.put(str, ident);
        return ident;
    }

    IntegerLiteral parseInteger() {
        StringBuilder buf = new StringBuilder();
        if (current == '-') {
            buf.append(currentChar());
            next();
        }
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
            if (current == '\\') {
                next();

            }
            buf.append(currentChar());
            next();
        }
        expect(usedQuote);
        return new StringLiteral(StringEscapeUtils.unescapeEcmaScript(buf.toString()));
    }

    Literal<?> parseLiteral() {
        if (Character.isDigit(current) || current == '-') {
            return parseInteger();
        }
        return parseString();
    }

    Primitive parsePrimitive() {
        if (Character.isAlphabetic(current)) {
            return parseIdentifierAndRecord();
        }
        return parseLiteral();
    }
}
