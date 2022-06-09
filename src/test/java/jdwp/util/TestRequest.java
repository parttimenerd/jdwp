package jdwp.util;

import jdwp.JDWP.CommandVisitor;
import jdwp.JDWP.RequestReplyVisitor;
import jdwp.JDWP.RequestVisitor;
import jdwp.*;
import jdwp.VirtualMachineCmds.VersionRequest;

public class TestRequest extends AbstractTestParsedPacket implements Request<jdwp.util.TestReply> {

    @SafeVarargs
    public TestRequest(int id, Pair<String, ? extends Value>... values) {
        super(Type.REQUEST, id, values);
    }

    @Override
    public int getCommandSet() {
        return VirtualMachineCmds.COMMAND_SET;
    }

    @Override
    public int getCommand() {
        return VersionRequest.COMMAND;
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
    public boolean onlyReads() {
        return true;
    }

    @Override
    public Packet toPacket(VM vm) {
        return null;
    }

    @Override
    public ReplyOrError<jdwp.util.TestReply> parseReply(PacketInputStream ps) {
        return null;
    }

    @Override
    public void accept(RequestVisitor visitor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void accept(RequestReplyVisitor visitor, Reply reply) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void accept(CommandVisitor visitor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Request<TestReply> withNewId(int id) {
        return new TestRequest(id);
    }

    @Override
    public float getCost() {
        return 0;
    }
}
