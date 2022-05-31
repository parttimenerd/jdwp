package jdwp;

import jdwp.EventCmds.Events.EventCommon;
import jdwp.PrimitiveValue.StringValue;
import jdwp.Reference.ArrayReference;
import jdwp.util.Pair;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import tunnel.util.ToCode;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static jdwp.JDWP.Tag;
import static jdwp.util.Pair.p;

@SuppressWarnings("ALL")
public abstract class Value implements ToCode {

    public final Type type;

    protected Value(Type type) {
        this.type = type;
    }

    boolean isBasic() {
        return false;
    }

    public abstract void write(PacketOutputStream ps);

    public enum Type implements ToCode {
        THREAD(Tag.THREAD),
        LIST(Tag.ARRAY),
        REQUEST(-2),
        REPLY(-3),
        EVENTS(-4),
        EVENT(-5),
        BYTE(Tag.BYTE),
        CHAR(Tag.CHAR),
        OBJECT(Tag.OBJECT),
        FLOAT(Tag.OBJECT),
        DOUBLE(Tag.DOUBLE),
        INT(Tag.INT),
        LONG(Tag.LONG),
        SHORT(Tag.SHORT),
        BOOLEAN(Tag.BOOLEAN),
        VOID(Tag.VOID),
        STRING(Tag.STRING),
        THREAD_GROUP(Tag.THREAD_GROUP),
        CLASS_LOADER(Tag.CLASS_LOADER),
        CLASS_OBJECT(Tag.CLASS_OBJECT),
        LOCATION(-1), TYPE(-1), ARRAY(Tag.ARRAY),
        VALUE(-1), EVENT_MODIFIER(-1);

        protected static final Map<Class<? extends Value>, Type> classTypeMap = new HashMap<>();

        final int tag;
        Type(int tag) {
            this.tag = tag;
        }

        static Type forPrimitive(byte tag) {
            switch ((int)tag) {
                case Tag.BOOLEAN:
                    return Type.BOOLEAN;
                case Tag.BYTE:
                    return Type.BYTE;
                case Tag.CHAR:
                    return Type.CHAR;
                case Tag.SHORT:
                    return Type.SHORT;
                case Tag.INT:
                    return Type.INT;
                case Tag.LONG:
                    return Type.LONG;
                case Tag.FLOAT:
                    return Type.FLOAT;
                case Tag.DOUBLE:
                    return Type.DOUBLE;
                case Tag.STRING:
                    return Type.STRING;
                case Tag.VOID:
                    return Type.VOID;
                case Tag.OBJECT:
                    return Type.OBJECT;
                case Tag.THREAD:
                    return Type.THREAD;
                case Tag.THREAD_GROUP:
                    return Type.THREAD_GROUP;
                case Tag.CLASS_LOADER:
                    return Type.CLASS_LOADER;
                case Tag.CLASS_OBJECT:
                    return CLASS_OBJECT;
                default:
                    throw new AssertionError("unknown primitive tag " + (char)tag);
            }
        }

        static Type forTag(byte tag) {
            switch (tag) {
                case Tag.OBJECT:
                    return Type.OBJECT;
                case Tag.CLASS_OBJECT:
                    return Type.CLASS_OBJECT;
                case Tag.ARRAY:
                    return Type.ARRAY;
            }
            return forPrimitive(tag);
        }

        public static <T extends Value> Type forClass(Class<T> klass) {
            if (klass.equals(BasicScalarValue.class)) {
                return VALUE;
            }
            return classTypeMap.getOrDefault(klass, OBJECT);
        }

        static void registerType(Class<? extends Value> klass, Type type) {
            classTypeMap.put(klass, type);
        }

        public String toCode() {
            return "Type." + name();
        }
    }

    public static abstract class WalkableValue<K> extends Value {
        protected WalkableValue(Type type) {
            super(type);
        }

        public abstract Stream<K> getKeyStream();
        public abstract Value get(K key);
        protected abstract boolean containsKey(K key);

        public List<Pair<K, Value>> getValues() {
            return getKeyStream().map(k -> p(k, get(k))).collect(Collectors.toList());
        }

        public boolean hasValues() {
            return getValues().size() > 0;
        }

        protected Value keyError(Object key) {
            throw new AssertionError(String.format("Unknown key %s", key));
        }

        @SuppressWarnings("unchecked")
        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof WalkableValue<?>)) {
                return false;
            }
            return getValues().equals(((WalkableValue<?>) obj).getValues());
        }

        @Override
        public int hashCode() {
            return Objects.hash(getValues().toArray());
        }

        /** returns the tagged values in pre-order */
        public Stream<TaggedBasicValue<?>> getTaggedValues() {
            return getTaggedValues(new AccessPath());
        }

        private Stream<TaggedBasicValue<?>> getTaggedValues(AccessPath basePath) {
            return getValues().stream()
                    .flatMap(
                            p -> {
                                var subPath = basePath.appendElement(p.first);
                                if (p.second instanceof ListValue.BasicValue) {
                                    return Stream.of(new TaggedBasicValue<>(subPath, (BasicValue) p.second));
                                }
                                if (p.second instanceof WalkableValue<?>) {
                                    return ((WalkableValue<?>) p.second).getTaggedValues(subPath);
                                }
                                throw new AssertionError();
                            });
        }

        public ContainedValues getContainedValues() {
            var containedValues = new ContainedValues();
            getTaggedValues().forEach(containedValues::add);
            return containedValues;
        }
    }

    /**
     * Implicit contract that every instantiatable sub class has to fulfill in order to be usable with
     * the create function (request, reply and event are excluded):
     *
     * <ul>
     *   <ul>
     *     implement a constructor that accepts all parameters in order
     *   </ul>
     *   <ul>
     *     implement a constructor that accepts a Map<String, Value> containing all parameters
     *   </ul>
     *   <ul>
     *     getValues and getTaggedValues return all values to construct the exact same object
     *   </ul>
     *   <ul>
     *     all list valued fields with entries of CombinedValue type have an {@link EntryClass}
     *     annotation
     *   </ul>
     *   <ul>
     *     lists are not nested
     *   </ul>
     * </ul>
     */
    public abstract static class CombinedValue extends WalkableValue<String> {

        protected CombinedValue(Type type) {
            super(type);
        }

        @Override
        public Stream<String> getKeyStream() {
            return getKeys().stream();
        }

        public abstract List<String> getKeys();

        public abstract Value get(String key);

        @Override
        public void write(PacketOutputStream ps) {
            getKeys().forEach(k -> get(k).write(ps));
        }

        @Override
        public String toString() {
            return getClass().getSimpleName()
                    + "("
                    + getValues().stream().map(p -> p.second.toString()).collect(Collectors.joining(", "))
                    + ")";
        }

        @Override
        public String toCode() {
            return String.format(
                    "new %s(%s)",
                    getClass().getSimpleName(),
                    getValues().stream().map(p -> p.second.toCode()).collect(Collectors.joining(", ")));
        }

        @Override
        public boolean hasValues() {
            return getKeys().size() > 0;
        }

        /**
         * Create an object of the passed class with the given constructor arguments, inverse of {@link
         * #getValues()}
         *
         * <p>assumes that all constructor arguments are present
         *
         * <p>uses reflection
         */
        public static <T extends CombinedValue> T create(
                Class<T> klass, List<Pair<String, Value>> arguments) {
            return create(
                    klass, arguments.stream().collect(Collectors.toMap(p -> p.first, p -> p.second)));
        }

        public static <T extends CombinedValue> T create(Class<T> klass, Map<String, Value> arguments) {
            try {
                return (T) klass.getConstructor(Map.class).newInstance(arguments);
            } catch (InstantiationException
                    | IllegalAccessException
                    | InvocationTargetException
                    | NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }

        /** Create an object for a list of tagged values, inverse of {@link #getTaggedValues()} */
        public static <T extends CombinedValue> T createForTagged(
                Class<T> klass, Stream<TaggedBasicValue<?>> taggedArguments) {
            return CombinedValue.createForTagged(klass, taggedArguments, CombinedValue::create);
        }

        /** Create an object for a list of tagged values, inverse of {@link #getTaggedValues()} */
        protected static <T extends CombinedValue> T createForTagged(
                Class<T> klass,
                Stream<TaggedBasicValue<?>> taggedArguments,
                BiFunction<Class<T>, Map<String, Value>, T> creator) {
            return creator.apply(
                    klass,
                    collectArguments(
                            klass,
                            taggedArguments,
                            (name, values) -> {
                                try {
                                    Field field;
                                    try {
                                        field = klass.getField(name);
                                    } catch (NoSuchFieldException e) {
                                        field = klass.getDeclaredField(name);
                                    }
                                    var type = (Class<? extends Value>) field.getType();
                                    if (CombinedValue.class.isAssignableFrom(type)) {
                                        return createForTagged((Class<? extends CombinedValue>) type, values.stream());
                                    } else if (ListValue.class.isAssignableFrom(type)) {
                                        // the required element type is encoded in a field annotation
                                        var annotation = field.getAnnotation(EntryClass.class);
                                        Class<? extends Value> elementType;
                                        if (annotation != null) {
                                            elementType = annotation.value();
                                        } else {
                                            assert BasicListValue.class.isAssignableFrom(type);
                                            elementType =
                                                    values.size() > 0 ? values.get(0).value.getClass() : CombinedValue.class;
                                        }
                                        return ListValue.createForTagged(
                                                (Class<? extends ListValue<?>>) type, elementType, values.stream());
                                    } else {
                                        throw new AssertionError();
                                    }
                                } catch (NoSuchFieldException e) {
                                    throw new AssertionError(e);
                                }
                            }));
        }

        private static <T> Map<T, Value> collectArguments(
                Class<?> klass,
                Stream<TaggedBasicValue<?>> taggedArguments,
                BiFunction<T, List<TaggedBasicValue<?>>, Value> subCreate) {
            Map<T, Value> arguments = new HashMap<>();
            Map<T, List<TaggedBasicValue<?>>> subValues = new HashMap<>();
            taggedArguments.forEach(
                    tv -> {
                        var name = (T) tv.getFirstPathElement();
                        if (tv.hasSinglePath()) {
                            arguments.put(name, tv.value);
                        } else {
                            subValues
                                    .computeIfAbsent(name, p -> new ArrayList<>())
                                    .add(tv.dropFirstPathElement());
                        }
                    });
            for (var entry : subValues.entrySet()) {
                arguments.put(entry.getKey(), subCreate.apply(entry.getKey(), entry.getValue()));
            }
            // now find fields that are not present
            // two reasons: values without properties (unsupported currently) or empty lists
            Stream.concat(Arrays.stream(klass.getDeclaredFields()), Arrays.stream(klass.getFields()))
                    .filter(f -> !subValues.containsKey(f.getName()))
                    .distinct()
                    .forEach(
                            f -> {
                                var entryAnn = f.getAnnotation(EntryClass.class);
                                if (entryAnn != null) { // we found a list field without an argument
                                    assert ListValue.class.isAssignableFrom(f.getType());
                                    arguments.put(
                                            (T) f.getName(),
                                            ListValue.createForList((Class) f.getType(), entryAnn.value(), List.of()));
                                }
                            });
            return arguments;
        }
    }

    /**
     * Implicit contract that every instantiatable sub class has to fulfill in order to be usable with
     * the create function:
     *
     * <ul>
     *   <li>implement a constructor X(Type entryType, List<T> values)
     *   <li>getValues() and getTaggedValues() return all values of an object that are necessary to
     *       construct a copy
     * </ul>
     */
    public static class ListValue<T extends Value> extends WalkableValue<Integer>
            implements Iterable<T> {

        final Type entryType;
        final List<T> values;

        public ListValue(Type entryType, List<T> values) {
            super(Type.LIST);
            this.entryType = entryType;
            this.values = values;
        }

        public ListValue(Type entryType, T... values) {
            this(entryType, List.of(values));
        }

        @SafeVarargs
        public ListValue(T value, T... values) {
            super(Type.LIST);
            this.entryType = Type.forClass(value.getClass());
            this.values = Stream.concat(Stream.of(value), Stream.of(values)).collect(Collectors.toList());
        }

        @Override
        public Stream<Integer> getKeyStream() {
            return IntStream.range(0, values.size()).boxed();
        }

        @Override
        public T get(Integer key) {
            return values.get(key);
        }

        @Override
        protected boolean containsKey(Integer key) {
            return key >= 0 && key < values.size();
        }

        public int size() { return values.size(); }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeInt(values.size());
            values.forEach(value -> value.write(ps));
        }

        @Override
        public boolean equals(Object o) {
            if ((!(o instanceof ListValue<?>))) {
                return false;
            }
            ListValue<?> val = (ListValue<?>) o;
            return val.entryType == entryType && val.values.equals(values);
        }

        @Override
        public int hashCode() {
            return Objects.hash(entryType, values);
        }

        @Override
        public String toString() {
            return entryType.name() + values.toString();
        }

        @Override
        public String toCode() {
            return String.format("new %s<>(%s, List.of(%s))", getClass().getSimpleName(), type.toCode(),
                    values.stream().map(v -> v.toCode()).collect(Collectors.joining(", ")));
        }

        @NotNull
        @Override
        public Iterator<T> iterator() {
            return values.iterator();
        }

        @Override
        public boolean hasValues() {
            return size() > 0;
        }

        public Stream<T> stream() {
            return values.stream();
        }

        /** inverse of {@link #getValues()} using reflection */
        public static <T extends ListValue<? extends Value>> T create(
                Class<T> klass, Class<? extends Value> elementType, List<Pair<Integer, Value>> arguments) {
            return createForList(
                    klass,
                    elementType,
                    arguments.stream()
                            .sorted((x, y) -> Integer.compare(x.first, y.first))
                            .map(p -> p.second)
                            .collect(Collectors.toList()));
        }

        public static <T extends ListValue<? extends Value>> T createForList(
                Class<T> klass, Class<? extends Value> elementType, List<Value> arguments) {
            try {
                Type type = Type.OBJECT;
                return (T)
                        klass
                                .getConstructor(Type.class, List.class)
                                .newInstance(Type.forClass(elementType), arguments);
            } catch (InstantiationException
                    | IllegalAccessException
                    | InvocationTargetException
                    | NoSuchMethodException e) {
                throw new AssertionError(e);
            }
        }

        /** Create an object for a list of tagged values, inverse of {@link #getTaggedValues()} */
        public static <T extends ListValue<? extends Value>> T createForTagged(
                Class<T> klass,
                Class<? extends Value> elementType, // obtain via annotation on level above
                Stream<TaggedBasicValue<?>> taggedArguments) {
            Objects.requireNonNull(elementType);
            if (ListValue.class.isAssignableFrom(elementType)) {
                throw new AssertionError("no nested lists supported");
            }
            return create(
                    klass,
                    elementType,
                    CombinedValue.<Integer>collectArguments(
                                    klass,
                                    taggedArguments,
                                    (name, values) -> {
                                        if (!(name instanceof Integer)) {
                                            throw new AssertionError();
                                        }
                                        if (elementType == null) {
                                            throw new AssertionError(
                                                    "elementType == null only works for scalar element values, "
                                                            + "probably missing an EntryType annotation");
                                        }
                                        if (elementType.equals(EventCommon.class)) {
                                            // handle events differently
                                            String kindClass = "";
                                            List<TaggedBasicValue<?>> cleanedValues = new ArrayList<>();
                                            for (TaggedBasicValue<?> value : values) {
                                                if (value.getPath().get(0).equals("kind")) {
                                                    kindClass = ((StringValue)value.value).value;
                                                } else {
                                                    cleanedValues.add(value);
                                                }
                                            }
                                            try {
                                                return CombinedValue.createForTagged(
                                                        (Class<CombinedValue>)Class.forName(elementType.getName().replace("$EventCommon", "") + "$" + kindClass),
                                                            values.stream());
                                            } catch (ClassNotFoundException e) {
                                                throw new AssertionError(e);
                                            }
                                        }
                                        return CombinedValue.createForTagged(
                                                (Class<CombinedValue>) elementType, values.stream());
                                    })
                            .entrySet()
                            .stream()
                            .map(e -> p(e.getKey(), e.getValue()))
                            .collect(Collectors.toList()));
        }
    }

    /** also known as array region */
    public static class BasicListValue<T extends BasicScalarValue<?>> extends ListValue<T> {

        public BasicListValue(Type entryType, List<T> values) {
            super(entryType, values);
        }

        public BasicListValue(T value, T... values) {
            super(value, values);
        }

        @SuppressWarnings("unchecked cast")
        public static <T extends BasicScalarValue<?>> BasicListValue<T> read(PacketInputStream ps) {
            byte tag = ps.readByte();
            int length = ps.readInt();
            List<T> values = new ArrayList<>(length);
            boolean gettingObjects = PacketOutputStream.isObjectTag(tag);
            for (int i = 0; i < length; i++) {
                /*
                 * Each object comes back with a type key which might
                 * identify a more specific type than the type key we
                 * passed in, so we use it in the decodeValue call.
                 * (For primitives, we just use the original one)
                 */
                if (gettingObjects) {
                    tag = ps.readByte();
                }
                T value = (T)ps.readUntaggedValue(tag);
                values.add(value);
            }
            return new BasicListValue<>(Type.forTag(tag), values);
        }

        @SuppressWarnings("unchecked cast")
        public static <T extends BasicScalarValue<?>> BasicListValue<T> read(PacketInputStream ps, ArrayReference ref) {
            byte tag = ps.vm().getArrayTag(ref.value);
            int length = ps.readInt();
            List<T> values = new ArrayList<>(length);
            boolean gettingObjects = PacketOutputStream.isObjectTag(tag);
            for (int i = 0; i < length; i++) {
                /*
                 * Each object comes back with a type key which might
                 * identify a more specific type than the type key we
                 * passed in, so we use it in the decodeValue call.
                 * (For primitives, we just use the original one)
                 */
                if (gettingObjects) {
                    tag = ps.readByte();
                }
                T value = (T)ps.readUntaggedValue(tag);
                values.add(value);
            }
            return new BasicListValue<>(Type.forTag(tag), values);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeByte((byte)type.tag);
            ps.writeInt(values.size());
            boolean writingObjects = PacketOutputStream.isObjectTag((byte)type.tag);
            for (T value : values) {
                if (writingObjects) {
                    ps.writeByte((byte)value.type.tag);
                }
                ps.writeWritableUntagged(value);
            }
        }

        public void writeUntagged(PacketOutputStream ps) {
            ps.writeInt(values.size());
            for (T value : values) {
                ps.writeWritableUntagged(value);
            }
        }
    }

    static enum BasicGroup {
        THREAD_GROUP_REF,
        MODULE_REF,
        FIELD_REF,
        FRAME_REF,
        HEAP_REF,
        METHOD_REF,
        CLASSLOADER_REF,
        THREAD_REF,
        BYTE_LIST,
        BOOLEAN,
        BYTE,
        CHAR,
        SHORT,
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        STRING,
        VOID
    }

    public static abstract class BasicValue extends Value {

        protected BasicValue(Type type) {
            super(type);
        }

        @Override
        boolean isBasic() {
            return true;
        }

        public abstract BasicGroup getGroup();

        @Override
        public int hashCode() {
            return getGroup().hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof BasicValue && ((BasicValue) obj).getGroup().equals(getGroup());
        }
    }

    /** Consists only of a single data field */
    public static abstract class BasicScalarValue<T> extends BasicValue {

        public final T value;

        protected BasicScalarValue(Type type, T value) {
            super(type);
            this.value = value;
        }

        T getValue() {
            return value;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + value + ")";
        }

        @Override
        public boolean equals(Object o) {
            return super.equals(o) && o instanceof BasicScalarValue<?> && ((BasicScalarValue<?>)o).value.equals(value);
        }

        @Override
        public int hashCode() {
            return super.hashCode() * value.hashCode();
        }

        public abstract boolean isPrimitive();

        public abstract boolean isReference();

        @Override
        public String toCode() {
            return String.format("new %s(%s)", getClass().getSimpleName(), value);
        }
    }

    /**
     * Specific list imlementation that is a basic value and stores the bytes
     * as a byte array.
     */
    public static class ByteList extends BasicValue {

        public final byte[] bytes;

        public ByteList(byte... bytes) {
            super(Type.LIST);
            this.bytes = bytes;
        }

        byte[] getValue() {
            return bytes;
        }

        byte get(int index) {
            return bytes[index];
        }

        public static ByteList read(PacketInputStream ps) {
            int length = ps.readInt();
            byte[] bytes = ps.readByteArray(length);
            return new ByteList(bytes);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeInt(bytes.length);
            ps.writeByteArray(bytes);
        }

        @Override
        public String toString() {
            return "bytes" + Arrays.toString(bytes) + "";
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ByteList && Arrays.equals(((ByteList) o).bytes, bytes);
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.BYTE_LIST;
        }

        @Override
        public String toCode() {
            return String.format("new ByteList(%s)",
                    Arrays.asList(bytes).stream().map(b -> "" + b).collect(Collectors.joining(", ")));
        }
    }

    @Getter
    @EqualsAndHashCode(callSuper = false)
    public static class TaggedBasicValue<V extends BasicValue> extends BasicValue {

        @NotNull public final AccessPath path;
        @NotNull
        public final V value;

        public TaggedBasicValue(AccessPath path, V value) {
            super(value.type);
            this.path = path;
            this.value = value;
        }

        @Override
        public void write(PacketOutputStream ps) {
            value.write(ps);
        }

        @Override
        public BasicGroup getGroup() {
            return value.getGroup();
        }

        @Override
        public String toCode() {
            return String.format("new TaggedBasicValue()"); // currently not supported
        }

        @Override
        public String toString() {
            return "TaggedBasicValue{" +
                    "path=" + path +
                    ", value=" + value +
                    '}';
        }

        public boolean hasSinglePath() {
            return path.size() == 1;
        }

        /** returns the first path element, either string or int */
        public Object getFirstPathElement() {
            return path.get(0);
        }

        /** assumes that the path has at least size 2 and drops the first path element */
        public TaggedBasicValue<?> dropFirstPathElement() {
            assert path.size() >= 2;
            return new TaggedBasicValue<>(path.dropFirstPathElement(), value);
        }

        public TaggedBasicValue<V> prependPath(Object... prefix) {
            return new TaggedBasicValue<>(path.prepend(prefix), value);
        }
    }
}
