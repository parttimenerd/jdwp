package jdwp;

import jdwp.Value.BasicValue;
import jdwp.Value.TaggedBasicValue;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Idea collect all values that are contained in a request/reply/event
 *
 * Supports chaining contained values together
 */
public class ContainedValues {

    /** parent to ask if something is not found, removes need for cloning */
    private final ContainedValues parent;
    /** assumption: order is the order in the object */
    private final Map<BasicValue, List<TaggedBasicValue<?>>> map;

    public ContainedValues(Map<BasicValue, List<TaggedBasicValue<?>>> map) {
        this(null, map);
    }

    public ContainedValues() {
        this(new HashMap<>());
    }

    public ContainedValues(ContainedValues parent, Map<BasicValue, List<TaggedBasicValue<?>>> map) {
        this.parent = parent;
        this.map = map;
    }

    public ContainedValues(ContainedValues parent) {
        this(parent, new HashMap<>());
    }

    public void add(TaggedBasicValue<?> value) {
        put(value.value, value);
    }

    private void put(BasicValue value, TaggedBasicValue<?> tagged) {
        map.computeIfAbsent(value, v -> new ArrayList<>()).add(tagged);
    }

    public boolean containsBasicValue(BasicValue value) {
        return map.containsKey(value) || (parent != null && parent.containsBasicValue(value));
    }

    /** Creates a new object with the parent set to the passed parent */
    public ContainedValues setParent(ContainedValues parent) {
        return new ContainedValues(parent, map);
    }

  public Set<AccessPath> getPaths(BasicValue value) {
        return map.containsKey(value) ? map.get(value).stream().map(v -> v.path).collect(Collectors.toSet()) : Collections.emptySet();
    }

    public TaggedBasicValue<?> getFirstTaggedValue(BasicValue value) {
        assert containsBasicValue(value);
        return map.get(value).get(0);
    }

    public Collection<BasicValue> getBasicValues() {
        return map.keySet();
    }

    @Override
    public String toString() {
        if (parent != null) {
            return String.format("ContainedValues{%s; %s}", parent, map);
        }
        return String.format("ContainedValues{%s}", map);
    }
}
