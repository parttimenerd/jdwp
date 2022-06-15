package tunnel.util;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;

/** used side effects in lambdas */
@AllArgsConstructor
@EqualsAndHashCode
public class Box<T> {
    T value;

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
