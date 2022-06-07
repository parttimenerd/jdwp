package tunnel;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jdwp.EventCmds.Events;
import jdwp.ParsedPacket;
import jdwp.Request;
import tunnel.synth.program.AST.EventsCall;
import tunnel.synth.program.AST.PacketCall;
import tunnel.synth.program.AST.RequestCall;
import tunnel.synth.program.Program;

import java.io.*;
import java.util.Optional;
import java.util.function.Consumer;

import static jdwp.util.Pair.p;

/**
 * Similar to {@link tunnel.synth.ProgramCollection} with the aim to cache the most recent program for a given
 * cause
 */
public class ProgramCache implements Consumer<Program> {

    public enum Mode {
        /** always use the last cached program */
        LAST  // we currently only support this mode, it is the simplest
        // a possible mode would be to merge the last 5 cached programs
        // or to look for other events with nearby locations for events
    }

    private final Mode mode;
    private final Cache<PacketCall, Program> causeToProgram;

    public static final int DEFAULT_MIN_SIZE = 2;

    public static final int DEFAULT_MAX_CACHE_SIZE = 200;

    /** minimal number assignments in a program to be added, event causes are included */
    private final int minSize;

    private final int maxCacheSize;

    public ProgramCache() {
        this(Mode.LAST, DEFAULT_MIN_SIZE);
    }

    public ProgramCache(Mode mode, int minSize) {
        this(mode, minSize, DEFAULT_MAX_CACHE_SIZE);
    }
    public ProgramCache(Mode mode, int minSize, int maxCacheSize) {
        this.maxCacheSize = maxCacheSize;
        this.mode = mode;
        this.minSize = minSize;
        this.causeToProgram = CacheBuilder.newBuilder().maximumSize(maxCacheSize).build();
        assert mode == Mode.LAST;
    }

    @Override
    public void accept(Program program) {
        if (program.getNumberOfDistinctCalls() >= minSize) {
            add(program);
        }
    }

    private void add(Program program) {
        assert program.getFirstCallAssignment() != null;
        var expression = program.getFirstCallAssignment().getExpression();
        assert expression instanceof PacketCall;
        causeToProgram.put((PacketCall) expression, program);
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
     * returns the program with the most similar cause
     *
     * @see PacketCall#computeSimilarity(PacketCall)
     */
    public Optional<Program> getSimilar(PacketCall packet) {
        var best = causeToProgram.asMap().entrySet().stream()
                .map(e -> p(e, e.getKey().computeSimilarity(packet)))
                .max((p1, p2) -> Float.compare(p1.second, p2.second));
        if (best.isPresent() && best.get().second > 0) {
            return Optional.of(best.get().first.getValue());
        }
        return Optional.empty();
    }

    public Optional<Program> getSimilar(ParsedPacket packet) {
        return getSimilar(packet instanceof Events ?
                EventsCall.create((Events) packet) : RequestCall.create((Request<?>) packet));
    }

    public int size() {
        return (int)causeToProgram.size();
    }

    public static class DisabledProgramCache extends ProgramCache {
        @Override
        public void accept(Program program) {
        }
    }

    public int load(InputStream stream) throws IOException {
        var reader = new BufferedReader(new InputStreamReader(stream));
        String line = null;
        int count = 0;
        while ((line = reader.readLine()) != null) {
            StringBuffer program = new StringBuffer(line);
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
                causeToProgram.asMap().equals(((ProgramCache)obj).causeToProgram.asMap());
    }

    @Override
    public String toString() {
        return causeToProgram.asMap().toString();
    }

    @Override
    public int hashCode() {
        return causeToProgram.asMap().hashCode();
    }
}
