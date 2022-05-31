package jdwp;

import jdwp.Value.CombinedValue;
import jdwp.util.Pair;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @inheritDoc
 *
 * Additions for AbstractParsedPacket:
 * - the required constructor is X(int id, Map<String, Value>)
 *
 * the packet id is ignored for equals and hashCode
 */
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

    /**
     * Create an object of the passed class with the given constructor arguments, inverse of {@link #getValues()}
     *
     * assumes that all constructor arguments are present
     *
     * uses reflection
     */
    public static <T extends AbstractParsedPacket> T create(int id, Class<T> klass,
                                                            List<Pair<String, Value>> arguments) {
        return create(id, klass, arguments.stream().collect(Collectors.toMap(p -> p.first, p -> p.second)));
    }

    public static <T extends AbstractParsedPacket> T create(int id, Class<T> klass,
                                                            Map<String, Value> arguments) {
        try {
            return klass.getConstructor(int.class, Map.class).newInstance(id, arguments);
        } catch (InstantiationException | IllegalAccessException |
                InvocationTargetException | NoSuchMethodException e) {
            throw new AssertionError(String.format("Cannot create packet (id=%d, class=%s, arguments=%s)",
                    id, klass, arguments), e);
        }
    }

    /**
     * Create an object for a list of tagged values, inverse of {@link #getTaggedValues()}
     */
    public static <T extends AbstractParsedPacket> T createForTagged(int id, Class<T> klass,
                                                                     Stream<TaggedBasicValue<?>> taggedArguments) {
        return CombinedValue.createForTagged(klass, taggedArguments, (k, values) -> create(id, k, values));
    }
}
