package jdwp;

import io.reactivex.annotations.Nullable;

import java.util.Optional;

public class ReplyOrError<R extends Reply> implements ParsedPacket {
    private final int id;
    private final short flags;
    @Nullable
    private final R reply;
    private final short errorCode;

    public ReplyOrError(int id, short flags, R reply) {
        this.reply = reply;
        this.errorCode = 0;
        this.id = id;
        this.flags = flags;
    }

    public ReplyOrError(int id, short flags, short errorCode) {
        this.reply = null;
        this.errorCode = errorCode;
        this.id = id;
        this.flags = flags;
    }

    public ReplyOrError(int id, short errorCode) {
        this(id, Packet.Reply, errorCode);
    }

    public ReplyOrError(int id, R reply) {
        this(id, Packet.Reply, reply);
    }

    public boolean hasError() {
        return reply == null;
    }

    public boolean hasReply() {
        return reply != null;
    }

    public short getErrorCode() {
        assert hasError();
        return errorCode;
    }

    public R getReply() {
        assert hasReply();
        return reply;
    }

    @Override
    public String toString() {
        if (hasError()) {
            return "ErrorReply(" + errorCode + ")";
        }
        return reply.toString();
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public short getFlags() {
        return flags;
    }

    @Override
    public Packet toPacket(VM vm) {
        if (hasError()) {
            Packet packet = new Packet();
            packet.flags = (short)flags;
            packet.id = id;
            packet.errorCode = (short)errorCode;
            return packet;
        }
        return reply.toPacket(vm);
    }
}
