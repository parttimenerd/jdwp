package jdwp;

public interface Request<R extends Value & Reply> extends ParsedPacket {

    int getCommandSet();

    int getCommand();

    boolean onlyReads();

    Packet toPacket(VM vm);

    ReplyOrError<R> parseReply(PacketStream ps);
}
