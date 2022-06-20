package tunnel.synth;

import jdwp.JDWP;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.program.AST.*;
import tunnel.synth.program.Program;
import tunnel.synth.program.Visitors.ReturningExpressionVisitor;
import tunnel.synth.program.Visitors.ReturningStatementVisitor;
import tunnel.synth.program.Visitors.StatementVisitor;
import tunnel.util.Hashed;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Compute and store hashes for every statement in a program.
 * It is useful for structurally merging and overlapping two programs.
 *
 * Assumes that the program does not change after the analysis.
 *
 * Its hashes can be used heuristically (the collision probability of the used 64bit hashes is fairly low,
 * especially as the 16bits encode the command set and command, using the command set 0 to encode non request
 * statements).
 * Use the {@link Hashed} objects to use these hashes for all comparisons (and hashing).
 * Loops only consider there headers
 *
 * For complex statements (e.g. switch, case, loop) the hashes describe only their headers.
 */
@Getter
public class ProgramHashes extends AbstractSet<Hashed<Statement>> {

    // command values for the hashes non request assignment statements
    private final byte LOOP = 0;
    private final byte PRIMITIVE = 1;
    private final byte FUNCTION_CALL = 2;
    private final byte MAP_CALL = 3;
    private final byte SWITCH = 4;
    private final byte CASE = 5;
    private final byte BODY = 6;
    private final byte RECURSION = 7;
    private final byte REC_CALL = 8;
    private final byte OTHER = 100;

    private final ProgramHashes parent;
    private final Map<Statement, Hashed<Statement>> statementToHashed;
    private final Map<Hashed<Statement>, Statement> hashedToStatement;
    private final Map<Hashed<Statement>, Integer> hashedToIndex;

    public ProgramHashes() {
        this(null);
    }

    public ProgramHashes(@Nullable ProgramHashes parent) {
        this.statementToHashed = new HashMap<>();
        this.hashedToStatement = new HashMap<>();
        this.hashedToIndex = new HashMap<>();
        this.parent = parent;
    }

    @Override
    public Iterator<Hashed<Statement>> iterator() {
        return hashedToIndex.keySet().iterator();
    }

    @Override
    public int size() {
        return hashedToIndex.size();
    }

    public Hashed<Statement> get(Statement statement) {
        if (!statementToHashed.containsKey(statement)) {
            throw new IllegalArgumentException(String.format("Statement %s not found", statement));
        }
        return Objects.requireNonNull(statementToHashed.get(statement));
    }

    public Hashed<Statement> getOrCompute(Statement statement) {
        if (!statementToHashed.containsKey(statement)) {
            return create(statement);
        }
        return Objects.requireNonNull(statementToHashed.get(statement));
    }

    private Hashed<Statement> getOrParent(Statement statement) {
        if (!statementToHashed.containsKey(statement)) {
            if (parent != null) {
                return parent.getOrParent(statement);
            }
            throw new IllegalArgumentException(String.format("Statement %s not found", statement));
        }
        return Objects.requireNonNull(statementToHashed.get(statement));
    }

    /**
     * Used for reporting collisions, a collision happened if the hashed objects are equal, but the objects are not.
     * This works only if both statement are in the same programs.
     */
    private Hashed<Statement> getCollision(Hashed<Statement> hashed) {
        return hashedToIndex.keySet().stream()
                .filter(h -> h.hashCode() == hashed.hashCode() && h.equals(hashed)).findFirst().get();
    }

    public Statement get(Hashed<Statement> hashed) {
        return hashedToStatement.get(hashed);
    }

    public int getIndex(Hashed<Statement> hashed) {
        return hashedToIndex.get(hashed);
    }

    /**
     * approximately
     */
    public int getIndex(Statement statement) {
        return getIndex(get(statement));
    }

    /** does this collection contain the exact same statement? */
    public boolean contains(Statement statement) {
        return statementToHashed.containsKey(statement);
    }

    /** does this collection contain the hashed statement disregarding variable names and only using hashes? */

    public boolean contains(Hashed<Statement> statement) {
        return hashedToIndex.containsKey(statement);
    }

    @Override
    @SuppressWarnings("unchecked")
    public boolean contains(Object o) {
        return o instanceof Hashed ? contains((Hashed<Statement>) o) :
                (o instanceof Statement && contains((Statement) o));
    }

    private void add(Hashed<Statement> hashed, int index) {
        if (contains(hashed) && !contains(hashed.get())) {
            throw new AssertionError(String.format("hash collision: %s (%d) and %s (%d), " +
                            "maybe two similar statements in the same program", hashed.get(), hashed.hash(),
                    getCollision(hashed).get(), hashed.hash()));
        }
        statementToHashed.put(hashed.get(), hashed);
        hashedToStatement.put(hashed, hashed.get());
        hashedToIndex.put(hashed, index);
    }

    public long get(Identifier name) {
        return name.isGlobalVariable() ? name.hashCode() : get(name.getSource()).hash();
    }

    public long getOrParent(Identifier name) {
        return name.isGlobalVariable() ? name.hashCode() : getOrParent(name.getSource()).hash();
    }

    private long hash(Expression expression) {
        return expression.accept(new ReturningExpressionVisitor<>() {
            @Override
            public Long visit(Identifier name) {
                return getOrParent(name);
            }

            @Override
            public Long visit(Literal<?> literal) {
                return (long)literal.hashCode();
            }

            @Override
            public Long visit(FunctionCall call) {
                return Hashed.hash(call.getFunctionName().hashCode(), collectSubExpressionHashes(call));
            }

            @Override
            public Long visit(CallProperty property) {
                return Hashed.hash(property.getPath().hashCode(), property.getAccessor().accept(this));
            }

            @Override
            public Long visit(Expression expression) {
                return collectSubExpressionHashes(expression);
            }

            private long collectSubExpressionHashes(Expression expression) {
                return Hashed.hash(expression.getSubExpressions().stream().mapToLong(e -> e.accept(this)).toArray());
            }
        });
    }

    /** create but do not add */
    Hashed<Statement> create(Statement statement) {
        return statement.accept(new ReturningStatementVisitor<>() {
            @Override
            public Hashed<Statement> visit(AssignmentStatement assignment) {
                Hashed<Statement> hashed =  assignment.getExpression().accept(new ReturningExpressionVisitor<>() {

                    private long[] computeChildHashes(Expression expression) {
                        return expression.getSubExpressions().stream().mapToLong(ProgramHashes.this::hash).toArray();
                    }

                    @Override
                    public Hashed<Statement> visit(RequestCall request) {
                        byte commandSet = JDWP.getCommandSetByte(request.getCommandSet());
                        byte command = JDWP.getCommandByte(request.getCommandSet(), request.getCommand());
                        return Hashed.create(assignment, commandSet, command, computeChildHashes(request));
                    }

                    @Override
                    public Hashed<Statement> visit(FunctionCall call) {
                        return Hashed.create(assignment, (byte)0, FUNCTION_CALL, hash(call));
                    }

                    @Override
                    public Hashed<Statement> visit(Primitive primitive) {
                        return Hashed.create(assignment, (byte)0, PRIMITIVE, hash(primitive));
                    }

                    @Override
                    public Hashed<Statement> visit(Expression expression) {
                        return Hashed.create(assignment, (byte)0, OTHER, hash(expression));
                    }
                });
                if (assignment.isCause()) {
                    return new Hashed<>(Hashed.hash(0, hashed.hash()), statement);
                }
                return hashed;
            }

            @Override
            public Hashed<Statement> visit(Loop loop) {
                return Hashed.create(loop, 0, LOOP, hash(loop.getIterable()));
            }

            @Override
            public Hashed<Statement> visit(MapCallStatement mapCall) {
                return Hashed.create(mapCall, 0, MAP_CALL, hash(mapCall.getIterable()));
            }

            @Override
            public Hashed<Statement> visit(SwitchStatement switchStatement) {
                return Hashed.create(switchStatement, 0, SWITCH, hash(switchStatement.getExpression()));
            }

            @Override
            public Hashed<Statement> visit(CaseStatement caseStatement) {
                return Hashed.create(caseStatement, 0, CASE, hash(caseStatement.getExpression()));
            }

            @Override
            public Hashed<Statement> visit(Recursion recursion) {
                return Hashed.create(recursion, 0, RECURSION, hash(recursion.getRequest()));
            }

            @Override
            public Hashed<Statement> visit(RecRequestCall recCall) {
                return Hashed.create(recCall, 0, REC_CALL,
                        recCall.getName().getSource() != null ? recCall.getName().getSource().accept(this).hash() : 0);
            }
        });
    }

    public static void setInProgram(Program program) {
        setInStatement(null, program);
    }

    public static void setInStatement(@Nullable ProgramHashes parentHashes, Statement statement) {
        ProgramHashes hashes = new ProgramHashes(parentHashes);
        var visitor = new StatementVisitor() {
            @Override
            public void visit(Statement statement) {
                hashes.add(hashes.create(statement), hashes.size());
                setInStatement(hashes, statement);
                statement.getHashes().add(hashes.get(statement), 0);
            }

            @Override
            public void visit(Body body) {
                body.forEach(s -> s.accept(this));
                body.setHashes(hashes);
            }
        };
        try {
            if (statement instanceof Program) {
                var program = (Program) statement;
                if (program.hasCause()) {
                    hashes.add(hashes.create(program.getCauseStatement()), hashes.size());
                }
                program.getBody().accept(visitor);
            } else {
                statement.getSubStatements().forEach(s -> s.accept(visitor));
            }
        } catch (IllegalArgumentException e) {
            throw new AssertionError("problem with statement " + statement, e);
        }
        if (parentHashes != null && !(statement instanceof Body)) {
            hashes.add(parentHashes.create(statement), 0);
        }
        statement.setHashes(hashes);
    }

    @Override
    public String toString() {
        return String.format("{%s}", statementToHashed.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().hash()).collect(Collectors.joining(", ")));
    }
}