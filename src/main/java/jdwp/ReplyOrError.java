package jdwp;

import jdwp.JDWP.CommandVisitor;
import jdwp.JDWP.Metadata;
import jdwp.JDWP.ReplyVisitor;
import jdwp.JDWP.StateProperty;
import jdwp.Value.CombinedValue;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import tunnel.util.ToShortString;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class ReplyOrError<R extends Reply> extends CombinedValue implements ToShortString, Reply {
    private final int id;
    private final short flags;
    @Getter
    private final Metadata metadata;
    @Nullable
    private final R reply;
    private final short errorCode;

    @Override
    public boolean onlyReads() {
        return metadata.onlyReads();
    }

    @Override
    public float getCost() {
        return getMetadata().getCost();
    }

    @Override
    public boolean invalidatesReplyCache() {
        return getMetadata().invalidatesReplyCache();
    }

    @Override
    public Set<StateProperty> getAffectedBy() {
        return getMetadata().getAffectedBy();
    }

    @Override
    public Set<StateProperty> getAffects() {
        return getMetadata().getAffects();
    }

    @Override
    public List<Integer> getReplyLikeErrors() {
        return getMetadata().getReplyLikeErrors();
    }

    @Override
    public int getCommandSet() {
        return getMetadata().getCommandSet();
    }

    @Override
    public int getCommand() {
        return getMetadata().getCommand();
    }

    @Override
    public long getAffectsBits() {
        return getMetadata().getAffectsBits();
    }

    @Override
    public long getAffectedByBits() {
        return getMetadata().getAffectedByBits();
    }

    @Override
    public boolean isAffectedByTime() {
        return false;
    }

    public boolean isReplyLikeError(int errorCode) {
        return getMetadata().isReplyLikeError(errorCode);
    }

    public ReplyOrError(int id, short flags, R reply) {
        super(Type.OBJECT);
        if (reply instanceof ReplyOrError) {
            throw new IllegalArgumentException("reply cannot be a ReplyOrError");
        }
        this.reply = Objects.requireNonNull(reply);
        this.errorCode = 0;
        this.id = id;
        this.flags = flags;
        this.metadata = reply.getMetadata();
    }

    public ReplyOrError(int id, short flags, Metadata metadata, int errorCode) {
        super(Type.OBJECT);
        if (errorCode == 0) {
            throw new AssertionError("error code must not be 0");
        }
        this.reply = null;
        this.errorCode = (short)errorCode;
        this.id = id;
        this.flags = flags;
        this.metadata = metadata;
    }

    public ReplyOrError(int id, Metadata metadata, int errorCode) {
        this(id, Packet.REPLY_FLAG, metadata, errorCode);
    }

    public ReplyOrError(Request<?> request, int errorCode) {
        this(request.getId(), Packet.REPLY_FLAG, request.getMetadata(), errorCode);
    }

    public ReplyOrError(int id, R reply) {
        this(id, Packet.REPLY_FLAG, reply);
    }

    public ReplyOrError(R reply) {
        this(reply.getId(), Packet.REPLY_FLAG, reply);
    }

    public boolean isError() {
        return reply == null;
    }

    public boolean isNonReplyLikeError() {
        return reply == null && !isReplyLikeError(errorCode);
    }

    public boolean isReplyLikeError() {
        return reply == null && isReplyLikeError(errorCode);
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
    public List<String> getKeys() {
        return reply == null ? List.of() : reply.asCombined().getKeys();
    }

    @Override
    public Value get(String key) {
        return reply == null ? null : reply.asCombined().get(key);
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
            return String.format("new ReplyOrError<>(%d, %s, %d)", id, metadata.toCode(), errorCode);
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
    public PacketInputStream toStream(VM vm) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void accept(CommandVisitor visitor) {
        if (reply != null) {
            reply.accept(visitor);
        }
    }

    @Override
    public boolean containsKey(String key) {
        return reply != null && reply.asCombined().containsKey(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ReplyOrError<?> that = (ReplyOrError<?>) o;
        if (metadata.getCommandSet() != that.metadata.getCommandSet()) return false;
        if (metadata.getCommand() != that.metadata.getCommand()) return false;
        if (flags != that.flags) return false;
        if (errorCode != that.errorCode) return false;
        return Objects.equals(reply, that.reply);
    }

    @Override
    public int hashCode() {
        return Objects.hash(flags, reply, errorCode, getCommand(), getCommandSet());
    }

    @Override
    public CombinedValue asCombined() {
        if (reply != null) {
            return reply.asCombined();
        }
        return this;
    }

    @Override
    public ParsedPacket withNewId(int id) {
        return isReply() ?
                new ReplyOrError<>(id, flags, (Reply)getReply().withNewId(id)) :
                new ReplyOrError<>(id, flags, metadata, errorCode);
    }

    @Override
    public String toShortString() {
        return isReply() ? getReply().toShortString() :
                String.format("Error(%d,%d%s)", getId(), getErrorCode(), isReplyLikeError() ? ",reply" : "");
    }

    public boolean isReplyLike() {
        return isReply() || isReplyLikeError();
    }

    @Override
    public String getCommandName() {
        return JDWP.getCommandName((byte)getCommandSet(), (byte)getCommand());
    }

    @Override
    public String getCommandSetName() {
        return JDWP.getCommandSetName((byte)getCommandSet());
    }

    @Override
    public void accept(ReplyVisitor visitor) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public boolean isAffectedBy(Request<?> other) {
        return (metadata.getAffectedByBits() & other.getMetadata().getAffectedByBits()) != 0;
    }
}
