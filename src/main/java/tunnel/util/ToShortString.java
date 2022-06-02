package tunnel.util;

public interface ToShortString {

    /** Produces a minimal string, consisting the simple name by default */
    default String toShortString() {
        return getClass().getSimpleName();
    }
}
