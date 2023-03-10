package jdwp.util;

import jdwp.JDWP.CommandVisitor;
import jdwp.JDWP.Metadata;
import jdwp.JDWP.ReplyVisitor;
import jdwp.*;
import jdwp.VirtualMachineCmds.IDSizesRequest;
import jdwp.exception.TunnelException.UnsupportedOperationException;

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
        return "T";
    }

    @Override
    public String getCommandSetName() {
        return "T";
    }

    @Override
    public boolean isAffectedBy(Request<?> other) {
        return false;
    }

    @Override
    public void accept(ReplyVisitor visitor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParsedPacket withNewId(int id) {
        return new TestReply(id);
    }

    @Override
    public Metadata getMetadata() {
        return IDSizesRequest.METADATA;
    }
}
