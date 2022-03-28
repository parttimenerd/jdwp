package jdwp;

import jdwp.JDWP.Tag;
import jdwp.Value.BasicValue;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * Primitive values
 *
 * @param <T> wrapped type
 */
@EqualsAndHashCode
@ToString
public abstract class PrimitiveValue<T> extends BasicValue<T> {

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

    public static class BooleanValue extends jdwp.PrimitiveValue<Boolean> {

        static {
            registerType(BooleanValue.class, Type.BOOLEAN);
        }

        protected BooleanValue(Boolean value) {
            super(Type.BOOLEAN, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeBoolean(value);
        }

        public static BooleanValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readBoolean());
        }
    }

    public static class ByteValue extends jdwp.PrimitiveValue<Byte> {

        static {
            registerType(ByteValue.class, Type.BYTE);
        }

        protected ByteValue(Byte value) {
            super(Type.BYTE, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeByte(value);
        }

        public static ByteValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readByte());
        }
    }

    public static class CharValue extends jdwp.PrimitiveValue<Character> {

        static {
            registerType(CharValue.class, Type.CHAR);
        }

        protected CharValue(Character value) {
            super(Type.CHAR, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeChar(value);
        }

        public static CharValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readChar());
        }
    }

    public static class ShortValue extends jdwp.PrimitiveValue<Short> {

        static {
            registerType(ShortValue.class, Type.SHORT);
        }

        protected ShortValue(Short value) {
            super(Type.SHORT, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeShort(value);
        }

        public static ShortValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readShort());
        }
    }

    public static class IntValue extends jdwp.PrimitiveValue<Integer> {

        static {
            registerType(IntValue.class, Type.INT);
        }

        protected IntValue(Integer value) {
            super(Type.INT, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeInt(value);
        }

        public static IntValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readInt());
        }
    }

    public static class LongValue extends jdwp.PrimitiveValue<Long> {

        static {
            registerType(LongValue.class, Type.LONG);
        }

        protected LongValue(Long value) {
            super(Type.LONG, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeLong(value);
        }

        public static LongValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readLong());
        }
    }

    public static class FloatValue extends jdwp.PrimitiveValue<Float> {

        static {
            registerType(FloatValue.class, Type.FLOAT);
        }

        protected FloatValue(Float value) {
            super(Type.FLOAT, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeFloat(value);
        }

        public static FloatValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readFloat());
        }
    }

    public static class DoubleValue extends jdwp.PrimitiveValue<Double> {

        static {
            registerType(DoubleValue.class, Type.DOUBLE);
        }

        protected DoubleValue(Double value) {
            super(Type.DOUBLE, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeDouble(value);
        }

        public static DoubleValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readDouble());
        }
    }

    public static class StringValue extends PrimitiveValue<String> {

        static {
            registerType(StringValue.class, Type.STRING);
        }

        protected StringValue(String value) {
            super(Type.STRING, value);
        }

        public void write(PacketStream packetStream) {
            packetStream.writeString(value);
        }

        public static StringValue read(PacketStream ps) {
            return PrimitiveValue.wrap(ps.readString());
        }
    }

    public static class VoidValue extends PrimitiveValue<Integer> {

        static {
            registerType(VoidValue.class, Type.VOID);
        }

        protected VoidValue() {
            super(Type.VOID, 0);
        }

        public static VoidValue VALUE = new VoidValue();


        @Override
        public void write(PacketStream packetStream) {

        }

        public static VoidValue read(PacketStream ps) {
            return VALUE;
        }
    }

    public static PrimitiveValue<?> readValue(PacketStream ps) {
        return readValue(ps, ps.readByte());
    }

    public static PrimitiveValue<?> readValue(PacketStream ps, byte tag) {
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
                throw new AssertionError();
        }
    }
}
