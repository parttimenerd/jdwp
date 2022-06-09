package jdwp;

import jdwp.JDWP.Tag;
import jdwp.JDWP.TypeTag;
import jdwp.Value.BasicScalarValue;

import java.util.Objects;

/**
 * reference to an object, thread, ...
 *
 * they are equal if there underlying value is equal, ignoring their type
 */
@SuppressWarnings("ALL")
public abstract class Reference extends BasicScalarValue<Long> {

    public Reference(Type type, long ref) {
        super(type, ref);
    }

    @Override
    public String toCode() {
        return String.format("new %s(%sL)", getClass().getSimpleName(), value);
    }

    public void write(PacketOutputStream ps) {
        ps.writeObjectRef(getValue());
    }

    public static Reference readReference(PacketInputStream ps) {
        return readReference(ps, ps.readByte());
    }

    public static Reference readReference(PacketInputStream ps, byte tag) {
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
                throw new AssertionError(String.format("Unknown reference tag %d", tag));
        }
    }

    public static ObjectReference object(long ref) {
        return new ObjectReference(ref);
    }

    public static ObjectReference object(Type type, long ref) {
        return new ObjectReference(type, ref);
    }

    public static ObjectReference string(long ref) {
        return new ObjectReference(Type.STRING, ref);
    }

    public static ObjectReference integer(long ref) {
        return new ObjectReference(Type.INT, ref);
    }

    public static ThreadReference thread(long ref) { return new ThreadReference(ref); }

    public static ThreadGroupReference threadGroup(long ref) { return new ThreadGroupReference(ref); }

    public static ClassTypeReference classType(long ref) { return new ClassTypeReference(ref); }

    public static InterfaceTypeReference interfaceType(long ref) { return new InterfaceTypeReference(ref); }

    public static ArrayTypeReference arrayType(long ref) { return new ArrayTypeReference(ref); }

    public static ArrayReference array(long ref) { return new ArrayReference(ref); }

    public static NullObjectReference nullObject() { return new NullObjectReference(); }

    public static ClassReference klass(long ref) { return new ClassReference(ref); }

    public static InterfaceReference interfac(long ref) { return new InterfaceReference(ref); }

    public static ClassLoaderReference classLoader(long ref) { return new ClassLoaderReference(ref); }

    public static MethodReference method(long ref) { return new MethodReference(ref); }

    public static ModuleReference module(long ref) { return new ModuleReference(ref); }

    public static FieldReference field(long ref) { return new FieldReference(ref); }

    public static FrameReference frame(long ref) { return new FrameReference(ref); }

    public static ClassObjectReference classObject(long ref) { return new ClassObjectReference(ref); }

    public static abstract class HeapReference extends Reference {

        public HeapReference(Type type, long ref) {
            super(type, ref);
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.HEAP_REF;
        }
    }

    public static class ObjectReference extends HeapReference {

        public ObjectReference(long ref) {
            super(Type.OBJECT, ref);
        }

        public ObjectReference(Type type, long ref) {
            super(type, ref);
        }

        public static ObjectReference read(PacketInputStream ps) {
            return new ObjectReference(ps.readObjectRef());
        }

        public static ObjectReference read(byte tag, PacketInputStream ps) {
            return new ObjectReference(Type.forPrimitive(tag), ps.readObjectRef());
        }

        public static ObjectReference readTagged(PacketInputStream ps) {
            return new ObjectReference(Type.forPrimitive(ps.readByte()), ps.readObjectRef());
        }
    }

    public static class ThreadReference extends Reference {
        public ThreadReference(long ref) {
            super(Type.THREAD, ref);
        }

        public static ThreadReference read(PacketInputStream ps) {
            return new ThreadReference(ps.readObjectRef());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.THREAD_REF;
        }
    }

    public static class ThreadGroupReference extends Reference {
        public ThreadGroupReference(long ref) {
            super(Type.THREAD_GROUP, ref);
        }

        public static ThreadGroupReference read(PacketInputStream ps) {
            return new ThreadGroupReference(ps.readObjectRef());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.THREAD_GROUP_REF;
        }
    }

    public static abstract class TypeReference extends ClassObjectReference {
        public final byte typeTag;

        TypeReference(byte typeTag, long ref) {
            super(ref);
            this.typeTag = typeTag;
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeClassRef(value);
        }

        public void writeTagged(PacketOutputStream ps) {
            ps.writeByte(typeTag);
            write(ps);
        }

        public static TypeReference read(PacketInputStream ps) {
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

        public static InterfaceTypeReference read(PacketInputStream ps) {
            return new InterfaceTypeReference(ps.readClassRef());
        }
    }

    public static class ClassTypeReference extends TypeReference {

        public ClassTypeReference(long ref) {
            super((byte)TypeTag.CLASS, ref);
        }

        public static ClassTypeReference read(PacketInputStream ps) {
            return new ClassTypeReference(ps.readClassRef());
        }
    }

    public static class ArrayTypeReference extends TypeReference {

        ArrayTypeReference(long ref) {
            super((byte)TypeTag.ARRAY, ref);
        }

        public static ArrayTypeReference read(PacketInputStream ps) {
            return new ArrayTypeReference(ps.readClassRef());
        }
    }

    public static class NullObjectReference extends ObjectReference {
        NullObjectReference() {
            super(Type.OBJECT, 0);
        }
    }

    public static class TypeObjectReference extends ClassObjectReference {

        public TypeObjectReference(long ref) {
            super(ref);
        }

        public static TypeObjectReference read(PacketInputStream ps) {
            return new TypeObjectReference(ps.readClassRef());
        }
    }

    public static class ClassReference extends TypeObjectReference {
        public ClassReference(long ref) {
            super(ref);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeClassRef(value);
        }

        public static ClassReference read(PacketInputStream ps) {
            return new ClassReference(ps.readClassRef());
        }
    }

    public static class InterfaceReference extends TypeObjectReference {
        InterfaceReference(long ref) {
            super(ref);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeClassRef(value);
        }

        public static InterfaceReference read(PacketInputStream ps) {
            return new InterfaceReference(ps.readClassRef());
        }
    }

    public static class MethodReference extends Reference {
        public MethodReference(long val) {
            super(Type.OBJECT, val);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeMethodRef(value);
        }

        public static MethodReference read(PacketInputStream ps) {
            return new MethodReference(ps.readMethodRef());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.METHOD_REF;
        }
    }

    public static class ArrayReference extends HeapReference {
        ArrayReference(long val) {
            super(Type.ARRAY, val);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeMethodRef(value);
        }

        public static ArrayReference read(PacketInputStream ps) {
            return new ArrayReference(ps.readObjectRef());
        }
    }

    public static class ModuleReference extends Reference {
        ModuleReference(long val) {
            super(Type.OBJECT, val);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeModuleRef(value);
        }

        public static ModuleReference read(PacketInputStream ps) {
            return new ModuleReference(ps.readModuleRef());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.MODULE_REF;
        }
    }

    public static class FieldReference extends Reference {
        FieldReference(long val) {
            super(Type.OBJECT, val);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeFieldRef(value);
        }

        public static FieldReference read(PacketInputStream ps) {
            return new FieldReference(ps.readFieldRef());
        }

        public BasicScalarValue<?> readUntaggedInstanceFieldValue(Reference instance, PacketInputStream ps) {
            return ps.readUntaggedValue(ps.vm().getFieldTagForObj(instance.value, value));
        }

        public BasicScalarValue<?> readUntaggedClassFieldValue(Reference klass, PacketInputStream ps) {
            return ps.readUntaggedValue(ps.vm().getFieldTagForClass(klass.value, value));
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.FIELD_REF;
        }
    }

    public static class FrameReference extends Reference {
        public FrameReference(long val) {
            super(Type.OBJECT, val);
        }

        @Override
        public void write(PacketOutputStream ps) {
            ps.writeFrameRef(value);
        }

        public static FrameReference read(PacketInputStream ps) {
            return new FrameReference(ps.readFrameRef());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.FRAME_REF;
        }
    }

    public static class ClassLoaderReference extends Reference {
        ClassLoaderReference(long val) {
            super(Type.CLASS_LOADER, val);
        }

        public static ClassLoaderReference read(PacketInputStream ps) {
            return new ClassLoaderReference(ps.readObjectRef());
        }

        @Override
        public BasicGroup getGroup() {
            return BasicGroup.CLASSLOADER_REF;
        }
    }

    public static class ClassObjectReference extends HeapReference {
        ClassObjectReference(long val) {
            super(Type.CLASS_OBJECT, val);
        }

        public static ClassObjectReference read(PacketInputStream ps) {
            return new ClassObjectReference(ps.readObjectRef());
        }
    }

    @Override
    public boolean isReference() {
        return true;
    }

    @Override
    public boolean isPrimitive() {
        return false;
    }

    public <R extends Reference, S extends Reference> R checkInGroup(BasicGroup group) {
        if (!getGroup().equals(group)) {
            throw new AssertionError(String.format("%s not in group %s", this, group));
        }
        return (R)this;
    }

    public static boolean isNotReferenceOrSameClassArgument(Class<?> klass, Value value) {
        Objects.requireNonNull(value);
        return !(value instanceof Reference) || klass.isAssignableFrom(value.getClass());
    }
}
