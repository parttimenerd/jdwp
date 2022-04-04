package jdwp;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import jdwp.AccessPath.TaggedAccessPath;
import jdwp.Value.BasicValue;
import jdwp.Value.TaggedBasicValue;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Idea collect all values that are contained in a reqest/reply/event
 *
 * Supports chaining contained values together
 */
public class ContainedValues {

    /** parent to ask if something is not found, removes need for cloning */
    private final ContainedValues parent;
    private final Multimap<BasicValue, TaggedBasicValue<?>> map;

    public ContainedValues(Multimap<BasicValue, TaggedBasicValue<?>> map) {
        this(null, map);
    }

    public ContainedValues() {
        this(HashMultimap.create());
    }

    public ContainedValues(ContainedValues parent, Multimap<BasicValue, TaggedBasicValue<?>> map) {
        this.parent = parent;
        this.map = map;
    }

    public ContainedValues(ContainedValues parent) {
        this(parent, HashMultimap.create());
    }

    public void add(TaggedBasicValue<?> value) {
        this.map.put(value.value, value);
    }

    public boolean containsBasicValue(BasicValue value) {
        return map.containsKey(value) || (parent != null && parent.containsBasicValue(value));
    }

    /** Creates a new object with the parent set to the passed parent */
    public ContainedValues setParent(ContainedValues parent) {
        return new ContainedValues(parent, map);
    }

    public Set<TaggedAccessPath<?>> getPaths(BasicValue value) {
        return map.containsKey(value) ? map.get(value).stream().map(v -> v.path).collect(Collectors.toSet()) : Collections.emptySet();
    }
}
