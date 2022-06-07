package tunnel.synth.program;

import jdwp.AccessPath;
import jdwp.EventCmds.Events;
import jdwp.EventRequestCmds;
import jdwp.EventRequestCmds.SetRequest.ModifierCommon;
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

        @Setter private boolean isLoopIterableRelated;

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

    static abstract class Statement implements AST {

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
    @AllArgsConstructor
    @EqualsAndHashCode(callSuper = false)
    class PacketCall extends Expression {
        private final String name;
        private final String commandSet;
        private final String command;
        private final List<CallProperty> properties;

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
                var res = create(request.getCommandSetName(), request.getCommandName(), tagged.stream(), List.of());
                return res;
            }
            return create(
                    request.getCommandSetName(),
                    request.getCommandName(),
                    request.asCombined().getTaggedValues(),
                    List.of());
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
    }

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
    class Loop extends Statement implements CompoundStatement<Loop> {
        private final Identifier iter;
        private final Expression iterable;
        private final Body body;

        public Loop(Identifier iter, Expression iterable, List<Statement> body) {
            this(iter, iterable, new Body(body));
        }

        public Loop(Identifier iter, Expression iterable, Body body) {
            this.iter = iter;
            this.iterable = iterable;
            this.body = body;
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

        public List<Loop> merge(ProgramHashes hashes, ProgramHashes otherHashes, Loop other) {
            if (hashes.get(this).equals(otherHashes.get(other))) {
                return List.of(new Loop(iter, iterable, body.merge(hashes, otherHashes, other.body)));
            }
            return List.of(this, other);
        }

        public List<Loop> overlap(ProgramHashes hashes, ProgramHashes otherHashes, Loop other) {
            if (hashes.get(this).equals(otherHashes.get(other))) {
                var newBody = body.overlap(hashes, otherHashes, other.body);
                if (newBody.size() > 0) {
                    return List.of(new Loop(iter, iterable, newBody));
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
            return new Loop(iter, iterable, body.removeStatements(statements));
        }

        @Override
        public List<Expression> getSubExpressions() {
            return List.of(iter, iterable);
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
                    .collect(Collectors.joining("\n"));
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
            return (o instanceof Body) && ((Body) o).body.equals(body);
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
        public Body merge(ProgramHashes hashes, ProgramHashes otherHashes, Body other) {
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
                        newStatements.addAll(((Loop) statement).merge(hashes, otherHashes, (Loop) other.body.get(sIndex)));
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

        public Body overlap(ProgramHashes hashes, ProgramHashes otherHashes, Body other) {
            if (isEmpty() || other.isEmpty()) {
                return new Body(List.of());
            }
            var startIndex = hashes.getIndex(body.get(0));
            List<Statement> newStatements = new ArrayList<>();
            int lastIndex = -1;
            for (Statement statement : other) {
                var hashedStatement = otherHashes.get(statement);
                if (statement instanceof Loop) {
                    var loop = (Loop) statement;
                    if (hashes.contains(hashedStatement)) {
                        var index = hashes.getIndex(hashedStatement);
                        newStatements.addAll(((Loop) get(index - startIndex)).overlap(hashes, otherHashes, loop));
                        lastIndex = index;
                    }
                } else if (hashes.contains(hashedStatement)) {
                    var index = hashes.getIndex(hashedStatement);
                    if (index > lastIndex) {
                        newStatements.add(get(index - startIndex));
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
            return new Body(body.stream().filter(s -> !statements.contains(s))
                    .map(s -> s instanceof CompoundStatement<?> ?
                            ((CompoundStatement<?>) s).removeStatements(statements) : s)
                    .collect(Collectors.toList()));
        }
    }
}
