package jdwp;

import jdwp.JDWP.ReplyVisitor;
import jdwp.JDWP.WithMetadata;

public interface Reply extends ParsedPacket, WithMetadata {
    int getCommand();

    int getCommandSet();

    String getCommandName();

    String getCommandSetName();

    /** Thrown if the id of a reply does not match the id of the request which should parse it*/
    class IdMismatchException extends RuntimeException {
        public IdMismatchException(int expectedId, int actualId) {
            super(String.format("Id mismatch for reply, expected %d but got %d", expectedId, actualId));
        }
    }

    boolean isAffectedBy(Request<?> other);

    void accept(ReplyVisitor visitor);

    default boolean hasNullReference() {
        return asCombined().hasNullReference();
    }
}
