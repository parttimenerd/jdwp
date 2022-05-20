package jdwp;

import jdwp.JDWP.Tag;
import jdwp.Value.BasicScalarValue;

/**
 * Primitive values
 *
 * @param <T> wrapped type
 */
@SuppressWarnings("ALL")
public abstract class PrimitiveValue<T> extends BasicScalarValue<T> {

    private PrimitiveValue(Type type, T value) {
        super(type, value);
    }

    public static ByteValue wrap(byte val) { return new ByteValue(val); }

    public static BooleanValue wrap(boolean val) { return new BooleanValue(val); }

    public static CharValue wrap(char val) { return new CharValue(val); }

    public static ShortValue wrap(short val) { return new ShortValue(val); }

    public static IntValue wrap(int val) { return new IntValue(val); }

    public static LongValue wrap(long val) { return new LongValue(val); }

    public static FloatValue wrap(float val) { return new FloatValue(val); }

    public static DoubleValue wrap(double val) { return new DoubleValue(val); }

    public static StringValue wrap(String val) { return new StringValue(val); }

    @Override
    public String toCode() {
        return String.format("PrimitiveValue.wrap((%s)%s)",
                getClass().getSimpleName().replace("Value", "").toLowerCase(), value);
    }

    public static class BooleanValue extends jdwp.PrimitiveValue<Boolean> {

        static {
            Type.registerType(BooleanValue.class, Type.BOOLEAN);
        }

        public BooleanValue(Boolean value) {
            super(Type.BOOLEAN, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeBoolean(value);
        }

        public static BooleanValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readBoolean());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.BOOLEAN;
        }

        @Override
        public String toCode() {
            return String.format("PrimitiveValue.wrap(%s)", value);
        }
    }

    public static class ByteValue extends jdwp.PrimitiveValue<Byte> {

        static {
            Type.registerType(ByteValue.class, Type.BYTE);
        }

        public ByteValue(Byte value) {
            super(Type.BYTE, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeByte(value);
        }

        public static ByteValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readByte());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.BYTE;
        }
    }

    public static class CharValue extends jdwp.PrimitiveValue<Character> {

        static {
            Type.registerType(CharValue.class, Type.CHAR);
        }

        public CharValue(Character value) {
            super(Type.CHAR, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeChar(value);
        }

        public static CharValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readChar());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.CHAR;
        }
    }

    public static class ShortValue extends jdwp.PrimitiveValue<Short> {

        static {
            Type.registerType(ShortValue.class, Type.SHORT);
        }

        public ShortValue(Short value) {
            super(Type.SHORT, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeShort(value);
        }

        public static ShortValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readShort());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.SHORT;
        }
    }

    public static class IntValue extends jdwp.PrimitiveValue<Integer> {

        static {
            Type.registerType(IntValue.class, Type.INT);
        }

        public IntValue(Integer value) {
            super(Type.INT, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeInt(value);
        }

        public static IntValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readInt());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.INT;
        }

        @Override
        public String toCode() {
            return String.format("PrimitiveValue.wrap(%s)", value);
        }
    }

    public static class LongValue extends jdwp.PrimitiveValue<Long> {

        static {
            Type.registerType(LongValue.class, Type.LONG);
        }

        public LongValue(Long value) {
            super(Type.LONG, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeLong(value);
        }

        public static LongValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readLong());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.LONG;
        }
    }

    public static class FloatValue extends jdwp.PrimitiveValue<Float> {

        static {
            Type.registerType(FloatValue.class, Type.FLOAT);
        }

        protected FloatValue(Float value) {
            super(Type.FLOAT, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeFloat(value);
        }

        public static FloatValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readFloat());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.FLOAT;
        }
    }

    public static class DoubleValue extends jdwp.PrimitiveValue<Double> {

        static {
            Type.registerType(DoubleValue.class, Type.DOUBLE);
        }

        protected DoubleValue(Double value) {
            super(Type.DOUBLE, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeDouble(value);
        }

        public static DoubleValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readDouble());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.DOUBLE;
        }

        @Override
        public String toCode() {
            return String.format("PrimitiveValue.wrap(%s)", value);
        }
    }

    public static class StringValue extends PrimitiveValue<String> {

        static {
            Type.registerType(StringValue.class, Type.STRING);
        }

        public StringValue(String value) {
            super(Type.STRING, value);
        }

        public void write(PacketOutputStream packetStream) {
            packetStream.writeString(value);
        }

        public static StringValue read(PacketInputStream ps) {
            return PrimitiveValue.wrap(ps.readString());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.STRING;
        }

        @Override
        public String toCode() {
            return String.format("PrimitiveValue.wrap(%s)", value);
        }
    }

    public static class VoidValue extends PrimitiveValue<Integer> {

        static {
            Type.registerType(VoidValue.class, Type.VOID);
        }

        protected VoidValue() {
            super(Type.VOID, 0);
        }

        public static final VoidValue VALUE = new VoidValue();


        @Override
        public void write(PacketOutputStream packetStream) {

        }

        public static VoidValue read(PacketInputStream ps) {
            return VALUE;
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.VOID;
        }
    }

    public static PrimitiveValue<?> readValue(PacketInputStream ps) {
        return readValue(ps, ps.readByte());
    }

    public static PrimitiveValue<?> readValue(PacketInputStream ps, byte tag) {
        switch ((int)tag) {
            case Tag.BOOLEAN:
                return BooleanValue.read(ps);
            case Tag.BYTE:
                return ByteValue.read(ps);
            case Tag.CHAR:
                return CharValue.read(ps);
            case Tag.SHORT:
                return ShortValue.read(ps);
            case Tag.INT:
                return IntValue.read(ps);
            case Tag.FLOAT:
                return FloatValue.read(ps);
            case Tag.DOUBLE:
                return DoubleValue.read(ps);
            case Tag.VOID:
                return VoidValue.read(ps);
            default:
                throw new AssertionError("Unknown tag " + tag);
        }
    }

    @Override
    public boolean isPrimitive() {
        return true;
    }

    @Override
    public boolean isReference() {
        return false;
    }
}
