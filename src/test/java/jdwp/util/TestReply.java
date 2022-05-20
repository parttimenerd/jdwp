package jdwp.util;

import jdwp.JDWP.CommandVisitor;
import jdwp.JDWP.ReplyVisitor;
import jdwp.Packet;
import jdwp.Reply;
import jdwp.VM;
import jdwp.Value;

public class TestReply extends AbstractTestParsedPacket implements Reply {

    @SafeVarargs
    public TestReply(int id, Pair<String, ? extends Value>... values) {
        super(Type.REPLY, id, values);
    }

    @Override
    public Packet toPacket(VM vm) {
        return null;
    }

    @Override
    public void accept(CommandVisitor visitor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String toCode() {
        return null;
    }

    @Override
    public int getCommand() {
        return 0;
    }

    @Override
    public int getCommandSet() {
        return 0;
    }

    @Override
    public String getCommandName() {
        return "";
    }

    @Override
    public String getCommandSetName() {
        return "";
    }

    @Override
    public void accept(ReplyVisitor visitor) {
        throw new UnsupportedOperationException();
    }
}
