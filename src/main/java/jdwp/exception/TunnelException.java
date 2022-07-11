package jdwp.exception;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import tunnel.synth.Partitioner.Partition;

public class TunnelException extends RuntimeException {

    @Getter
    private final Level level;
    /**
     * is this exception expected and therefore expected to be handled properly
     */
    @Getter
    private final boolean expected;

    public TunnelException(Level level, boolean expected, String message) {
        super(message);
        this.level = level;
        this.expected = expected;
    }

    public TunnelException(Level level, boolean expected, String message, Throwable cause) {
        super(message, cause);
        this.level = level;
        this.expected = expected;
    }

    public TunnelException log(Logger logger, String message) {
        switch (level) {
            case ERROR:
                logger.error(message, this);
                break;
            case WARN:
                logger.warn(message, this);
                break;
            case INFO:
                logger.info(message, this);
                break;
            case DEBUG:
                logger.debug(message, this);
                break;
            case TRACE:
                logger.trace(message, this);
                break;
        }
        return this;
    }

    protected void logMessage(Logger logger, String message) {
        switch (level) {
            case ERROR:
                logger.error(message);
                break;
            case WARN:
                logger.warn(message);
                break;
            case INFO:
                logger.info(message);
                break;
            case DEBUG:
                logger.debug(message);
                break;
            case TRACE:
                logger.trace(message);
                break;
        }
    }

    public TunnelException log(Logger logger) {
        return log(logger, getMessage());
    }

    public static class ReflectiveCreationException extends TunnelException {
        public ReflectiveCreationException(String message, Throwable cause) {
            super(Level.ERROR, true, message);
        }

        public ReflectiveCreationException(String message) {
            this(message, null);
        }
    }

    public static class UnsupportedOperationException extends TunnelException {
        public UnsupportedOperationException() {
            super(Level.ERROR, false, "");
        }
    }

    public static class JNITypeSignatureException extends TunnelException {
        public JNITypeSignatureException(String message) {
            super(Level.INFO, true, message);
        }
    }

    public static class SynthesisException extends TunnelException {
        public SynthesisException(Partition partition, Throwable cause) {
            super(Level.INFO, true, "Failed to synthesize program for partition: " + partition.toCode(), cause);
        }
    }

    public static class ProgramHashesException extends TunnelException {
        public ProgramHashesException(String message) {
            super(Level.INFO, true, message);
        }

        public ProgramHashesException(String message, Throwable cause) {
            super(Level.INFO, true, message, cause);
        }
    }

    public static class ParserException extends TunnelException {
        public ParserException(String message) {
            super(Level.INFO, true, message);
        }

        public ParserException(String message, Throwable cause) {
            super(Level.INFO, true, message, cause);
        }
    }

    public static class MergeException extends TunnelException {
        public MergeException(String message) {
            super(Level.INFO, true, message);
        }

        public MergeException(String message, Throwable cause) {
            super(Level.INFO, true, message, cause);
        }
    }

    public static class FunctionsException extends TunnelException {
        public FunctionsException(String message) {
            super(Level.INFO, true, message);
        }

        public FunctionsException(String message, Throwable cause) {
            super(Level.INFO, true, message, cause);
        }
    }

    public static class UnknownVariableException extends TunnelException {
        public UnknownVariableException(String variable) {
            super(Level.INFO, true, "Unknown variable: " + variable);
        }
    }
}
