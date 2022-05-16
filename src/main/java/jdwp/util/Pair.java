package jdwp.util;

import java.util.stream.Stream;

/**
 * Simple implementation of an immutable pair
 */
public class Pair<T, V> {
    public final T first;

    public final V second;

    public Pair(T first, V second) {
        this.first = first;
        this.second = second;
    }

    @Override
    public int hashCode() {
        return first.hashCode() ^ second.hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Pair)){
            return false;
        }
        Pair<?, ?> pair = (Pair<?, ?>)obj;
        return pair.first == this.first && pair.second == this.second;
    }

    public Stream<T> firstStream(){
        return Stream.of(first);
    }

    @Override
    public String toString() {
        return String.format("(%s,%s)", first, second);
    }

    public T first(){
        return first;
    }

    public V second() {
        return second;
    }

    public static <T, V> Pair<T, V> p(T t, V v) {
        return new Pair<>(t, v);
    }
}