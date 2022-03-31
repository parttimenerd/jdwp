package jdwp;

import jdwp.JDWP.ReplyVisitor;
import jdwp.JDWP.RequestVisitor;

public interface Reply extends ParsedPacket {
    int getCommand();
    int getCommandSet();

    /** Thrown if the id of a reply does not match the id of the request which should parse it*/
    class IdMismatchException extends RuntimeException {
        public IdMismatchException(int expectedId, int actualId) {
            super(String.format("Id mismatch for reply, expected %d but got %d", expectedId, actualId));
        }
    }

    void accept(ReplyVisitor visitor);
}
