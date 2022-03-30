package jdwp;

import jdwp.EventCollection.NullReply;
import jdwp.Value.CombinedValue;

import java.util.Collections;
import java.util.List;

public interface EventCollection extends Request<NullReply> {

    List<? extends EventInstance> getEvents();

    byte getSuspendPolicy();

    @Override
    default ReplyOrError<NullReply> parseReply(PacketStream ps) {
        throw new AssertionError();
    }

    default int size() {
        return getEvents().size();
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
        public int getCommand() {
            throw new AssertionError();
        }

        @Override
        public int getCommandSet() {
            throw new AssertionError();
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
    }
}
