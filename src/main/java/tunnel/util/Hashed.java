package tunnel.util;

import org.jetbrains.annotations.NotNull;

/**
 * combines a value with a 64bit hash, using the latter for comparisons
 */
public class Hashed<T> implements Comparable<Hashed<T>> {

    private final long hash;
    private final T value;


    public Hashed(long hash, T value) {
        this.hash = hash;
        this.value = value;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Hashed<?> hashed = (Hashed<?>) o;

        return hashed.value.getClass() == value.getClass() && hashed.hash == hash;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(hash);
    }

    public T get() {
        return value;
    }

    public long hash() {
        return hash;
    }

    @Override
    public int compareTo(@NotNull Hashed<T> o) {
        return Long.compare(hash, o.hash);
    }

    public static <T> Hashed<T> hash(T value, short identifier, long... childHashes) {
        return new Hashed<>(hash(identifier, childHashes), value);
    }

    public static <T> Hashed<T> hash(T value, int identifier, long... childHashes) {
        return new Hashed<>(hash(identifier, childHashes), value);
    }

    public static long hash(short identifier, long... childHashes) {
        // see: https://www.planetmath.org/goodhashtableprimes
        long hash = 1;
        for (var childHash : childHashes) {
            hash = (hash + childHash) * 193;
        }
        return ((long)identifier << 48) & (hash >> 16);
    }

    public static long hash(int identifier, long... childHashes) {
        // see: https://www.planetmath.org/goodhashtableprimes
        long hash = 1;
        for (var childHash : childHashes) {
            hash = (hash + childHash) * 193;
        }
        return ((long)identifier << 32) & (hash >> 32);
    }
}
