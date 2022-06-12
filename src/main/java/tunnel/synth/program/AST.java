package tunnel.synth.program;

import com.google.common.collect.Lists;
import jdwp.AccessPath;
import jdwp.EventCmds.Events;
import jdwp.EventRequestCmds;
import jdwp.EventRequestCmds.SetRequest.ModifierCommon;
import jdwp.JDWP;
import jdwp.Request;
import jdwp.Value.TaggedBasicValue;
import jdwp.util.Pair;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.text.StringEscapeUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.ProgramHashes;
import tunnel.synth.Synthesizer;
import tunnel.synth.program.Functions.Function;
import tunnel.synth.program.Visitors.*;

import java.util.*;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static tunnel.synth.program.Functions.WRAP;
import static tunnel.synth.program.Functions.createWrapperFunctionCall;

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

    abstract class Expression implements AST {
        public static Expression parse(String input) {
            return new Parser(input).parseExpression();
        }

        public abstract void accept(ExpressionVisitor visitor);

        public abstract <R> R accept(ReturningExpressionVisitor<R> visitor);

        public abstract List<Expression> getSubExpressions();

        public boolean doesDependOnStatement(Statement other) {
            return getSubExpressions().stream().anyMatch(e -> e.doesDependOnStatement(other));
        }

        /**
         * directly contains reference value
         */
        public boolean isDirectPointerRelated() {
            return getSubExpressions().stream().anyMatch(Expression::isDirectPointerRelated);
        }
    }

    abstract class Primitive extends Expression {
        public abstract void accept(PrimitiveVisitor visitor);

        public abstract <R> R accept(ReturningPrimitiveVisitor<R> visitor);

        @Override
        public <R> R accept(ReturningExpressionVisitor<R> visitor) {
            return accept((ReturningPrimitiveVisitor<R>) visitor);
        }

        @Override
        public void accept(ExpressionVisitor visitor) {
            accept((PrimitiveVisitor) visitor);
        }
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

        @Override
        public List<Expression> getSubExpressions() {
            return List.of();
        }
    }

    class StringLiteral extends Literal<String> {
        public StringLiteral(String string) {
            super(string);
        }

        @Override
        public <R> R accept(ReturningPrimitiveVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public void accept(PrimitiveVisitor visitor) {
            visitor.visit(this);
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
        public <R> R accept(ReturningPrimitiveVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public void accept(PrimitiveVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public String toString() {
            return value.toString();
        }
    }

    @Getter
    @EqualsAndHashCode(
            callSuper = false,
            of = {"name"})
    @AllArgsConstructor
    class Identifier extends Primitive {
        private final String name;

        @Nullable @Setter private Statement source;

        @Setter
        private boolean isLoopIterableRelated;
        @Setter
        private boolean mapIterableRelated;

        public Identifier(String name) {
            this.name = name;
        }

        @Override
        public <R> R accept(ReturningPrimitiveVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public void accept(PrimitiveVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public String toString() {
            return name;
        }

        public String getName() {
            return name;
        }

        public boolean hasSource() {
            return source != null;
        }

        public @Nullable Statement getSource() {
            return source;
        }

        public boolean isGlobalVariable() {
            return source == null;
        }

        @Override
        public List<Expression> getSubExpressions() {
            return List.of();
        }

        @Override
        public boolean doesDependOnStatement(Statement other) {
            return source != null && source.equals(other);
        }
    }

    abstract class Statement implements AST {

        @Getter
        private ProgramHashes hashes;

        public Statement setHashes(ProgramHashes hashes) {
            this.hashes = hashes;
            return this;
        }

        public Statement initHashes(@Nullable ProgramHashes parentHash) {
            ProgramHashes.setInStatement(parentHash, this);
            return this;
        }

        public abstract void accept(StatementVisitor visitor);

        public abstract <R> R accept(ReturningStatementVisitor<R> visitor);

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

        public static Statement parse(String input) {
            return new Parser(input).parseStatement();
        }

        public List<Statement> getSubStatements() {
            return List.of();
        }

        public List<Expression> getSubExpressions() {
            return List.of();
        }

        public boolean doesDependOn(Statement other) {
            return getSubExpressions().stream().anyMatch(e -> e.doesDependOnStatement(other)) ||
                    getSubStatements().stream().anyMatch(s -> s.doesDependOn(other));
        }

        public boolean isDirectPointerRelated() {
            return getSubExpressions().stream().anyMatch(Expression::isDirectPointerRelated) ||
                    getSubStatements().stream().anyMatch(Statement::isDirectPointerRelated);
        }
    }

    interface CompoundStatement<T extends Statement> extends AST {

        List<Statement> getSubStatements();

        T removeStatements(Set<Statement> statements);

        default T removeStatementsTransitively(Set<Statement> statements) {
            return removeStatements(getDependentStatementsAndAnchors(statements));
        }

        default Set<Statement> getDependentStatements(Set<Statement> statements) {
            Set<Statement> ret = new HashSet<>();
            for (Statement subStatement : getSubStatements()) {
                for (Statement other : statements) {
                    if (subStatement.doesDependOn(other)) {
                        ret.add(subStatement);
                    }
                    if (subStatement instanceof CompoundStatement<?>) {
                        ret.addAll(((CompoundStatement<?>) subStatement).getDependentStatements(statements));
                    }
                }
            }
            return ret;
        }

        default Set<Statement> getDependentStatementsAndAnchor(Statement anchor) {
            return getDependentStatementsAndAnchors(Set.of(anchor));
        }

        default Set<Statement> getDependentStatementsAndAnchors(Set<Statement> anchors) {
            if (anchors.isEmpty()) {
                return Set.of();
            }
            return Stream.concat(
                    getDependentStatements(anchors).stream(),
                    anchors.stream()
            ).collect(Collectors.toSet());
        }

        default Set<Statement> getDirectPointerRelatedStatements() {
            Set<Statement> ret = new HashSet<>();
            ((Statement) this).getSubStatements().forEach(s -> s.accept(new StatementVisitor() {
                @Override
                public void visit(Loop loop) {
                    if (loop.isDirectPointerRelated()) {
                        ret.add(loop);
                    } else {
                        ret.addAll(loop.getDirectPointerRelatedStatements());
                    }
                }

                @Override
                public void visit(Statement statement) {
                    if (statement.isDirectPointerRelated()) {
                        ret.add(statement);
                    } else if (statement instanceof CompoundStatement<?>) {
                        ret.addAll(((CompoundStatement<?>) statement).getDirectPointerRelatedStatements());
                    }
                }
            }));
            return ret;
        }

        default T removeStatementsTransitively(Predicate<Statement> predicate) {
            Set<Statement> toRemove = new HashSet<>();
            ((Statement) this).getSubStatements().forEach(s -> s.accept(new StatementVisitor() {
                @Override
                public void visit(Statement statement) {
                    if (predicate.test(statement)) {
                        toRemove.add(statement);
                    } else {
                        statement.getSubStatements().forEach(this::visit);
                    }
                }
            }));
            return removeStatementsTransitively(toRemove);
        }

        /**
         * remove statements that directly contain a wrapped direct pointer
         */
        default T removeDirectPointerRelatedStatements() {
            return removeStatements(getDependentStatementsAndAnchors(getDirectPointerRelatedStatements()));
        }

        @SuppressWarnings("unchecked")
        default T removeDirectPointerRelatedStatementsTransitively() {
            var statements = getDependentStatementsAndAnchors(getDirectPointerRelatedStatements());
            if (statements.isEmpty()) {
                return (T) this;
            }
            return removeStatementsTransitively(statements);
        }
    }

    interface PartiallyMergeable<T extends PartiallyMergeable<T>> extends AST {
        List<T> merge(T other);

        List<T> overlap(T other);
    }

    @Getter
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    class AssignmentStatement extends Statement {
        private final Identifier variable;
        private final Expression expression;

        @Override
        public void accept(StatementVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public <R> R accept(ReturningStatementVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public String toPrettyString(String indent, String innerIndent) {
            return String.format("%s(= %s %s)", indent, variable, expression);
        }

        @Override
        public List<Expression> getSubExpressions() {
            return List.of(variable, expression);
        }

        public boolean isCause() {
            return variable.name.equals(Synthesizer.CAUSE_NAME);
        }
    }

    @AllArgsConstructor
    @Getter
    @EqualsAndHashCode(exclude = "function", callSuper = false)
    class FunctionCall extends Expression {

        private final String functionName;
        @Setter private Function function;
        private final List<Expression> arguments;

        public FunctionCall(String functionName, List<Expression> arguments) {
            this.functionName = functionName;
            this.arguments = arguments;
        }

        @Override
        public void accept(ExpressionVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public <R> R accept(ReturningExpressionVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public String toString() {
            return String.format(
                    "(%s%s%s)",
                    functionName,
                    arguments.size() > 0 ? " " : "",
                    arguments.stream().map(Object::toString).collect(Collectors.joining(" ")));
        }

        @Override
        public List<Expression> getSubExpressions() {
            return arguments;
        }

        @Override
        public boolean isDirectPointerRelated() {
            if (functionName.equals(WRAP) && arguments.get(1) instanceof IntegerLiteral) {
                assert arguments.get(0) instanceof StringLiteral;
                return Functions.applyWrapper(((StringLiteral) arguments.get(0)).get(),
                        ((IntegerLiteral) arguments.get(1)).get()).isDirectPointer();
            }
            return arguments.stream().anyMatch(Expression::isDirectPointerRelated);
        }
    }

    @Getter
    @EqualsAndHashCode(callSuper = false)
    abstract class PacketCall extends Expression {
        private final String name;
        private final String commandSet;
        private final String command;
        private final List<CallProperty> properties;

        public PacketCall(String name, String commandSet, String command, List<CallProperty> properties) {
            this.name = name;
            this.commandSet = commandSet;
            this.command = command;
            this.properties =
                    properties.stream().sorted(Comparator.comparing(CallProperty::getPath)).collect(Collectors.toList());
        }

        @Override
        public <R> R accept(ReturningExpressionVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public void accept(ExpressionVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Expression> getSubExpressions() {
            return (List<Expression>) (List<? extends Expression>) properties;
        }

        @Override
        public String toString() {
            return String.format(
                    "(%s %s %s%s%s)",
                    name,
                    commandSet,
                    command,
                    properties.isEmpty() ? "" : " ",
                    properties.stream().map(Object::toString).collect(Collectors.joining(" ")));
        }


        /**
         * returns 0 if both are incompatible (e.g. one is a request and the other an event) and > 0 if there is some
         * similarity (and programs with one as a cause are useful for programs with the other as a cause)
         */
        public float computeSimilarity(PacketCall other) {
            if (!getCommandSet().equals(other.getCommandSet()) ||
                    !getCommand().equals(other.getCommand())) { // request vs events
                return 0;
            }
            return compareProperties(getProperties(), other.getProperties());
        }

        /** returns a value >= 0 which is larger for more similar property lists */
        static float compareProperties(List<CallProperty> props1, List<CallProperty> props2) {
            // compute the percentage of equal props
            Set<CallProperty> props2Set = new HashSet<>(props2);
            return (float)props1.stream().mapToDouble(p -> props2Set.contains(p) ? 1 : 0).sum();
        }
    }

    class RequestCall extends PacketCall {

        public RequestCall(String commandSet, String command, List<CallProperty> properties) {
            super("request", commandSet, command, properties);
        }

        @Override
        public <R> R accept(ReturningExpressionVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public void accept(ExpressionVisitor visitor) {
            visitor.visit(this);
        }

        private static RequestCall create(
                String commandSet,
                String command,
                Stream<TaggedBasicValue<?>> taggedValues,
                List<CallProperty> properties) {
            return new RequestCall(
                    commandSet,
                    command,
                    Stream.concat(taggedValues.map(CallProperty::create), properties.stream())
                            .collect(Collectors.toList()));
        }

        public static RequestCall create(Request<?> request) {
            if (request instanceof EventRequestCmds.SetRequest) {
                var setRequest = (EventRequestCmds.SetRequest) request;
                List<TaggedBasicValue<?>> tagged = new ArrayList<>();
                tagged.add(new TaggedBasicValue<>(new AccessPath("eventKind"), setRequest.eventKind));
                tagged.add(new TaggedBasicValue<>(new AccessPath("suspendPolicy"), setRequest.suspendPolicy));
                int i = 0;
                for (ModifierCommon modifier : setRequest.modifiers) {
                    var prefix = new AccessPath("modifiers", i);
                    tagged.add(new TaggedBasicValue<>(prefix.append("kind"), wrap(modifier.getClass().getSimpleName())));
                    modifier.getTaggedValues().forEach(t -> tagged.add(t.prependPath(prefix)));
                    i++;
                }
                return create(request.getCommandSetName(), request.getCommandName(), tagged.stream(), List.of());
            }
            return create(
                    request.getCommandSetName(),
                    request.getCommandName(),
                    request.asCombined().getTaggedValues(),
                    List.of());
        }

        public float getCost() {
            return JDWP.getCost(JDWP.getCommandSetByte(getCommandSet()),
                    JDWP.getCommandByte(getCommandSet(), getCommand()));
        }
    }

    class EventsCall extends PacketCall {

        public EventsCall(String commandSet, String command, List<CallProperty> properties) {
            super("events", commandSet, command, properties);
        }

        @Override
        public <R> R accept(ReturningExpressionVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public void accept(ExpressionVisitor visitor) {
            visitor.visit(this);
        }

        /** has to contain the kind property for each event (the kind property is the class name of the event) */
        public static EventsCall create(
                String commandSet,
                String command,
                Stream<TaggedBasicValue<?>> taggedValues,
                List<CallProperty> properties) {
            return new EventsCall(
                    commandSet,
                    command,
                    Stream.concat(taggedValues.map(CallProperty::create), properties.stream())
                            .collect(Collectors.toList()));
        }

        public static EventsCall create(Events events) {
            return create(
                    events.getCommandSetName(),
                    events.getCommandName(),
                    Stream.concat(events.events.getValues().stream().map(p ->
                                    new TaggedBasicValue<>(new AccessPath("events", p.first, "kind"),
                                                wrap(p.second.getClass().getSimpleName()))),
                            events.asCombined().getTaggedValues()),
                    List.of());
        }
        public float computeSimilarity(PacketCall other) {
            if (!getCommandSet().equals(other.getCommandSet()) ||
                    !getCommand().equals(other.getCommand()) ||
                    !getKinds().equals(((EventsCall)other).getKinds())) { // request vs events
                return 0;
            }
            return compareProperties(getProperties(), other.getProperties());
        }

        private Set<String> getKinds() {
            return getProperties().stream().filter(p -> p.getPath().endsWith("kind"))
                    .map(p -> ((StringLiteral) p.getAccessor().arguments.get(1)).value).collect(Collectors.toSet());
        }
    }

    /**
     * (access path)=(expression)
     */
    @Getter
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    class CallProperty extends Expression {
        private final AccessPath path;
        private final FunctionCall accessor;

        @Override
        public String toString() {
            return String.format(
                    "(%s)=%s",
                    path.stream()
                            .map(o -> o instanceof String ? String.format("\"%s\"", o) : o.toString())
                            .collect(Collectors.joining(" ")),
                    accessor);
        }

        public static CallProperty create(TaggedBasicValue<?> taggedValue) {
            return new CallProperty(
                    taggedValue.path, createWrapperFunctionCall(taggedValue.getValue()));
        }

        @Override
        public void accept(ExpressionVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public <R> R accept(ReturningExpressionVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public List<Expression> getSubExpressions() {
            return List.of(accessor);
        }
    }

    @Getter
    @EqualsAndHashCode(callSuper = false)
    @AllArgsConstructor
    class Loop extends Statement implements CompoundStatement<Loop>, PartiallyMergeable<Loop> {
        private final Identifier iter;
        private final Expression iterable;
        private final Body body;

        public Loop(Identifier iter, Expression iterable, List<Statement> body) {
            this(iter, iterable, new Body(body));
        }

        @Override
        public void accept(StatementVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public <R> R accept(ReturningStatementVisitor<R> visitor) {
            return visitor.visit(this);
        }

        public String toPrettyString(String indent, String innerIndent) {
            String subIndent = innerIndent + indent;
            return String.format(
                    "%s(for %s %s %s%s)",
                    indent,
                    iter,
                    iterable,
                    innerIndent.isEmpty() ? "" : "\n",
                    body.toPrettyString(subIndent, innerIndent));
        }

        @Override
        public List<Loop> merge(Loop other) {
            if (getHashes().get(this).equals(other.getHashes().get(other))) {
                return List.of((Loop) new Loop(iter, iterable, body.merge(other.body)).initHashes(getHashes().getParent()));
            }
            return List.of(this, other);
        }

        @Override
        public List<Loop> overlap(Loop other) {
            if (getHashes().get(this).equals(other.getHashes().get(other))) {
                var newBody = body.overlap(other.body);
                if (newBody.size() > 0) {
                    return List.of((Loop) new Loop(iter, iterable, newBody).initHashes(getHashes().getParent()));
                }
            }
            return List.of();
        }

        public Pair<Identifier, Expression> getHeader() {
            return p(iter, iterable);
        }

        @Override
        public List<Statement> getSubStatements() {
            return List.of(body);
        }

        @Override
        public Loop removeStatements(Set<Statement> statements) {
            return (Loop) new Loop(iter, iterable, body.removeStatements(statements)).initHashes(getHashes().getParent());
        }

        @Override
        public List<Expression> getSubExpressions() {
            return List.of(iter, iterable);
        }
    }

    @Getter
    @EqualsAndHashCode(callSuper = false)
    class SwitchStatement extends Statement
            implements CompoundStatement<SwitchStatement>, PartiallyMergeable<SwitchStatement> {
        private final Expression expression;
        private final List<CaseStatement> cases;

        public SwitchStatement(Expression expression, List<CaseStatement> cases) {
            this.expression = expression;
            this.cases = cases;
        }

        @Override
        public void accept(StatementVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public <R> R accept(ReturningStatementVisitor<R> visitor) {
            return visitor.visit(this);
        }

        public String toPrettyString(String indent, String innerIndent) {
            String subIndent = innerIndent + indent;
            return String.format(
                    "%s(switch %s%s%s)",
                    indent,
                    expression,
                    innerIndent.isEmpty() ? (cases.isEmpty() ? "" : " ") : "\n",
                    cases.stream().map(c -> c.toPrettyString(subIndent, innerIndent))
                            .collect(Collectors.joining(innerIndent.isEmpty() ? " " : "\n")));
        }

        @Override
        public List<SwitchStatement> merge(SwitchStatement other) {
            throw new AssertionError("not implemented");
        }

        @Override
        public List<SwitchStatement> overlap(SwitchStatement other) {
            if (getHashes().get(this).equals(other.getHashes().get(other))) {
                var newBody = cases.stream().filter(c -> other.getHashes().contains(getHashes().get(c)))
                        .flatMap(c -> c.overlap((CaseStatement) other.getHashes().get(getHashes().get(c))).stream())
                        .collect(Collectors.toList());
                if (newBody.size() > 0) {
                    return List.of((SwitchStatement) new SwitchStatement(expression, newBody).initHashes(getHashes().getParent()));
                }
            }
            return List.of();
        }

        @Override
        @SuppressWarnings({"unchecked", "rawtypes"})
        public List<Statement> getSubStatements() {
            return (List<Statement>) (List) cases;
        }

        @Override
        public SwitchStatement removeStatements(Set<Statement> statements) {
            return (SwitchStatement) new SwitchStatement(expression, cases.stream().
                    filter(statements::contains)
                    .map(c -> c.removeStatements(statements)).collect(Collectors.toList())).initHashes(getHashes().getParent());
        }

        @Override
        public List<Expression> getSubExpressions() {
            return List.of(expression);
        }
    }

    @Getter
    @EqualsAndHashCode(callSuper = false)
    @AllArgsConstructor
    class CaseStatement extends Statement
            implements CompoundStatement<CaseStatement>, PartiallyMergeable<CaseStatement> {
        private final Expression expression;
        private final Body body;

        CaseStatement(Expression expression, List<Statement> body) {
            this(expression, new Body(body));
        }

        @Override
        public void accept(StatementVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public <R> R accept(ReturningStatementVisitor<R> visitor) {
            return visitor.visit(this);
        }

        public String toPrettyString(String indent, String innerIndent) {
            String subIndent = innerIndent + indent;
            return String.format(
                    "%s(case %s%s%s)",
                    indent,
                    expression,
                    innerIndent.isEmpty() ? (body.isEmpty() ? "" : " ") : "\n",
                    body.toPrettyString(subIndent, innerIndent));
        }

        public List<CaseStatement> merge(CaseStatement other) {
            throw new AssertionError("not implemented");
        }

        public List<CaseStatement> overlap(CaseStatement other) {
            if (getHashes().get(this).equals(other.getHashes().get(other))) {
                var newBody = body.overlap(other.body);
                if (newBody.size() > 0) {
                    return List.of((CaseStatement) new CaseStatement(expression, newBody).initHashes(getHashes().getParent()));
                }
            }
            return List.of();
        }

        @Override
        public List<Statement> getSubStatements() {
            return List.of(body);
        }

        @Override
        public CaseStatement removeStatements(Set<Statement> statements) {
            return (CaseStatement) new CaseStatement(expression, body.removeStatements(statements)).initHashes(getHashes().getParent());
        }

        @Override
        public List<Expression> getSubExpressions() {
            return List.of(expression);
        }
    }

    /**
     * (map variable iterable iter [call properties])
     * <p>
     * Currently, only single string and empty paths are supported
     */
    @Getter
    @EqualsAndHashCode(callSuper = false)
    class MapCallStatement extends Statement {
        private final Identifier variable;
        private final Expression iterable;
        private final Identifier iter;
        private final List<CallProperty> arguments;

        public MapCallStatement(Identifier variable, Expression iterable, Identifier iter,
                                List<CallProperty> arguments) {
            this.variable = variable;
            this.iterable = iterable;
            this.iter = iter;
            this.arguments = arguments;
            checkArguments();
        }

        private void checkArguments() {
            if (arguments.isEmpty()) {
                throw new AssertionError("map call must have at least one argument");
            }
            if (!arguments.stream().map(CallProperty::getPath)
                    .allMatch(p -> p.size() <= 1 &&
                            ((p.size() == 0 && arguments.size() == 1) || p.get(0) instanceof String))) {
                throw new AssertionError("map call paths must be single string or empty");
            }
        }


        @Override
        public void accept(StatementVisitor visitor) {
            visitor.visit(this);
        }

        @Override
        public <R> R accept(ReturningStatementVisitor<R> visitor) {
            return visitor.visit(this);
        }

        public Pair<Identifier, Expression> getHeader() {
            return p(iter, iterable);
        }

        @Override
        public String toPrettyString(String indent, String innerIndent) {
            return String.format("%s(map %s %s %s%s%s)", indent, variable, iterable, iter,
                    arguments.isEmpty() ? "" : " ",
                    arguments.stream().map(CallProperty::toString).collect(Collectors.joining(" ")));
        }

        @Override
        public List<Expression> getSubExpressions() {
            return Lists.asList(iter, iterable, arguments.toArray(new CallProperty[0]));
        }
    }


    class SyntaxError extends RuntimeException {
        public SyntaxError(int line, int column, String msg) {
            super(String.format("Error at %d.%d: %s", line, column, msg));
        }
    }

    class Body extends Statement implements List<Statement>, CompoundStatement<Body> {
        private final List<Statement> body;

        @Override
        public int lastIndexOf(Object o) {
            return body.lastIndexOf(o);
        }

        @NotNull
        @Override
        public ListIterator<Statement> listIterator() {
            return body.listIterator();
        }

        @NotNull
        @Override
        public ListIterator<Statement> listIterator(int index) {
            return body.listIterator(index);
        }

        @NotNull
        @Override
        public List<Statement> subList(int fromIndex, int toIndex) {
            return body.subList(fromIndex, toIndex);
        }

        public Body(List<Statement> body) {
            this.body = body;
        }

        @Override
        public String toString() {
            return body.stream().map(Statement::toString).collect(Collectors.joining(" "));
        }

        @Override
        public String toPrettyString(String indent, String innerIndent) {
            return body.stream()
                    .map(s -> s.toPrettyString(indent, innerIndent))
                    .collect(Collectors.joining(innerIndent.isEmpty() ? " " : "\n"));
        }

        /** run on each sub statement */
        public void accept(StatementVisitor visitor) {
            visitor.visit(this);
        }

        public <R> R accept(ReturningStatementVisitor<R> visitor) {
            return visitor.visit(this);
        }

        @Override
        public int size() {
            return body.size();
        }

        @Override
        public boolean isEmpty() {
            return body.isEmpty();
        }

        @Override
        public boolean contains(Object o) {
            return body.contains(o);
        }

        @NotNull
        @Override
        public Iterator<Statement> iterator() {
            return body.iterator();
        }

        @NotNull
        @Override
        public Object[] toArray() {
            return body.toArray();
        }

        @NotNull
        @Override
        public <T> T[] toArray(@NotNull T[] a) {
            return body.toArray(a);
        }

        @Override
        public boolean add(Statement statement) {
            return body.add(statement);
        }

        @Override
        public boolean remove(Object o) {
            return body.remove(o);
        }

        @Override
        public boolean containsAll(@NotNull Collection<?> c) {
            return body.containsAll(c);
        }

        @Override
        public boolean addAll(@NotNull Collection<? extends Statement> c) {
            return body.addAll(c);
        }

        @Override
        public boolean addAll(int index, @NotNull Collection<? extends Statement> c) {
            return body.addAll(index, c);
        }

        @Override
        public boolean removeAll(@NotNull Collection<?> c) {
            return body.removeAll(c);
        }

        @Override
        public boolean retainAll(@NotNull Collection<?> c) {
            return body.retainAll(c);
        }

        @Override
        public void replaceAll(UnaryOperator<Statement> operator) {
            body.replaceAll(operator);
        }

        @Override
        public void sort(Comparator<? super Statement> c) {
            body.sort(c);
        }

        @Override
        public void clear() {
            body.clear();
        }

        @Override
        public int hashCode() {
            return body.hashCode();
        }

        @Override
        public Statement set(int index, Statement element) {
            return body.set(index, element);
        }

        @Override
        public void add(int index, Statement element) {
            body.add(index, element);
        }

        @Override
        public Statement remove(int index) {
            return body.remove(index);
        }

        @Override
        public int indexOf(Object o) {
            return body.indexOf(o);
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof Body) {
                return ((Body) o).body.equals(body);
            }
            return false;
        }

        public Map<Statement, Integer> getIndexesOfStatements() {
            return IntStream.range(0, body.size()).boxed().collect(Collectors.toMap(body::get, i -> i));
        }

        public Map<Pair<Identifier, Expression>, List<Integer>> getLoopIndexes() {
            return IntStream.range(0, body.size())
                    .boxed()
                    .filter(i -> get(i) instanceof Loop)
                    .collect(
                            Collectors.groupingBy(
                                    i -> {
                                        var loop = (Loop) get(i);
                                        return p(loop.getIter(), loop.getIterable());
                                    }));
        }

        /**
         * Merge both programs using a simple algorithm that works if the program generation is highly
         * deterministic.
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
                List<Integer> loopInd =
                        statement instanceof Loop
                                ? loopIndexes.getOrDefault(((Loop) statement).getHeader(), List.of()).stream()
                                .filter(i -> i >= pi)
                                .collect(Collectors.toList())
                                : List.of();
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
                        newStatements.addAll(((Loop) statement).merge((Loop) other.body.get(sIndex)));
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
            return (Body) new Body(newStatements).initHashes(getHashes().getParent());
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        public Body overlap(Body other) {
            if (isEmpty() || other.isEmpty()) {
                return (Body) new Body(List.of()).initHashes(getHashes().getParent());
            }
            var startIndex = getHashes().getIndex(body.get(0));
            List<Statement> newStatements = new ArrayList<>();
            int lastIndex = -1;
            for (Statement statement : other) {
                var hashedStatement = other.getHashes().get(statement);
                if (statement instanceof PartiallyMergeable) {
                    var pm = (PartiallyMergeable<?>) statement;
                    if (getHashes().contains(hashedStatement)) {
                        var index = getHashes().getIndex(hashedStatement);
                        newStatements.addAll(((PartiallyMergeable) get(index - startIndex)).overlap(pm));
                        lastIndex = index;
                    }
                } else if (getHashes().contains(hashedStatement)) {
                    var index = getHashes().getIndex(hashedStatement);
                    if (index > lastIndex) {
                        newStatements.add(get(index - startIndex));
                        lastIndex = index;
                    }
                }
            }
            return (Body) new Body(newStatements).initHashes(getHashes().getParent());
        }

        @Override
        public Statement get(int index) {
            return body.get(index);
        }

        /** returns the first statement if it is a packet call */
        public @Nullable AssignmentStatement getFirstCallAssignment() {
            return body.isEmpty() || !(body.get(0) instanceof AssignmentStatement) ?
                    null : (AssignmentStatement) body.get(0);
        }

        public @Nullable Statement getLastStatement() {
            return isEmpty() ? null : get(size() - 1);
        }

        @Override
        public List<Statement> getSubStatements() {
            return body;
        }

        public Body removeStatements(Set<Statement> statements) {
            return (Body) new Body(body.stream().filter(s -> !statements.contains(s))
                    .map(s -> s instanceof CompoundStatement<?> ?
                            ((CompoundStatement<?>) s).removeStatements(statements) : s)
                    .collect(Collectors.toList())).initHashes(getHashes().getParent());
        }

        public void replaceSource(AssignmentStatement firstStatement, AssignmentStatement newFirstStatement) {
            accept(new RecursiveASTVisitor() {
                @Override
                public void visit(Identifier name) {
                    if (name.hasSource() && name.getSource().equals(firstStatement)) {
                        name.setSource(newFirstStatement);
                    }
                }
            });
        }
    }
}
