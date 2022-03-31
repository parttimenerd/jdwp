package jdwp;

public abstract class ModifierInstance extends Value.CombinedValue {

    /** Modifier kind selector */
    public final byte kind;

    protected ModifierInstance(byte kind) {
        super(Type.EVENT_MODIFIER);
        this.kind = kind;
    }

    public byte getKind() {
        return kind;
    }
}
