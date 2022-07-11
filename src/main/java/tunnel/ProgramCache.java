package tunnel;

import ch.qos.logback.classic.Logger;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jdwp.EventCmds.Events;
import jdwp.ParsedPacket;
import jdwp.Request;
import org.slf4j.LoggerFactory;
import tunnel.synth.program.AST.EventsCall;
import tunnel.synth.program.AST.PacketCall;
import tunnel.synth.program.AST.RequestCall;
import tunnel.synth.program.Program;

import java.io.*;
import java.util.Collection;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static jdwp.util.Pair.p;

/**
 * Similar to {@link tunnel.synth.ProgramCollection} with the aim to cache the most recent program for a given
 * cause
 */
public class ProgramCache implements Consumer<Program> {

    public final static Logger LOG = (Logger) LoggerFactory.getLogger("ProgramCache");
    public enum Mode {
        /**
         * always use the last cached program
         */
        LAST,
        /**
         * Merge programs for the same cause
         */
        MERGE
    }

    private final Mode mode;
    private final Cache<PacketCall, Program> causeToProgram;
    private final Cache<Program, Program> originForSimilars;
    private Set<Program> removedSimilars;

    public static final int DEFAULT_MIN_SIZE = 2;

    public static final int DEFAULT_MAX_CACHE_SIZE = 200;

    public static final float DEFAULT_MAX_COST_FOR_SIMILAR = 2;

    /**
     * minimal number assignments in a program to be added, event causes are included
     */
    private final int minSize;

    private final int maxCacheSize;

    /** only include statements in programs for getSimilar if their cost is <= this value (in milli seconds)*/
    private final float maxCostForSimilar;

    public ProgramCache() {
        this(Mode.LAST, DEFAULT_MIN_SIZE);
    }

    public ProgramCache(Mode mode, int minSize) {
        this(mode, minSize, DEFAULT_MAX_CACHE_SIZE, DEFAULT_MAX_COST_FOR_SIMILAR);
    }
    public ProgramCache(Mode mode, int minSize, int maxCacheSize, float maxCostForSimilar) {
        this.maxCacheSize = maxCacheSize;
        this.mode = mode;
        this.minSize = minSize;
        this.maxCostForSimilar = maxCostForSimilar;
        this.causeToProgram = CacheBuilder.newBuilder().maximumSize(maxCacheSize).build();
        this.originForSimilars = CacheBuilder.newBuilder().maximumSize(maxCacheSize * 2L).build();
        this.removedSimilars = new HashSet<>();
    }

    @Override
    public void accept(Program program) {
        if (isAcceptableProgram(program)) {
            add(program);
            originForSimilars.invalidate(program); // ignore equivalent similars
            removedSimilars.remove(program);
        }
    }

    public boolean isAcceptableProgram(Program program) {
        return program.getNumberOfDistinctCalls() >= minSize;
    }

    private void add(Program program) {
        assert program.getFirstCallAssignment() != null;
        var expression = program.getFirstCallAssignment().getExpression();
        assert expression instanceof PacketCall;
        var oldProgram = causeToProgram.getIfPresent((PacketCall) expression);
        var newProgram = program;
        if (mode == Mode.MERGE && oldProgram != null) {
            newProgram = oldProgram.merge(program);
        }
        causeToProgram.put((PacketCall) expression, newProgram);
    }

    private Optional<Program> get(PacketCall packetCall) {
        return Optional.ofNullable(causeToProgram.getIfPresent(packetCall));
    }

    public Optional<Program> get(Request<?> request) {
        return get(RequestCall.create(request));
    }

    public Optional<Program> get(Events events) {
        return get(EventsCall.create(events));
    }

    public Optional<Program> get(ParsedPacket packet) {
        if (packet instanceof Events) {
            return get((Events) packet);
        }
        if (packet instanceof Request<?>) {
            return get((Request<?>) packet);
        }
        return Optional.empty();
    }

    /**
     * returns the program with the most similar cause and alters the obtained program so that the cause
     * (and if needed the first statement) matches the given packet
     *
     * @see PacketCall#computeSimilarity(PacketCall)
     */
    public Optional<Program> getSimilar(PacketCall packet) {
        var best = causeToProgram.asMap().entrySet().stream()
                .map(e -> p(e, e.getKey().computeSimilarity(packet)))
                .max((p1, p2) -> Float.compare(p1.second, p2.second));
        if (best.isPresent() && best.get().second > 0) {
            var origin = best.get().first.getValue();
            Program prog;
            try {
                prog = origin.setCause(packet);
            } catch (AssertionError e) {
                LOG.debug("Failed to set cause " + packet + " for " + origin);
                return Optional.empty();
            }
            // remove statements that are deemed to be to costly
            /*prog = prog.removeStatementsTransitively(s -> s instanceof AssignmentStatement &&
                    ((AssignmentStatement) s).getExpression() instanceof RequestCall &&
                    ((RequestCall) ((AssignmentStatement) s).getExpression()).getCost() > maxCostForSimilar);*/
            if (removedSimilars.contains(origin)) { // this similar program already failed
                return Optional.empty();
            }
            addSimilar(origin, prog);
            return Optional.of(prog);
        }
        return Optional.empty();
    }

    public Optional<Program> getSimilar(ParsedPacket packet) {
        return getSimilar(packet instanceof Events ?
                EventsCall.create((Events) packet) : RequestCall.create((Request<?>) packet));
    }

    /**
     * is this program only a similar program and not a synthesized one?
     */
    private boolean isSimilar(Program program) {
        return originForSimilars.asMap().containsKey(program);
    }

    private void addSimilar(Program origin, Program program) {
        originForSimilars.put(origin, program);
    }

    public int size() {
        return (int) causeToProgram.size();
    }

    public static class DisabledProgramCache extends ProgramCache {
        @Override
        public void accept(Program program) {
        }
    }

    public int load(InputStream stream) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(stream));
        String line;
        int count = 0;
        while ((line = reader.readLine()) != null) {
            StringBuilder program = new StringBuilder(line);
            while ((line = reader.readLine()) != null && !line.isBlank()) {
                program.append(line);
            }
            accept(Program.parse(program.toString()));
            count++;
        }
        return count;
    }

    /**
     * Write the whole map into a stream, but only include statements
     * that do not depend on direct pointers written directly in the program.
     * Skip programs that only consist of such statements or whose cause is such a statement
     */
    public void store(OutputStream stream) throws IOException {
        var writer = new OutputStreamWriter(stream);
        for (Program program : causeToProgram.asMap().values()) {
            var filtered = program.removeDirectPointerRelatedStatementsTransitively();
            if (!filtered.hasCause() || filtered.getNumberOfDistinctCalls() == 0) {
                continue;
            }
            writer.write(filtered.toPrettyString());
            writer.write("\n\n");
        }
        writer.flush();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ProgramCache &&
                causeToProgram.asMap().equals(((ProgramCache) obj).causeToProgram.asMap());
    }

    @Override
    public String toString() {
        return causeToProgram.asMap().toString();
    }

    @Override
    public int hashCode() {
        return causeToProgram.asMap().hashCode();
    }


    /**
     * Returns all programs with an event cause
     */
    public Collection<Program> getServerPrograms() {
        return causeToProgram.asMap().values().stream().filter(Program::isServerProgram).collect(Collectors.toList());
    }

    /**
     * Returns all programs with a request cause
     */
    public Collection<Program> getClientPrograms() {
        return causeToProgram.asMap().values().stream().filter(Program::isClientProgram).collect(Collectors.toList());
    }

    /**
     * programs should be removed if their execution was unsuccessful
     */
    public void remove(Program program) {
        if (program.getFirstCallAssignment() != null) {
            causeToProgram.invalidate((PacketCall) program.getFirstCallAssignment().getExpression());
            if (isSimilar(program)) {
                removedSimilars.add(program);
                if (removedSimilars.size() > maxCacheSize * 10) {
                    removedSimilars = removedSimilars.stream().limit(maxCacheSize * 5L)
                            .collect(Collectors.toCollection(HashSet::new));
                }
            }
        }
    }

    public void clear() {
        causeToProgram.invalidateAll();
        originForSimilars.invalidateAll();
        removedSimilars.clear();
    }
}
