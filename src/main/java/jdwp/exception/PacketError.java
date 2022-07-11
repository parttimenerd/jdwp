package jdwp.exception;

import jdwp.Packet;
import jdwp.PacketInputStream;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import org.slf4j.event.Level;

import java.util.Arrays;

/**
 * thrown if the packet could not be parsed properly
 */
public class PacketError extends TunnelException {
    @Getter
    @Setter
    private byte[] content;

    public PacketError(String message) {
        this(message, (byte[]) null);
    }

    public PacketError(String message, byte[] content, Throwable cause) {
        super(Level.ERROR, false, message, cause);
        this.content = content;
    }

    public PacketError(String message, byte[] content) {
        super(Level.ERROR, false, message);
        this.content = content;
    }

    public PacketError(String message, PacketInputStream ps) {
        super(Level.ERROR, false, message);
        this.content = ps.data();
    }

    public PacketError(String message, Throwable cause) {
        super(Level.ERROR, true, message, cause);
    }

    public PacketError(String message, Packet packet) {
        this(message);
        this.content = packet.toByteArray();
    }

    @Override
    public String toString() {
        if (content == null) {
            return super.toString();
        }
        return super.toString() + ": new byte[]" + Arrays.toString(content);
    }

    public boolean hasContent() {
        return content != null;
    }

    @FunctionalInterface
    public interface SupplierWithError<R> {
        R call();
    }

    @SneakyThrows
    public static <R> R call(SupplierWithError<R> supplier, PacketInputStream ps) {
        try {
            return supplier.call();
        } catch (Exception | AssertionError e) {
            throw new PacketError("Failed to parse packet", ps.data(), e);
        }
    }

    public static class NoSuchCommandException extends PacketError {
        public NoSuchCommandException(PacketInputStream ps) {
            super("Unknown command " + ps.command(), ps.data());
        }
    }

    public static class NoSuchCommandSetException extends PacketError {
        public NoSuchCommandSetException(PacketInputStream ps) {
            super("Unknown command set " + ps.commandSet(), ps.data());
        }
    }

    public static class NoSuchSelectKindSetException extends PacketError {
        public NoSuchSelectKindSetException(PacketInputStream ps, byte kind) {
            super("Unknown kind " + kind, ps.data());
        }
    }

    public static class UnknownTagException extends PacketError {
        public UnknownTagException(PacketInputStream ps, byte tag) {
            super("Unknown tag " + tag + " at " + ps.getCursor(), ps.data());
        }
    }
}
