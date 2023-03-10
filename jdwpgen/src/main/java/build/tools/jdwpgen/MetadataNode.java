package build.tools.jdwpgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.io.PrintWriter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static build.tools.jdwpgen.MetadataNode.StateProperty.EVERYTHING;
import static build.tools.jdwpgen.MetadataNode.StateProperty.NOTHING;

@Getter
public class MetadataNode extends Node {

    private static class DefaultValue<T> {
        final T value;
        final Function<Map<String, Object>, T> function;

        DefaultValue(T value) {
            this.value = value;
            this.function = null;
        }

        DefaultValue(Function<Map<String, Object>, T> function) {
            this.value = null;
            this.function = function;
        }

        Object get() {
            return value != null ? value : function;
        }
    }

    @AllArgsConstructor
    public static class Entry<T> {
        private final String name;
        public final String constantName;

        public final String description;

        public final Class<T> resultType;
        private final Function<String, T> parser;
        private final Optional<DefaultValue<T>> defaultValue;

        public boolean hasDefaultValue() {
            return defaultValue.isPresent();
        }

        public T parse(String value) {
            return parser.apply(value);
        }

        public String getNodeName() {
            return name;
        }

        public String getMethodName() {
            if (resultType == Boolean.class) {
                return CodeGeneration.INSTANCE.lowercaseFirstCharacter(name);
            }
            return "get" + name;
        }

        public TypeName getTypeName() {
            switch (resultType.getSimpleName()) {
                case "Boolean":
                    return TypeName.BOOLEAN;
                case "Integer":
                    return TypeName.INT;
                case "Long":
                    return TypeName.LONG;
                case "String":
                    return TypeName.get(String.class);
                case "Float":
                    return TypeName.FLOAT;
                default:
                    return ClassName.bestGuess(resultType.getSimpleName());
            }
        }

        public TypeName getBoxedTypeName() {
            return ClassName.bestGuess(resultType.getSimpleName());
        }

        public void check(T value, CommandNode cmd) {

        }
    }

    enum StateProperty {
        TIME("Passing time, replies to requests affected by time " +
                "get invalid after a short time due to possible concurrency"),
        EVERYTHING("All properties together"),
        NOTHING("No property"),
        CLASSPATH("Classpath"),
        CLASSLOADERS("Classloaders"),
        MODULES("Modules"),
        CLASSES("Classes"),
        METHODS("Methods"),
        FIELDS("Fields"),
        THREADS("Threads"),
        INSTANCES("Instances"),
        EVENTS("Events"),
        CURRENTSUSPENSION("Current suspension of the current thread"),
        FRAMEVALUES("Values of the current frame"),
        CLASSLOADTIME("Interval between classloads"),
        THREADLOADTIME("Interval between thread name changes and creations"),
        GARBAGECOLLECTIONTIME("Interval between garbage collections");

        public final String description;

        StateProperty(String description) {
            this.description = description;
        }

        @Override
        public String toString() {
            return name().toLowerCase();
        }

        public static StateProperty fromString(String s) {
            return StateProperty.valueOf(s.toUpperCase());
        }

        public static String combinedString() {
            return Arrays.stream(values()).map(StateProperty::toString).collect(Collectors.joining(", "));
        }
    }

    static class StatePropertySet {
        public final Set<StateProperty> properties;

        StatePropertySet(Set<StateProperty> properties) {
            if (properties.contains(EVERYTHING) && properties.size() > 1) {
                throw new IllegalArgumentException("Cannot have EVERYTHING and other properties");
            }
            if (properties.contains(NOTHING) && properties.size() > 1) {
                throw new IllegalArgumentException("Cannot have NOTHING and other properties");
            }
            if (properties.size() == 0) {
                throw new IllegalArgumentException("Must have at least one property");
            }
            this.properties = properties.stream().flatMap(p -> {
                switch (p) {
                    case EVERYTHING:
                        return Arrays.stream(StateProperty.values())
                                .filter(sp -> sp != EVERYTHING && sp != NOTHING);
                    case NOTHING:
                        return Stream.of();
                    default:
                        return Stream.of(p);
                }
            }).collect(Collectors.toSet());
        }

        /**
         * for every state property in the enum (in the order there): 1 if contained, 0 if not
         */
        public long getBitfield() {
            long bits = 0;
            int index = 0;
            for (StateProperty p : StateProperty.values()) {
                if (properties.contains(p)) {
                    bits |= 1L << index;
                }
                index++;
            }
            return bits;
        }
    }

    public static class StatePropertyEntry extends Entry<StatePropertySet> {

        public StatePropertyEntry(String name, String constantName, String description,
                                  Optional<DefaultValue<StatePropertySet>> statePropertyDefaultValue) {
            super(name, constantName, description, StatePropertySet.class,
                    StatePropertyEntry::parseSet, statePropertyDefaultValue);
        }

        static StatePropertySet parseSet(String str) {
            if (str.contains("-")) {
                var parts = str.split("-");
                if (parts.length < 2) {
                    throw new IllegalArgumentException("Invalid state property set: " + str);
                }
                var first = new StatePropertySet(Set.of(StateProperty.fromString(parts[0])));
                var rest = Arrays.stream(parts).skip(1).map(StateProperty::fromString).collect(Collectors.toSet());
                return new StatePropertySet(first.properties.stream().filter(x -> !rest.contains(x)).collect(Collectors.toSet()));
            }
            return new StatePropertySet(Arrays.stream(str.split("[, ]+")).map(StateProperty::fromString).collect(Collectors.toSet()));
        }

        @Override
        public TypeName getTypeName() {
            return ParameterizedTypeName.get(ClassName.get(Set.class), ClassName.bestGuess("StateProperty"));
        }

        @Override
        public TypeName getBoxedTypeName() {
            return getTypeName();
        }
    }

    static class ReplyLikeErrorList {
        public final List<String> errorConstants;

        ReplyLikeErrorList(List<String> errorConstants) {
            this.errorConstants = errorConstants;
        }
    }

    public static class ReplyLikeErrorEntry extends Entry<ReplyLikeErrorList> {

        public ReplyLikeErrorEntry(String name, String constantName, String description,
                                  Optional<DefaultValue<ReplyLikeErrorList>> statePropertyDefaultValue) {
            super(name, constantName, description, ReplyLikeErrorList.class,
                    ReplyLikeErrorEntry::parseSet, statePropertyDefaultValue);
        }

        static ReplyLikeErrorList parseSet(String str) {
            return new ReplyLikeErrorList(Arrays.stream(str.split("[, ]+")).collect(Collectors.toList()));
        }

        @Override
        public TypeName getTypeName() {
            return ParameterizedTypeName.get(ClassName.get(List.class), ClassName.bestGuess("Integer"));
        }

        @Override
        public TypeName getBoxedTypeName() {
            return getTypeName();
        }
    }

    @AllArgsConstructor
    static class PathList {
        public final List<List<String>> strings;
    }

    public static class PathListEntry extends Entry<PathList> {

        public PathListEntry(String name, String constantName, String description,
                                   Optional<DefaultValue<PathList>> statePropertyDefaultValue) {
            super(name, constantName, description, PathList.class,
                    PathListEntry::parsePathList, statePropertyDefaultValue);
        }

        private static PathList parsePathList(String str) {
            return new PathList(Arrays.stream(str.split(" ")).map(PathListEntry::parsePath).collect(Collectors.toList()));
        }

        private static List<String> parsePath(String path) {
            return Arrays.asList(path.split("\\."));
        }

        public PathListEntry(String name, String constantName, String description) {
            this(name, constantName, description, Optional.of(new DefaultValue<>(new PathList(List.of()))));
        }

        @Override
        public TypeName getTypeName() {
            return ParameterizedTypeName.get(ClassName.get(List.class), ClassName.bestGuess("AccessPath"));
        }

        @Override
        public TypeName getBoxedTypeName() {
            return getTypeName();
        }

        @Override
        public void check(PathList value, @Nullable CommandNode cmd) {
            if (cmd == null || cmd.isEventNode()) {
                return;
            }
            var nonListPaths = cmd.getOut().getNonListPaths();
            if (!nonListPaths.containsAll(value.strings)) {
                throw new IllegalArgumentException(String.format("Paths of %s for %s not found: %s", getNodeName(),
                        cmd.getCommandClassName(), value.strings.stream().filter(x -> !nonListPaths.contains(x)).collect(Collectors.toList())));
            }
        }
    }

    static final List<Entry<?>> entries = List.of(
            new Entry<>("OnlyReads", "ONLY_READS",
                    "Can the request be issued multiple times without affecting the JVM from debugger's perpective",
                    Boolean.class, Boolean::parseBoolean, Optional.empty()),
            new Entry<>("Cost", "COST",
                    "The time it takes to execute the request in milliseconds " +
                            "on the machine that the cost.csv file is created on",
                    Float.class, Float::parseFloat, Optional.of(new DefaultValue<>(0f))),
            new Entry<>("InvalidatesReplyCache", "INVALIDATES_REPLY_CACHE",
                    "Whether the reply cache should be invalidated when observing this request",
                    Boolean.class, Boolean::parseBoolean, Optional.of(new DefaultValue<>(map -> !(boolean) map.get(
                    "OnlyReads")))),
            new StatePropertyEntry("AffectedBy", "AFFECTED_BY",
                    String.format("List of state properties (%s) that affect the validity of the reply if they change",
                            StateProperty.combinedString()),
                    Optional.of(new DefaultValue<>(new StatePropertySet(Set.of(EVERYTHING))))),
            new StatePropertyEntry("Affects", "AFFECTS",
                    String.format("List of state properties (%s) that are affected the validity by this request",
                            StateProperty.combinedString()),
                    Optional.of(new DefaultValue<>(m -> new StatePropertySet(Set.of((boolean) m.get("OnlyReads") ?
                            NOTHING : EVERYTHING))))),
            new ReplyLikeErrorEntry("ReplyLikeErrors", "REPLY_LIKE_ERRORS",
                    "List of error constants that are considered to be a reply rather than an error",
                    Optional.of(new DefaultValue<>(new ReplyLikeErrorList(List.of())))),
            new PathListEntry("KeyPath", "KEY_PATH",
                    "list of keys from most specific to least specific, that form a path"),
            new Entry<>("KeyGroup", "KEY_GROUP", "The group that the key belongs to",
                    String.class, s -> s, Optional.of(new DefaultValue<>(""))),
            new Entry<>("SplitGraphAt", "SPLIT_GRAPH_AT", "List valued property on which a cause should be split",
                    String.class, s -> s, Optional.of(new DefaultValue<>(""))),
            new Entry<>("AlwaysSplitPartition", "ALWAYS_SPLIT_PARTITION",
                    "Whether the partition should always be split before the request",
                    Boolean.class, Boolean::parseBoolean, Optional.of(new DefaultValue<>(false))),
            new Entry<>("AlwaysSplitPartitionAfter", "ALWAYS_SPLIT_PARTITION_AFTER",
                    "Whether the partition should always be split after this request",
                    Boolean.class, Boolean::parseBoolean, Optional.of(new DefaultValue<>(false))),
            new Entry<>("IgnoreForPartitioning", "IGNORE_FOR_PARTITIONING",
                    "Ignore for partitioning, the request will not split any partition",
                    Boolean.class, Boolean::parseBoolean, Optional.of(new DefaultValue<>(false)))
    );

    public static final String METADATA_CLASSNAME = "Metadata";

    public static final Map<String, Entry<?>> entryMap = entries.stream().collect(Collectors.toMap(Entry::getNodeName
            , e -> e));

    @Getter
    public static class EntryNode extends Node {

        Entry<?> entry;
        Object value;

        public EntryNode() {
        }

        public EntryNode init() {
            this.entry = Objects.requireNonNull(entryMap.get(kind));
            String str = components.stream().map(s -> ((NameNode) s).name).collect(Collectors.joining(" "));
            this.value = entry.parser.apply(str);
            return this;
        }

        @Override
        void document(PrintWriter writer) {

        }
    }

    private Map<Entry<?>, Object> entryValues;

    MetadataNode() {
        this(new ArrayList<>());
    }

    MetadataNode(List<Node> components) {
        this.kind = "Metadata";
        this.components = components;
        this.entryValues = Map.of();
    }

    @Override
    void document(PrintWriter writer) {

    }

    void constrain(Context ctx) {
        Map<String, EntryNode> entryNodes = components.stream()
                .filter(e -> e instanceof EntryNode).collect(Collectors.toMap(e -> e.kind,
                        e -> ((EntryNode) e).init()));
        this.entryValues = new HashMap<>(entries.stream()
                .collect(Collectors.toMap(e -> e, e -> {
                    if (entryNodes.containsKey(e.getNodeName())) {
                        return entryNodes.get(e.getNodeName()).value;
                    }
                    if (e.hasDefaultValue()) {
                        return e.defaultValue.get().get();
                    }
                    error("Missing entry " + e.getNodeName() + " for " + ctx.whereJava);
                    return 0;
                })));
        var entryValuesForStrings = entryValues.entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().getNodeName(), Map.Entry::getValue));
        this.entryValues.putAll(entryValues.entrySet().stream().filter(e -> e.getValue() instanceof Function)
                .collect(Collectors.toMap(Map.Entry::getKey,
                        e -> e.getKey().defaultValue.get().function.apply(entryValuesForStrings))));
        super.constrain(ctx);
    }

    public Map<Entry<?>, Object> getEntryValues() {
        return entryValues;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String entry) {
        return (T) entryValues.get(entryMap.get(entry));
    }
}
