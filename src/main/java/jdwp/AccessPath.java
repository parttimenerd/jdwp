package jdwp;

import jdwp.Value.CombinedValue;
import jdwp.Value.ListValue;
import jdwp.Value.WalkableValue;
import lombok.EqualsAndHashCode;

import java.util.*;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * a path to access a specific value
 *
 * Path elements are either strings (field access) or integers (list access) and these
 * paths are not long (less than 5 elements)
 */
@EqualsAndHashCode(callSuper = false)
public class AccessPath extends AbstractList<Object> {

    /** AccessPath with root packet */
    public static class TaggedAccessPath<T extends WalkableValue<?>> extends AccessPath {

        private final T root;

        TaggedAccessPath(T root) {
            this.root = root;
        }

        public TaggedAccessPath(T root, Object... path) {
            super(path);
            this.root = root;
        }

        public TaggedAccessPath<T> appendElement(Object pathElement) {
            return new TaggedAccessPath<>(root, super.appendElement(pathElement).path);
        }

        public Value access() {
            return access(root);
        }

        @Override
        public String toString() {
            return (root instanceof AbstractParsedPacket ?
                    ((AbstractParsedPacket) root).toShortString() : root.toString()) + Arrays.toString(path);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;

            TaggedAccessPath<?> that = (TaggedAccessPath<?>) o;

            return Objects.equals(root, that.root);
        }

        @Override
        public int hashCode() {
            int result = super.hashCode();
            result = 31 * result + (root != null ? root.hashCode() : 0);
            return result;
        }
    }

    protected final Object[] path;

    public AccessPath() {
        this.path = new Object[0];
    }

    public AccessPath(Object[] path) {
        this.path = path;
    }

    public AccessPath append(String pathElement) {
        return appendElement(pathElement);
    }

    public AccessPath append(int pathElement) {
        return appendElement(pathElement);
    }

    public AccessPath appendElement(Object pathElement) {
        Object[] newPath = new Object[path.length + 1];
        System.arraycopy(path, 0, newPath, 0, path.length);
        newPath[path.length] = pathElement;
        return new AccessPath(newPath);
    }

    @Override
    public Object get(int index) {
        return path[index];
    }

    @Override
    public int size() {
        return path.length;
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        return Arrays.asList(path).toArray(generator);
    }

    @Override
    public Stream<Object> stream() {
        return Arrays.stream(path);
    }

    @Override
    public String toString() {
        return "Path" + Arrays.toString(path);
    }

    public static boolean isListAccess(Object pathElement) {
        assert pathElement instanceof Integer || pathElement instanceof String;
        return pathElement instanceof Integer;
    }

    @SuppressWarnings("unchecked")
    public static <T> Value access(Value value, Object pathElement) {
        if (isListAccess(pathElement)) {
            if (!(value instanceof ListValue<?>)) {
                throw new AssertionError("List access on non list " + value);
            }
        } else {
            if (!(value instanceof CombinedValue)) {
                throw new AssertionError("Field access on non combined value " + value);
            }
        }
        return ((WalkableValue<Object>)value).get(pathElement);
    }

    public Value access(WalkableValue<?> root) {
        Value current = root;
        for (Object o : path) {
            current = access(current, o);
        }
        return current;
    }
}
