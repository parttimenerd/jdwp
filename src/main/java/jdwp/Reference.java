package jdwp;

import jdwp.JDWP.Tag;
import jdwp.JDWP.TypeTag;
import jdwp.Value.BasicValue;

/** reference to an object, thread, ... */
@SuppressWarnings("ALL")
public class Reference extends BasicValue<Long> {

    public Reference(Type type, long ref) {
        super(type, ref);
    }

    public void write(PacketStream ps) {
        ps.writeObjectRef(getValue());
    }

    public static Reference readReference(PacketStream ps) {
        return readReference(ps, ps.readByte());
    }

    public static Reference readReference(PacketStream ps, byte tag) {
        switch ((int)tag) {
            case Tag.OBJECT:
            case Tag.STRING:
                return ObjectReference.read(tag, ps);
                case Tag.ARRAY:
                    return ArrayReference.read(ps);
            case Tag.THREAD:
                return ThreadReference.read(ps);
            case Tag.THREAD_GROUP:
                return ThreadGroupReference.read(ps);
            case Tag.CLASS_LOADER:
                return ClassLoaderReference.read(ps);
            case Tag.CLASS_OBJECT:
                return ClassObjectReference.read(ps);
            default:
                throw new AssertionError();
        }
    }

    public static ObjectReference object(long ref) {
        return new ObjectReference(ref);
    }

    public static ObjectReference object(Type type, long ref) {
        return new ObjectReference(type, ref);
    }

    public static ThreadReference thread(long ref) { return new ThreadReference(ref); }

    public static ThreadGroupReference threadGroup(long ref) { return new ThreadGroupReference(ref); }

    public static ClassTypeReference classType(long ref) { return new ClassTypeReference(ref); }

    public static InterfaceTypeReference interfaceType(long ref) { return new InterfaceTypeReference(ref); }

    public static ArrayReference array(long ref) { return new ArrayReference(ref); }

    public static NullObjectReference nullObject() { return new NullObjectReference(); }

    public static ClassReference klass(long ref) { return new ClassReference(ref); }

    public static ClassLoaderReference classLoader(long ref) { return new ClassLoaderReference(ref); }

    public static MethodReference method(long ref) { return new MethodReference(ref); }

    public static ModuleReference module(long ref) { return new ModuleReference(ref); }

    public static FieldReference field(long ref) { return new FieldReference(ref); }

    public static FrameReference frame(long ref) { return new FrameReference(ref); }

    public static ClassObjectReference classObject(long ref) { return new ClassObjectReference(ref); }

    public static class ObjectReference extends Reference {

        public ObjectReference(long ref) {
            super(Type.OBJECT, ref);
        }

        public ObjectReference(Type type, long ref) {
            super(type, ref);
        }

        public static ObjectReference read(PacketStream ps) {
            return new ObjectReference(ps.readObjectRef());
        }

        public static ObjectReference read(byte tag, PacketStream ps) {
            return new ObjectReference(Type.forPrimitive(tag), ps.readObjectRef());
        }

        public static ObjectReference readTagged(PacketStream ps) {
            return new ObjectReference(Type.forPrimitive(ps.readByte()), ps.readObjectRef());
        }
    }


    public static class ThreadReference extends Reference {
        ThreadReference(long ref) {
            super(Type.THREAD, ref);
        }

        public static ThreadReference read(PacketStream ps) {
            return new ThreadReference(ps.readObjectRef());
        }
    }

    public static class ThreadGroupReference extends Reference {
        ThreadGroupReference(long ref) {
            super(Type.THREAD_GROUP, ref);
        }

        public static ThreadGroupReference read(PacketStream ps) {
            return new ThreadGroupReference(ps.readObjectRef());
        }
    }

    public static abstract class TypeReference extends Reference {
        public final byte typeTag;

        TypeReference(byte typeTag, long ref) {
            super(Type.CLASS_OBJECT, ref);
            this.typeTag = typeTag;
        }

        @Override
        public void write(PacketStream ps) {
            ps.writeClassRef(value);
        }

        public void writeTagged(PacketStream ps) {
            ps.writeByte(typeTag);
            write(ps);
        }

        public static TypeReference read(PacketStream ps) {
            byte tag = ps.readByte();
            switch (tag) {
                case TypeTag.CLASS:
                    return ClassTypeReference.read(ps);
                case TypeTag.INTERFACE:
                    return InterfaceTypeReference.read(ps);
                case TypeTag.ARRAY:
                    return ArrayTypeReference.read(ps);
                default:
                    throw new AssertionError("Unknown type tag " + tag);
            }
        }
    }

    public static class InterfaceTypeReference extends TypeReference {

        InterfaceTypeReference(long ref) {
            super((byte)TypeTag.INTERFACE, ref);
        }

        public static InterfaceTypeReference read(PacketStream ps) {
            return new InterfaceTypeReference(ps.readClassRef());
        }
    }

    public static class ClassTypeReference extends TypeReference {

        ClassTypeReference(long ref) {
            super((byte)TypeTag.CLASS, ref);
        }

        public static ClassTypeReference read(PacketStream ps) {
            return new ClassTypeReference(ps.readClassRef());
        }
    }

    public static class ArrayTypeReference extends TypeReference {

        ArrayTypeReference(long ref) {
            super((byte)TypeTag.ARRAY, ref);
        }

        public static ArrayTypeReference read(PacketStream ps) {
            return new ArrayTypeReference(ps.readClassRef());
        }
    }

    public static class NullObjectReference extends Reference {
        NullObjectReference() {
            super(Type.OBJECT, 0);
        }
    }

    public static class ClassReference extends Reference {
        ClassReference(long ref) {
            super(Type.CLASS_OBJECT, ref);
        }

        @Override
        public void write(PacketStream ps) {
            ps.writeClassRef(value);
        }

        public static ClassReference read(PacketStream ps) {
            return new ClassReference(ps.readClassRef());
        }
    }

    public static class MethodReference extends Reference {
        MethodReference(long val) {
            super(Type.OBJECT, val);
        }

        @Override
        public void write(PacketStream ps) {
            ps.writeMethodRef(value);
        }

        public static MethodReference read(PacketStream ps) {
            return new MethodReference(ps.readMethodRef());
        }
    }

    public static class ArrayReference extends Reference {
        ArrayReference(long val) {
            super(Type.ARRAY, val);
        }

        @Override
        public void write(PacketStream ps) {
            ps.writeMethodRef(value);
        }

        public static ArrayReference read(PacketStream ps) {
            return new ArrayReference(ps.readObjectRef());
        }
    }

    public static class ModuleReference extends Reference {
        ModuleReference(long val) {
            super(Type.OBJECT, val);
        }

        @Override
        public void write(PacketStream ps) {
            ps.writeModuleRef(value);
        }

        public static ModuleReference read(PacketStream ps) {
            return new ModuleReference(ps.readModuleRef());
        }
    }

    public static class FieldReference extends Reference {
        FieldReference(long val) {
            super(Type.OBJECT, val);
        }

        @Override
        public void write(PacketStream ps) {
            ps.writeFieldRef(value);
        }

        public static FieldReference read(PacketStream ps) {
            return new FieldReference(ps.readFieldRef());
        }

        public BasicValue<?> readUntaggedFieldValue(PacketStream ps) {
            return ps.readUntaggedValue(ps.vm.getFieldTag(value));
        }
    }

    public static class FrameReference extends Reference {
        FrameReference(long val) {
            super(Type.OBJECT, val);
        }

        @Override
        public void write(PacketStream ps) {
            ps.writeFrameRef(value);
        }

        public static FrameReference read(PacketStream ps) {
            return new FrameReference(ps.readFrameRef());
        }
    }

    public static class ClassLoaderReference extends Reference {
        ClassLoaderReference(long val) {
            super(Type.CLASS_LOADER, val);
        }

        public static ClassLoaderReference read(PacketStream ps) {
            return new ClassLoaderReference(ps.readObjectRef());
        }
    }

    public static class ClassObjectReference extends Reference {
        ClassObjectReference(long val) {
            super(Type.CLASS_OBJECT, val);
        }

        public static ClassObjectReference read(PacketStream ps) {
            return new ClassObjectReference(ps.readObjectRef());
        }
    }
}
