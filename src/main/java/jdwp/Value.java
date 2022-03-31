package jdwp;

import jdwp.Reference.ArrayReference;
import jdwp.util.Pair;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static jdwp.JDWP.Tag;

@SuppressWarnings("ALL")
public abstract class Value {

    protected static final Map<Class<? extends Value>, Type> classTypeMap = new HashMap<>();

    public final Type type;

    protected Value(Type type) {
        this.type = type;
    }

    boolean isBasic() {
        return false;
    }

    public abstract void write(PacketStream ps);

    public static <T extends Value> Type typeForClass(Class<T> klass) {
        if (klass.equals(BasicValue.class)) {
            return Type.VALUE;
        }
        return classTypeMap.getOrDefault(klass, Type.OBJECT);
    }

    static void registerType(Class<? extends Value> klass, Type type) {
        classTypeMap.put(klass, type);
    }
    
    public enum Type {
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
    }

    public static abstract class WalkableValue<K> extends Value {
        protected WalkableValue(Type type) {
            super(type);
        }

        abstract Stream<K> getKeyStream();
        abstract Value get(K key);
        abstract boolean containsKey(K key);

        List<Pair<K, Value>> getValues() {
            return getKeyStream().map(k -> Pair.p(k, get(k))).collect(Collectors.toList());
        }

        protected Value keyError(Object key) {
            throw new AssertionError("Unknown key %s".formatted(key));
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
    }

    public static abstract class CombinedValue extends WalkableValue<String> {

        protected CombinedValue(Type type) {
            super(type);
        }

        @Override
        Stream<String> getKeyStream() {
            return getKeys().stream();
        }

        abstract List<String> getKeys();

        abstract Value get(String key);

        @Override
        public void write(PacketStream ps) {
            getKeys().forEach(k -> get(k).write(ps));
        }
    }

    /** fields and methods */

    public static class ListValue<T extends Value> extends WalkableValue<Integer> {

        final Type entryType;
        final List<T> values;

        protected ListValue(Type entryType, List<T> values) {
            super(Type.LIST);
            this.entryType = entryType;
            this.values = values;
        }

        protected ListValue(Type entryType, T... values) {
            this(entryType, List.of(values));
        }

        @SafeVarargs
        protected ListValue(T value, T... values) {
            super(Type.LIST);
            this.entryType = Value.typeForClass(value.getClass());
            this.values = Stream.concat(Stream.of(value), Stream.of(values)).collect(Collectors.toList());
        }

        @Override
        Stream<Integer> getKeyStream() {
            return IntStream.range(0, values.size()).boxed();
        }

        @Override
        T get(Integer key) {
            return values.get(key);
        }

        @Override
        boolean containsKey(Integer key) {
            return key >= 0 && key < values.size();
        }

        int size() { return values.size(); }

        @Override
        public void write(PacketStream ps) {
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
    }

    /** also known as array region */
    public static class BasicListValue<T extends BasicValue<?>> extends ListValue<T> {

        protected BasicListValue(Type entryType, List<T> values) {
            super(entryType, values);
        }

        protected BasicListValue(T value, T... values) {
            super(value, values);
        }

        @SuppressWarnings("unchecked cast")
        public static <T extends BasicValue<?>> BasicListValue<T> read(PacketStream ps) {
            byte tag = ps.readByte();
            int length = ps.readInt();
            List<T> values = new ArrayList<>(length);
            boolean gettingObjects = PacketStream.isObjectTag(tag);
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
        public static <T extends BasicValue<?>> BasicListValue<T> read(PacketStream ps, ArrayReference ref) {
            byte tag = ps.vm.getArrayTag(ref.value);
            int length = ps.readInt();
            List<T> values = new ArrayList<>(length);
            boolean gettingObjects = PacketStream.isObjectTag(tag);
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
        public void write(PacketStream ps) {
            ps.writeByte((byte)type.tag);
            ps.writeInt(values.size());
            boolean writingObjects = PacketStream.isObjectTag((byte)type.tag);
            for (T value : values) {
                if (writingObjects) {
                    ps.writeByte((byte)value.type.tag);
                }
                ps.writeWritableUntagged(value);
            }
        }

        public void writeUntagged(PacketStream ps) {
            ps.writeInt(values.size());
            for (T value : values) {
                ps.writeWritableUntagged(value);
            }
        }
    }

    /** Consists only of a single data field */
    public static abstract class BasicValue<T> extends Value {

        public final T value;

        protected BasicValue(Type type, T value) {
            super(type);
            this.value = value;
        }

        T getValue() {
            return value;
        }

        boolean isBasic() {
            return true;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "(" + value + ")";
        }

        @Override
        public boolean equals(Object o) {
            return o.getClass() == getClass() && ((BasicValue<?>)o).value.equals(value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }
    }

    /**
     * Specific list imlementation that is a basic value and stores the bytes
     * as a byte array.
     */
    public static class ByteList extends Value {

        public final byte[] bytes;

        protected ByteList(byte... bytes) {
            super(Type.LIST);
            this.bytes = bytes;
        }

        byte[] getValue() {
            return bytes;
        }

        boolean isBasic() {
            return true;
        }

        byte get(int index) {
            return bytes[index];
        }

        public static ByteList read(PacketStream ps) {
            int length = ps.readInt();
            byte[] bytes = ps.readByteArray(length);
            return new ByteList(bytes);
        }

        @Override
        public void write(PacketStream ps) {
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
        public int hashCode() {
            return Arrays.hashCode(bytes);
        }
    }

}
