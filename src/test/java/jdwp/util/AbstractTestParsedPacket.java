package jdwp.util;

import jdwp.AbstractParsedPacket;
import jdwp.JDWP.CommandVisitor;
import jdwp.Packet;
import jdwp.VM;
import jdwp.Value;
import jdwp.exception.TunnelException.UnsupportedOperationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public abstract class AbstractTestParsedPacket extends AbstractParsedPacket {

    final Map<String, Value> values;

    @SafeVarargs
    public AbstractTestParsedPacket(Type type, int id, Pair<String, ? extends Value>... values) {
        super(type, id, (type == Type.REPLY ? Packet.REPLY_FLAG : 0));
        this.values = Arrays.stream(values).collect(Collectors.toMap(Pair::first, Pair::second));
    }

    @Override
    public Packet toPacket(VM vm) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void accept(CommandVisitor visitor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> getKeys() {
        return new ArrayList<>(values.keySet());
    }

    @Override
    public Value get(String key) {
        return values.get(key);
    }

    @Override
    public boolean containsKey(String key) {
        return values.containsKey(key);
    }
}
