package tunnel.util;

import org.jetbrains.annotations.NotNull;

/**
 * combines a value with a 64bit hash, using the latter for comparisons and equality
 * (but also checking that the class of the stored object is the same for equality)
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

    public static <T> Hashed<T> create(T value, short identifier, long... childHashes) {
        return new Hashed<>(hash(identifier, childHashes), value);
    }

    public static <T> Hashed<T> create(T value, byte identifier1, byte identifier2, long... childHashes) {
        return new Hashed<>(hash(identifier1, identifier2, childHashes), value);
    }

    public static <T> Hashed<T> create(T value, int identifier, long... childHashes) {
        return new Hashed<>(hash(identifier, childHashes), value);
    }

    public static long hash(short identifier, long... childHashes) {
        return ((long)identifier << 48) | (0x0000ffffffffffffL & hashTo48Bit(hash(childHashes)));
    }

    public static long hash(byte identifier1, byte identifier2, long... childHashes) {
        return hash((short)(((short)(identifier1 << 8) & 0xff00) | (short)(((int)identifier2) & 0x00ff)), childHashes);
    }

    public static long hash(int identifier, long... childHashes) {
        return ((long)identifier << 32) | ((long)hashToInt(hash(childHashes)) & 0x00000000ffffffffL);
    }

    public static long hash(long... childHashes) {
        // see https://www.planetmath.org/goodhashtableprimes for suitable numbers
        long hash = 1;
        for (var childHash : childHashes) {
            hash = (hash + childHash) * 193;
        }
        return hash;
    }

    private static int hashToInt(long hash) {
        return (int)hash ^ (int)(hash >> 32);
    }

    private static short hashToShort(long hash) {
        return (short) ((short)hash ^ (short)(hash >> 16) ^ (short)(hash >> 32) ^ (short)(hash >> 48));
    }

    private static long hashTo48Bit(long hash) {
        return 0x0000ffffffffffffL & (((long)hashToShort(hash) << 32) | ((long)hashToInt(hash)));
    }

    @Override
    public String toString() {
        return String.format("%s: %s", value, hash);
    }
}