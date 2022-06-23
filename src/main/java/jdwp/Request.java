package jdwp;

import jdwp.JDWP.RequestReplyVisitor;
import jdwp.JDWP.RequestVisitor;
import jdwp.JDWP.ReturningRequestVisitor;
import jdwp.JDWP.StateProperty;

import java.util.Set;

public interface Request<R extends Value & Reply> extends ParsedPacket {

    int getCommandSet();

    int getCommand();

    String getCommandName();

    String getCommandSetName();

    /** is this a cacheable request? */
    boolean onlyReads();

    Set<StateProperty> getAffectedBy();

    Set<StateProperty> getAffects();

    default boolean isAffectedBy(StateProperty property) {
        return getAffectedBy().contains(property);
    }

    default boolean invalidatesReplyCache() {
        return !onlyReads();
    }

    /**
     * The average number of milliseconds it takes to execute on my machine (the machine that the cost.csv file
     * is created on).
     *
     * A high value is a value of at least 1, these should therefore be omitted in only similar programs.
     * A value of 0 is used to note that this command was not observed in the evaluation runs and therefore no
     * data is available.
     */
    float getCost();

    Packet toPacket(VM vm);

    ReplyOrError<R> parseReply(PacketInputStream ps);

    long getAffectsBits();

    long getAffectedByBits();

    boolean isAffectedBy(Request<?> other);

    boolean isAffectedByTime();

    void accept(RequestVisitor visitor);
    void accept(RequestReplyVisitor visitor, Reply reply);

    Request<R> withNewId(int id);

    default <R> R accept(ReturningRequestVisitor<R> visitor) {
        return null;
    }
}
