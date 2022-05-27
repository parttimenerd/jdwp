package tunnel.synth;

import jdwp.JDWP;
import lombok.Getter;
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
 * especially as the 16bits encode the command set and command, using the command set 0 to encode non request statements).
 * Use the {@link Hashed} objects to use these hashes for all comparisons (and hashing).
 * Loops only consider there headers
 *
 * The indexes are equivalent to the line number in the linearized programs (with for loop headers taking one position).
 */
@Getter
public class ProgramHashes extends AbstractSet<Hashed<Statement>> {

    // command values for the hashes non request assignment statements
    private final byte LOOP = 0;
    private final byte PRIMITIVE = 1;
    private final byte FUNCTION_CALL = 2;
    private final byte OTHER = 3;

    private final Map<Statement, Hashed<Statement>> statementToHashed;
    private final Map<Hashed<Statement>, Integer> hashedToIndex;

    public ProgramHashes() {
        this.statementToHashed = new HashMap<>();
        this.hashedToIndex = new HashMap<>();
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
        return Objects.requireNonNull(statementToHashed.get(statement));
    }

    /**
     * Used for reporting collisions, a collision happened if the hashed objects are equal, but the objects are not.
     * This works only if both statement are in the same programs.
     */
    private Hashed<Statement> get(Hashed<Statement> hashed) {
        return hashedToIndex.keySet().stream()
                .filter(h -> h.hashCode() == hashed.hashCode() && h.equals(hashed)).findFirst().get();
    }

    public int getIndex(Hashed<Statement> hashed) {
        return hashedToIndex.get(hashed);
    }

    /** approximately */
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
                    get(hashed).get(), hashed.hash()));
        }
        statementToHashed.put(hashed.get(), hashed);
        hashedToIndex.put(hashed, index);
    }

    public long get(Identifier name) {
        return name.isGlobalVariable() ? name.hashCode() : get(name.getSource()).hash();
    }

    private long hash(Expression expression) {
        return expression.accept(new ReturningExpressionVisitor<>() {
            @Override
            public Long visit(Identifier name) {
                return get(name);
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
                return assignment.getExpression().accept(new ReturningExpressionVisitor<>() {

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
            }

            @Override
            public Hashed<Statement> visit(Loop loop) {
                return Hashed.create(loop, 0, LOOP, hash(loop.getIterable()));
            }
        });
    }

    public static ProgramHashes create(Program program) {
        ProgramHashes hashes = new ProgramHashes();
        program.getBody().forEach(s -> s.accept(new StatementVisitor() {
            @Override
            public void visit(Statement statement) {
                hashes.add(hashes.create(statement), hashes.size());
            }

            @Override
            public void visit(Loop loop) {
                hashes.add(hashes.create(loop), hashes.size());
                loop.getBody().forEach(s -> s.accept(this));
            }
        }));
        return hashes;
    }

    @Override
    public String toString() {
        return String.format("{%s}", statementToHashed.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue().hash()).collect(Collectors.joining(", ")));
    }
}