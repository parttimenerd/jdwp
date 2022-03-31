package jdwp;

import jdwp.JDWP.CommandVisitor;

public interface ParsedPacket {
    int getId();
    short getFlags();
    Packet toPacket(VM vm);

    void accept(CommandVisitor visitor);
}
