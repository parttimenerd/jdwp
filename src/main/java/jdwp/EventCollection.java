package jdwp;

import jdwp.EventCmds.Events;
import jdwp.EventCollection.NullReply;
import jdwp.JDWP.CommandVisitor;
import jdwp.JDWP.ReplyVisitor;
import jdwp.JDWP.RequestReplyVisitor;
import jdwp.JDWP.RequestVisitor;
import jdwp.Value.CombinedValue;

import java.util.Collections;
import java.util.List;

public interface EventCollection extends Request<NullReply>, Reply {

    List<? extends EventInstance> getEvents();

    byte getSuspendPolicy();

    @Override
    default ReplyOrError<NullReply> parseReply(PacketInputStream ps) {
        throw new AssertionError();
    }

    default int size() {
        return getEvents().size();
    }

    @Override
    default boolean onlyReads() {
        return false;
    }

    @Override
    default void accept(CommandVisitor visitor) {
        visitor.visit((Events) this);
    }

    @Override
    default void accept(RequestVisitor visitor) {
    }

    @Override
    default void accept(RequestReplyVisitor visitor, Reply reply) {
    }

    class NullReply extends CombinedValue implements Reply {

        protected NullReply() {
            super(Type.VOID);
        }

        @Override
        public int getId() {
            throw new AssertionError();
        }

        @Override
        public short getFlags() {
            throw new AssertionError();
        }

        @Override
        public Packet toPacket(VM vm) {
            throw new AssertionError();
        }

        @Override
        public void accept(CommandVisitor visitor) {

        }

        @Override
        public int getCommand() {
            throw new AssertionError();
        }

        @Override
        public int getCommandSet() {
            throw new AssertionError();
        }

        @Override
        public void accept(ReplyVisitor visitor) {

        }

        @Override
        List<String> getKeys() {
            return Collections.emptyList();
        }

        @Override
        Value get(String key) {
            throw new AssertionError();
        }

        @Override
        boolean containsKey(String key) {
            return false;
        }

        @Override
        public CombinedValue asCombined() {
            return this;
        }
    }
}
