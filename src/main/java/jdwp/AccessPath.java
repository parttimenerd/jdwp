package jdwp;

import jdwp.Value.CombinedValue;
import jdwp.Value.ListValue;
import jdwp.Value.WalkableValue;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.NotNull;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.stream.Stream;

/**
 * a path to access a specific value
 *
 * Path elements are either strings (field access) or integers (list access) and these
 * paths are not long (less than 5 elements)
 */
@EqualsAndHashCode(callSuper = false)
public class AccessPath extends AbstractList<Object> implements Comparable<AccessPath> {

    protected final Object[] path;

    public AccessPath() {
        this.path = new Object[0];
    }

    public AccessPath(Object... path) {
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

    public AccessPath prepend(Object[] prefix) {
        Object[] newPath = new Object[prefix.length + path.length];
        System.arraycopy(prefix, 0, newPath, 0, prefix.length);
        System.arraycopy(path, 0, newPath, prefix.length, path.length);
        return new AccessPath(newPath);
    }

    public AccessPath prepend(AccessPath prefix) {
        return prepend(prefix.path);
    }

    /** supports python like indexing with negative numbers */
    @Override
    public Object get(int index) {
        return path[normalizeListIndex(index)];
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
        try {
            for (Object o : path) {
                current = access(current, o);
            }
        } catch (Exception e) {
            throw new AssertionError("Cannot access " + this + " on " + root.toCode(), e);
        }
        return current;
    }

    public Value access(Value root) {
        if (path.length == 0) {
            return root;
        }
        assert root instanceof WalkableValue;
        return access((WalkableValue<?>)root);
    }

    public boolean startsWith(AccessPath other) {
        if (other.path.length > this.path.length) {
            return false;
        }
        for (int i = 0; i < other.path.length; i++) {
            if (!other.path[i].equals(this.path[i])) {
                return false;
            }
        }
        return true;
    }

    public boolean endsWith(Object end) {
        return path[path.length - 1].equals(end);
    }

    public boolean endsWith(Class<?> clazz) {
        return clazz.isInstance(path[path.length - 1]);
    }

    public boolean endsWith(Class<?>... classes) {
        if (path.length < classes.length) {
            return false;
        }
        for (int i = 0; i < classes.length; i++) {
            if (!classes[i].isInstance(path[path.length - classes.length + i])) {
                return false;
            }
        }
        return true;
    }

    public AccessPath removeListAccesses() {
        return new AccessPath(Arrays.stream(path).filter(e -> e instanceof String).toArray());
    }

    /**
     * Returns -1 if both paths either do not differ or differ at more than two indexes
     *
     * Assumes that both paths are equal disregarding indexes
     */
    public int onlyDifferingIndex(AccessPath other) {
        assert other.removeListAccesses().equals(removeListAccesses());
        if (other.path.length != path.length) {
           return -1;
        }
        int differingIndex = -1;
        for (int i = 0; i < path.length; i++) {
            if (!other.path[i].equals(path[i])) {
                if (isListAccess(other.path[i]) && isListAccess(path[i])) {
                    if (differingIndex != -1) {
                        return -1;
                    }
                    differingIndex = i;
                } else {
                    return -1;
                }
            }
        }
        return differingIndex;
    }

    private int normalizeListIndex(int index) {
        return index < 0 ? path.length + index : index;
    }

    /** */
    public AccessPath subPath(int inclusiveStart, int exclusiveEnd) {
        inclusiveStart = normalizeListIndex(inclusiveStart);
        exclusiveEnd = normalizeListIndex(exclusiveEnd);
        Object[] np = new Object[exclusiveEnd - inclusiveStart];
        System.arraycopy(path, inclusiveStart, np, 0, exclusiveEnd - inclusiveStart);
        return new AccessPath(np);
    }

    public AccessPath dropFirstPathElement() {
        return subPath(1, size());
    }

    @Override
    public int compareTo(@NotNull AccessPath o) {
        int lengthComp = Integer.compare(size(), o.size());
        if (lengthComp != 0) {
            return lengthComp;
        }
        for (int i = 0; i < size(); i++) {
            int entryComp;
            var entry = get(i);
            var otherEntry = o.get(i);
            if (entry instanceof String) {
                if (otherEntry instanceof Integer) {
                    return 1;
                }
                entryComp = ((String)entry).compareTo((String)otherEntry);
            } else {
                if (otherEntry instanceof String) {
                    return -1;
                }
                entryComp = ((Integer)entry).compareTo((Integer) otherEntry);
            }
            if (entryComp != 0) {
                return entryComp;
            }
        }
        return 0;
    }

    public String getLastStringElementOrEmpty() {
        for (int i = size() - 1; i >= 0; i--) {
            var element = get(i);
            if (element instanceof String) {
                return (String) element;
            }
        }
        return "";
    }

    public AccessPath dropLast() {
        return subPath(0, size() - 1);
    }
}
