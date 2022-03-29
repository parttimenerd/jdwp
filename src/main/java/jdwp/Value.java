package jdwp;

import jdwp.Reference.ArrayReference;
import lombok.EqualsAndHashCode;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import static jdwp.JDWP.Tag;

public abstract class Value {

    protected static Map<Class<? extends Value>, Type> classTypeMap = new HashMap<>();

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
        VALUE(-1);
        int tag;
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

        protected Value keyError(Object key) {
            throw new AssertionError("Unknown key %s".formatted(key));
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof WalkableValue<?>)) {
                return false;
            }
            return getKeyStream().allMatch(k -> ((WalkableValue) obj).get(k).equals(get(k)));
        }

        @Override
        public int hashCode() {
            return Objects.hash(getKeyStream().toArray());
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
    /*public static class TypeComponent extends CombinedValue {
        final Reference ref;
        final jdwp.PrimitiveValue<String> name;
        final jdwp.PrimitiveValue<String> signature;
        final jdwp.PrimitiveValue<String> genericSignature;
        final Reference declaringType;
        final jdwp.PrimitiveValue<Integer> modifiers;

        public TypeComponent(Type type, Reference ref, jdwp.PrimitiveValue<String> name, jdwp.PrimitiveValue<String> signature, jdwp.PrimitiveValue<String> genericSignature, Reference declaringType, jdwp.PrimitiveValue<Integer> modifiers) {
            super(type);
            this.ref = ref;
            this.name = name;
            this.signature = signature;
            this.genericSignature = genericSignature;
            this.declaringType = declaringType;
            this.modifiers = modifiers;
        }

        private static final List<String> keys = Arrays.asList("ref", "name", "signature", "genericSignature", "declaringType", "modifiers");
        @Override
        List<String> getKeys() {
            return keys;
        }

        @Override
        Value get(String key) {
            switch (key) {
                case "ref":
                    return ref;
                case "name":
                    return name;
                case "signature":
                    return signature;
                case "genericSignature":
                    return genericSignature;
                case "declaringType":
                    return declaringType;
                case "modifiers":
                    return modifiers;
                default:
                    return keyError(key);
            }
        }
    }*/

    public static class ListValue<T extends Value> extends WalkableValue<Integer> {

        Type entryType;
        List<T> values;

        protected ListValue(Type entryType, List<T> values) {
            super(Type.LIST);
            this.entryType = entryType;
            this.values = values;
        }

        protected ListValue(Type entryType) {
            this(entryType, Collections.emptyList());
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

        int size() { return values.size(); }

        @Override
        public void write(PacketStream ps) {
            ps.writeInt(values.size());
            values.forEach(value -> value.write(ps));
        }
    }

    /** also known as array region */
    public static class BasicListValue<T extends BasicValue<?>> extends ListValue<T> {

        protected BasicListValue(Type entryType, List<T> values) {
            super(entryType, values);
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
            BasicListValue<?> v = BasicListValue.read(ps);
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
    @EqualsAndHashCode(callSuper = false)
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
    }

}
