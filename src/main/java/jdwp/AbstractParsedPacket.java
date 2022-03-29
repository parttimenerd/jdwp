package jdwp;

import jdwp.Value.CombinedValue;

public abstract class AbstractParsedPacket extends CombinedValue implements ParsedPacket {

    public final int id;
    public final short flags;

    protected AbstractParsedPacket(Type type, int id, short flags) {
        super(type);
        this.id = id;
        this.flags = flags;
    }

    @Override
    public int getId() {
        return id;
    }

    @Override
    public short getFlags() {
        return flags;
    }
}
