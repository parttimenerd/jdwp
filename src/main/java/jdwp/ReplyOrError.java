package jdwp;

import jdwp.JDWP.CommandVisitor;
import jdwp.Value.CombinedValue;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

public class ReplyOrError<R extends Reply> implements ParsedPacket {
    private final int id;
    private final short flags;
    @Nullable
    private final R reply;
    private final short errorCode;

    public ReplyOrError(int id, short flags, @Nullable R reply) {
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

    public ReplyOrError(R reply) {
        this(reply.getId(), Packet.Reply, reply);
    }

    public boolean isError() {
        return reply == null;
    }

    public boolean isReply() {
        return reply != null;
    }

    public short getErrorCode() {
        assert isError();
        return errorCode;
    }

    public R getReply() {
        assert isReply();
        return reply;
    }

    @Override
    public String toString() {
        if (isError()) {
            return "ErrorReply(" + errorCode + ")";
        }
        return reply.toString();
    }

    @Override
    public String toCode() {
        if (isError()) {
            return String.format("new ReplyOrError<>(%d, %d)", id, errorCode);
        }
        return String.format("new ReplyOrError<>(%d, %s)", id, reply.toCode());
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
        if (isError()) {
            Packet packet = new Packet();
            packet.flags = flags;
            packet.id = id;
            packet.errorCode = errorCode;
            return packet;
        }
        return reply.toPacket(vm);
    }

    @Override
    public void accept(CommandVisitor visitor) {
        if (reply != null) {
            reply.accept(visitor);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ReplyOrError<?> that = (ReplyOrError<?>) o;

        if (id != that.id) return false;
        if (flags != that.flags) return false;
        if (errorCode != that.errorCode) return false;
        return Objects.equals(reply, that.reply);
    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + (int) flags;
        result = 31 * result + (reply != null ? reply.hashCode() : 0);
        result = 31 * result + (int) errorCode;
        return result;
    }

    @Override
    public CombinedValue asCombined() {
        if (reply != null) {
            return reply.asCombined();
        }
        return null;
    }
}
