package jdwp;

public abstract class EventInstance extends Value.CombinedValue {

    /** Event kind selector */
    public final byte kind;
    /** Request that generated event (or 0 if this event is automatically generated)   */
    public final int id;

    protected EventInstance(byte kind, int id) {
        super(Type.EVENT);
        this.kind = kind;
        this.id = id;
    }

    public int getId() {
        return id;
    }

    public byte getKind() {
        return kind;
    }
}
