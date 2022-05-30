package jdwp;

import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;

import java.util.Arrays;

/** thrown if the packet could not be parsed properly */
public class PacketError extends RuntimeException {
    @Getter
    @Setter
    private byte[] content;

    public PacketError(String message) {
        super(message);
    }

    public PacketError(String message, byte[] content, Throwable cause) {
        super(message, cause);
        this.content = content;
    }

    public PacketError(String message, byte[] content) {
        super(message);
        this.content = content;
    }

    public PacketError(String message, Throwable cause) {
        super(message, cause);
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
}
