package jdwp;

import jdwp.JDWP.RequestReplyVisitor;
import jdwp.JDWP.RequestVisitor;
import jdwp.JDWP.ReturningRequestVisitor;

public interface Request<R extends Value & Reply> extends ParsedPacket {

    int getCommandSet();

    int getCommand();

    String getCommandName();

    String getCommandSetName();

    boolean onlyReads();

    Packet toPacket(VM vm);

    ReplyOrError<R> parseReply(PacketInputStream ps);

    void accept(RequestVisitor visitor);
    void accept(RequestReplyVisitor visitor, Reply reply);

    Request<R> withNewId(int id);

    default <R> R accept(ReturningRequestVisitor<R> visitor) {
        return null;
    }
}
