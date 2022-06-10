package build.tools.jdwpgen;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.TypeName;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.io.PrintWriter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

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
                    return ClassName.get(resultType);
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
                            "OnlyReads"))))
    );

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
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getKey().defaultValue.get().function.apply(entryValuesForStrings))));
        super.constrain(ctx);
    }

    public Map<Entry<?>, Object> getEntryValues() {
        return entryValues;
    }
}
