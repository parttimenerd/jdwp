package jdwp;

import jdwp.JDWP.CommandVisitor;
import jdwp.Value.CombinedValue;
import tunnel.util.ToCode;

public interface ParsedPacket extends ToCode {
    int getId();
    short getFlags();
    Packet toPacket(VM vm);
    default PacketInputStream toStream(VM vm) {
        return toPacket(vm).toStream(vm);
    }

    void accept(CommandVisitor visitor);

    String toCode();

    CombinedValue asCombined();

    /** just the name of the command set, the class itself and the id */
    default String toShortString() {
        var names = getClass().getCanonicalName().split("\\.");
        return String.format("%s.%s(%d)", names[names.length - 2], names[names.length - 1], getId());
    }

    ParsedPacket withNewId(int id);
}
