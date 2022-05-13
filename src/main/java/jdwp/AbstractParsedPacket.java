package jdwp;

import jdwp.Value.CombinedValue;

import java.util.stream.Collectors;

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

    @Override
    public String toCode() {
        if (hasValues()) {
            return String.format("new %s(%d, %s)",
                    getClass().getCanonicalName(),
                    id,
                    getValues().stream().map(p -> p.second.toCode()).collect(Collectors.joining(", ")));
        }
        return String.format("new %s(%d)", getClass().getCanonicalName(), id);
    }

    public CombinedValue asCombined() {
        return this;
    }
}
