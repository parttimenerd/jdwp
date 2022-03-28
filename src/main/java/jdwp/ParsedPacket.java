package jdwp;

public interface ParsedPacket {
    int getId();
    short getFlags();
    Packet toPacket(VM vm);
}
