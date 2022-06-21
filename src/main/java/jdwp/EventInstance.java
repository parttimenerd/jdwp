package jdwp;

import jdwp.EventCmds.Events.VMStart;
import jdwp.JDWP.StateProperty;

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

    /**
     * Whether the reply cache should be invalidated when observing this request
     */
    public boolean invalidatesReplyCache() {
      return VMStart.INVALIDATES_REPLY_CACHE;
    }

    /**
     * List of state properties (time, everything, nothing, classpath, classloaders, modules, classes, methods, fields, threads, instances, events, currentsuspension, framevalues, classloadtime, threadloadtime, garbagecollectiontime) that are affected the validity by this request
     */
    public java.util.Set<StateProperty> getAffects() {
      return VMStart.AFFECTS;
    }

    /**
     * Can the request be issued multiple times without affecting the JVM from debugger's perpective
     */
    public boolean onlyReads() {
      return VMStart.ONLY_READS;
    }

    /**
     * List of state properties (time, everything, nothing, classpath, classloaders, modules, classes, methods, fields, threads, instances, events, currentsuspension, framevalues, classloadtime, threadloadtime, garbagecollectiontime) that affect the validity of the reply if they change
     */
    public java.util.Set<StateProperty> getAffectedBy() {
      return VMStart.AFFECTED_BY;
    }

    public long getAffectsBits() {
      return 0b011111111111111001L;
    }

    public long getAffectedByBits() {
      return 0b011111111111111001L;
    }

    public boolean isAffectedBy(Request<?> other) {
      return (getAffectedByBits() & other.getAffectsBits()) != 0;
    }

    public boolean isAffectedByTime() {
      return true;
    }
}
