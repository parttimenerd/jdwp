package tunnel;

import ch.qos.logback.classic.Logger;
import jdwp.AccessPath;
import jdwp.EventCmds.Events;
import jdwp.ParsedPacket;
import jdwp.Request;
import jdwp.exception.TunnelException.MergeException;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.slf4j.LoggerFactory;
import tunnel.synth.DependencyGraph;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.Synthesizer;
import tunnel.synth.program.AST.*;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Program;
import tunnel.util.Util;

import java.io.*;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * A cache for programs that merges programs with similar causes
 */
public class ProgramCache implements Consumer<Program> {

    public final static Logger LOG = (Logger) LoggerFactory.getLogger("ProgramCache");

    @Getter
    @AllArgsConstructor
    static class AccessPathKey {

        private final String group;
        private final List<Object> pathValues;


        @Override
        public boolean equals(Object obj) {
            return obj instanceof AccessPathKey &&
                    ((AccessPathKey) obj).pathValues.equals(pathValues) &&
                    ((AccessPathKey) obj).group.equals(group);
        }

        @Override
        public int hashCode() {
            return Objects.hash(group, Objects.hash(pathValues.toArray()));
        }

        public int size() {
            return pathValues.size();
        }

        public AccessPathKey dropLast() {
            return new AccessPathKey(group, pathValues.subList(0, pathValues.size() - 1));
        }

        @Override
        public String toString() {
            return group + ":" + pathValues.stream().map(Object::toString).collect(Collectors.joining("."));
        }

        /**
         * returns the key for a given packet call, considers only the first event if multiple are given,
         * if no KeyGroup is specific in spec, then the key group is related to the call type
         */
        public static AccessPathKey from(PacketCall call) {
            if (call instanceof EventsCall) {
                var events = (EventsCall) call;
                var propsOfFirstEvent = events.getPropertiesOfEvent(0, true);
                var metadata = events.getMetadata(0);
                assert metadata != null;
                String keyGroup = metadata.getKeyGroup();
                if (metadata.getKeyGroup().isEmpty()) {
                    keyGroup = metadata.getCommandSet() + "." + metadata.getCommand();
                }
                return new AccessPathKey(keyGroup, metadata.getKeyPath().stream()
                        .map(p -> propsOfFirstEvent.stream().filter(c -> c.getPath().equals(p)).findFirst().get())
                        .collect(Collectors.toList()));
            } else {
                var req = (RequestCall) call;
                var metadata = req.getMetadata();
                String keyGroup = metadata.getKeyGroup();
                if (metadata.getKeyGroup().isEmpty()) {
                    keyGroup = metadata.getCommandSet() + "." + metadata.getCommand();
                }
                return new AccessPathKey(keyGroup, metadata.getKeyPath().stream()
                        .map(p -> req.getProperties().stream().filter(c -> c.getPath().equals(p)).findFirst().get())
                        .collect(Collectors.toList()));
            }
        }
    }

    private final Map<AccessPathKey, Program> groupToAccess;
    /**
     * the base programs for every group
     */
    private final Map<String, Program> groupToProgram;
    private final Map<PacketCall, Program> packetCallToProgram;
    private final Map<Program, PacketCall> programToPacketCall;
    private final Map<Integer, List<AccessPathKey>> sizeToGroupAndAccess;

    private final LinkedBlockingQueue<Program> toAdd;

    public static final int DEFAULT_MIN_SIZE = 2;

    public static final int DEFAULT_MAX_CACHE_SIZE = 500;


    /**
     * minimal number assignments in a program to be added, event causes are included
     */
    private final int minSize;

    private final int maxCacheSize;

    private final boolean addAsynchronously;

    public ProgramCache() {
        this(DEFAULT_MIN_SIZE);
    }

    public ProgramCache(int minSize) {
        this(minSize, DEFAULT_MAX_CACHE_SIZE);
    }

    public ProgramCache(int minSize, int maxCacheSize) {
        this(minSize, maxCacheSize, true);
    }

    public ProgramCache(int minSize, int maxCacheSize, boolean addAsynchronously) {
        this.maxCacheSize = maxCacheSize;
        this.minSize = minSize;
        this.groupToAccess = new HashMap<>();
        this.groupToProgram = new HashMap<>();
        this.packetCallToProgram = new HashMap<>();
        this.programToPacketCall = new HashMap<>();
        this.sizeToGroupAndAccess = new HashMap<>();
        this.toAdd = new LinkedBlockingQueue<>();
        this.addAsynchronously = addAsynchronously;
    }

    @Override
    public void accept(Program program) {
        if (isAcceptableProgram(program)) {
            if (addAsynchronously) {
                addAsynchronously(program);
            } else {
                add(program);
            }
        }
    }

    public void accept(Partition partition) {
        for (DependencyGraph dependencyGraph : DependencyGraph.compute(partition).splitOnCause()) {
            accept(Synthesizer.synthesizeProgram(dependencyGraph));
        }
    }

    public void addAsynchronously(Program program) {
        toAdd.add(program);
    }

    public boolean isAcceptableProgram(Program program) {
        return program.getNumberOfDistinctCalls() >= minSize;
    }

    private void addFromAsync() {
        if (toAdd.isEmpty()) {
            return;
        }
        List<Program> added = new ArrayList<>();
        toAdd.drainTo(added);
        added.forEach(this::add);
    }

    private void add(Program program) {
        assert program.getFirstCallAssignment() != null;
        var packetCall = (PacketCall) program.getFirstCallAssignment().getExpression();
        var key = AccessPathKey.from(packetCall);
        programToPacketCall.put(program, packetCall);
        packetCallToProgram.put(packetCall, program);
        while (key.size() >= 0) {
            if (key.size() == 0) {
                if (groupToProgram.containsKey(key.getGroup())) {
                    var oldProgram = groupToProgram.get(key.getGroup());
                    var mergedProgram = merge(oldProgram, program);
                    if (!oldProgram.equals(mergedProgram)) {
                        groupToProgram.put(key.getGroup(), mergedProgram);
                    }
                } else {
                    groupToProgram.put(key.getGroup(), program);
                }
                break;
            }
            if (groupToAccess.containsKey(key)) {
                var oldProgram = groupToAccess.get(key);
                Program mergedProgram = merge(oldProgram, program);
                if (!oldProgram.equals(mergedProgram)) {
                    groupToAccess.put(key, mergedProgram);
                } else {
                    break; // nothing changed
                }
            } else {
                groupToAccess.put(key, program);
            }
            key = key.dropLast();
        }
    }

    private static Program merge(Program old, Program other) {
        if (old.equals(other)) {
            return old;
        }
        try {
            return roundTrip(old).merge(roundTrip(other)); // TODO: pretty printing and parsing works, but could be
            // improved
        } catch (Exception e) {
            LOG.error("Could not merge programs \n{}\nand\n{}", old.toPrettyString(), other.toPrettyString(), e);
            throw new MergeException(String.format("Could not merge programs \n%s\nand\n%s", old.toPrettyString(),
                    other.toPrettyString()), e);
        }
    }

    private static Program roundTrip(Program program) {
        return Program.parse(program.toString());
    }

    public Optional<Program> get(PacketCall packetCall) {
        return get(AccessPathKey.from(packetCall), packetCall);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Optional<Program> get(AccessPathKey key, PacketCall packetCall) {
        addFromAsync();
        var metadata = packetCall.getMetadata();
        if (metadata.getSplitGraphAt().length() > 0) {
            var splitGraphAt = new AccessPath(metadata.getSplitGraphAt());
            var props = packetCall.getPropertiesForListPath(splitGraphAt);
            if (props.size() > 1) {
                var nonListPathProps =
                        packetCall.getProperties().stream().filter(p -> !p.getPath().startsWith(splitGraphAt)).collect(Collectors.toList());
                List<Statement> statements = new ArrayList<>();
                for (Integer index : props.keySet()) {
                    var path = splitGraphAt.append(index);
                    var indexProps = Util.combine(nonListPathProps,
                            props.get(index).stream()
                                    .map(p -> new CallProperty(p.getPath().replace(splitGraphAt.size(), 0),
                                            p.getAccessor())).collect(Collectors.toList()));
                    var indexPacketCall = packetCall instanceof RequestCall ?
                            new RequestCall(packetCall.getCommandSet(), packetCall.getCommand(), indexProps) :
                            new EventsCall(packetCall.getCommandSet(), packetCall.getCommand(), indexProps);
                    var indexProgram = get(indexPacketCall);
                    if (indexProgram.isEmpty()) {
                        continue;
                    }
                    var causeAssignment = new AssignmentStatement(new Identifier("cause"),
                            new FunctionCall(Functions.OBJECT, (List<Expression>) (List) indexProps));
                    Body body = indexProgram.get().getBody()
                            .replaceIdentifiersConv(identifier -> identifier.getName().equals("cause") ?
                                    causeAssignment.getVariable() : identifier);
                    var newBody = new Body(Util.combine(List.of(causeAssignment), body.getSubStatements()));
                    statements.add(new SwitchStatement(new IntegerLiteral((long) (int) index),
                            List.of(new CaseStatement(null, newBody))));
                }
                if (statements.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new Program(statements));
            }
        }
        if (packetCallToProgram.containsKey(packetCall)) {
            return Optional.of(packetCallToProgram.get(packetCall));
        }
        if (groupToAccess.containsKey(key)) {
            return Optional.ofNullable(groupToProgram.get(key.group).setCause(packetCall));
        }
        if (key.size() > 0) {
            // we try with one less specific path
            return get(key.dropLast(), packetCall);
        }
        return Optional.ofNullable(groupToProgram.get(key.group)).map(p -> p.setCause(packetCall));
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

    public int size() {
        return packetCallToProgram.size();
    }

    public void tick() {
        addFromAsync();
        if (size() > maxCacheSize) {
            reduceSize((int) (maxCacheSize * 0.9));
        }
    }

    private void reduceSize(int maxSize) {
        var accessPathsSorted =
                sizeToGroupAndAccess.keySet().stream().sorted(Comparator.comparing(e -> (int) e).reversed())
                        .flatMap(e -> sizeToGroupAndAccess.get(e).stream())
                        .collect(Collectors.toList());
        for (AccessPathKey accessPathKey : accessPathsSorted) {
            if (size() <= maxSize) {
                break;
            }
            var program = groupToProgram.get(accessPathKey.group);
            if (programToPacketCall.containsKey(program)) {
                var packetCall = programToPacketCall.get(program);
                packetCallToProgram.remove(packetCall);
                programToPacketCall.remove(program);
            }
            if (accessPathKey.size() == 0) {
                groupToProgram.remove(accessPathKey.group);
            }
            sizeToGroupAndAccess.get(accessPathKey.size()).remove(accessPathKey);
            groupToAccess.remove(accessPathKey);

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
            var prog = Program.parse(program.toString());
            if (isAcceptableProgram(prog)) {
                add(prog);
                count++;
            }
        }
        return count;
    }

    public void store(OutputStream stream) throws IOException {
        store(stream, 3);
    }

    /**
     * Write the whole map into a stream, but only include statements
     * that do not depend on direct pointers written directly in the program.
     * Skip programs that only consist of such statements or whose cause is such a statement
     */
    public void store(OutputStream stream, int minSizeOfStoredProgram) throws IOException {
        addFromAsync();
        var writer = new OutputStreamWriter(stream);
        for (Program program : packetCallToProgram.values()) {
            var filtered = program.removeDirectPointerRelatedStatementsTransitivelyWithoutCause();
            if (!filtered.hasCause() || filtered.getNumberOfDistinctCalls() < minSizeOfStoredProgram) {
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
                packetCallToProgram.equals(((ProgramCache) obj).packetCallToProgram);
    }

    @Override
    public String toString() {
        return packetCallToProgram.toString();
    }

    @Override
    public int hashCode() {
        return packetCallToProgram.hashCode();
    }

    /**
     * Returns all programs with an event cause
     */
    public Collection<Program> getServerPrograms() {
        return packetCallToProgram.values().stream().filter(Program::isServerProgram).collect(Collectors.toList());
    }

    /**
     * Returns all programs with a request cause
     */
    public Collection<Program> getClientPrograms() {
        return packetCallToProgram.values().stream().filter(Program::isClientProgram).collect(Collectors.toList());
    }

    /**
     * programs should be removed if their execution was unsuccessful
     */
    public void remove(Program program) {
        if (program.getFirstCallAssignment() != null) {
            packetCallToProgram.remove(getPacketCall(program));
        }
    }

    public void clear() {
        packetCallToProgram.clear();
        groupToProgram.clear();
        groupToAccess.clear();
        sizeToGroupAndAccess.clear();
    }

    private static PacketCall getPacketCall(Program program) {
        return (PacketCall) program.getFirstCallAssignment().getExpression();
    }

    public static class DisabledProgramCache extends ProgramCache {
        @Override
        public void accept(Program program) {
        }

        @Override
        public void addAsynchronously(Program program) {
        }

        @Override
        public void tick() {
        }
    }
}
