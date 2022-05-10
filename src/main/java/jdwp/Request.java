package jdwp;

import jdwp.JDWP.RequestReplyVisitor;
import jdwp.JDWP.RequestVisitor;

public interface Request<R extends Value & Reply> extends ParsedPacket {

    int getCommandSet();

    int getCommand();

    boolean onlyReads();

    Packet toPacket(VM vm);

    ReplyOrError<R> parseReply(PacketInputStream ps);

    void accept(RequestVisitor visitor);
    void accept(RequestReplyVisitor visitor, Reply reply);
}
