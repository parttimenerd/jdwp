package jdwp;

import jdwp.JDWP.CommandVisitor;

public interface ParsedPacket {
    int getId();
    short getFlags();
    Packet toPacket(VM vm);
    default PacketInputStream toStream(VM vm) {
        return toPacket(vm).toStream(vm);
    }

    void accept(CommandVisitor visitor);
}
