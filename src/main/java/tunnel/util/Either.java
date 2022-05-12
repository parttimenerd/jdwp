package tunnel.util;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Either<L, R> {

    public enum Contains {
        LEFT, RIGHT
    }

    private final Contains contains;
    private final L left;
    private final R right;

    public static <L, R> Either<L, R> left(L left) {
        return new Either<>(Contains.LEFT, left, null);
    }

    public static <L, R> Either<L, R> right(R right) {
        return new Either<>(Contains.RIGHT, null, right);
    }

    public boolean isLeft() {
        return contains == Contains.LEFT;
    }

    public boolean isRight() {
        return contains == Contains.RIGHT;
    }

    public Optional<L> getOptionalLeft() {
        return isLeft() ? Optional.of(left) : Optional.empty();
    }

    public Optional<R> getOptionalRight() {
        return isRight() ? Optional.of(right) : Optional.empty();
    }

    @Override
    public String toString() {
        return isLeft() ? left.toString() : right.toString();
    }

    @SuppressWarnings("unchecked")
    public <T> T get() {
        return isLeft() ? (T)left : (T)right;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Either<?, ?> either = (Either<?, ?>) o;

        if (contains != either.contains) return false;
        return eq(get(), either.get());
    }

    private boolean eq(@Nullable Object val1, @Nullable Object val2) {
        return (val1 == null && val2 == null) || (val1 != null && val1.equals(val2));
    }

    @Override
    public int hashCode() {
        int result = contains != null ? contains.hashCode() : 0;
        result = 31 * result + (left != null ? left.hashCode() : 0);
        result = 31 * result + (right != null ? right.hashCode() : 0);
        return result;
    }
}
