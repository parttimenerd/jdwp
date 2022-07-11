package jdwp.exception;

import jdwp.Value;
import lombok.Getter;
import org.slf4j.event.Level;

public class ValueAccessException extends TunnelException {

    @Getter
    private final Value value;

    public ValueAccessException(String message, Value value) {
        super(Level.INFO, true, message);
        this.value = value;
    }

    public ValueAccessException(Level level, String message, Value value) {
        super(level, true, message);
        this.value = value;
    }

    public ValueAccessException(Level level, String message, Value value, Throwable cause) {
        super(level, true, message, cause);
        this.value = value;
    }

    public ValueAccessException(String message, Value value, Throwable cause) {
        this(Level.INFO, message, value, cause);
    }

    public static class NoSuchFieldException extends ValueAccessException {
        public NoSuchFieldException(Value value, String field) {
            super(String.format("Unknown field %s in value %s", field, value), value);
        }
    }

    public static class IncorrectAccessException extends ValueAccessException {
        public IncorrectAccessException(Value value, String message) {
            super(Level.WARN, String.format(message + " in value " + value), value);
        }
    }
}
