package jdwp;

import jdwp.EventCmds.Events;
import jdwp.EventCollection.NullReply;
import jdwp.JDWP.*;
import jdwp.Value.CombinedValue;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        public String getCommandName() {
            throw new AssertionError();
        }

        @Override
        public String getCommandSetName() {
            throw new AssertionError();
        }

        @Override
        public void accept(ReplyVisitor visitor) {

        }

        @Override
        public List<String> getKeys() {
            return Collections.emptyList();
        }

        @Override
        public Value get(String key) {
            throw new AssertionError();
        }

        @Override
        protected boolean containsKey(String key) {
            return false;
        }

        @Override
        public CombinedValue asCombined() {
            return this;
        }

        @Override
        public Reply withNewId(int id) {
            throw new AssertionError();
        }
    }

    @Override
    default float getCost() {
        return 0;
    }

    @Override
    default boolean isAffectedBy(Request<?> other) {
        return getEvents().stream().anyMatch(e -> e.isAffectedBy(other));
    }

    @Override
    default Set<StateProperty> getAffectedBy() {
        return getEvents().stream().flatMap(e -> e.getAffectedBy().stream()).collect(Collectors.toSet());
    }

    @Override
    default Set<StateProperty> getAffects() {
        return getEvents().stream().flatMap(e -> e.getAffects().stream()).collect(Collectors.toSet());
    }

    @Override
    default boolean isAffectedByTime() {
        return getEvents().stream().anyMatch(e -> e.isAffectedByTime());
    }

    @Override
    default boolean invalidatesReplyCache() {
        return getEvents().stream().anyMatch(e -> e.invalidatesReplyCache());
    }

    @Override
    default long getAffectsBits() {
        return getEvents().stream().mapToLong(EventInstance::getAffectsBits).reduce(0, (a, b) -> a | b);
    }

    @Override
    default long getAffectedByBits() {
        return getEvents().stream().mapToLong(EventInstance::getAffectedByBits).reduce(0, (a, b) -> a | b);
    }
}
