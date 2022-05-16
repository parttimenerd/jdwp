package tunnel.synth;


import jdwp.ContainedValues;
import jdwp.EventCmds.Events;
import jdwp.Reply;
import jdwp.Request;
import jdwp.Value.BasicValue;
import jdwp.Value.TaggedBasicValue;
import jdwp.util.Pair;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.Partitioner.Partition;
import tunnel.util.Either;

import java.util.*;
import java.util.stream.Collectors;

import static jdwp.util.Pair.p;

/**
 * This is used to represent the interdependencies and for layering the packets.
 */
@Getter
public class DependencyGraph {

    @Getter
    public static class Edge {
        private final Node target;
        /**
         * order is undefined
         */
        private final List<TaggedBasicValue<?>> usedValues;

        public Edge(Node target, List<TaggedBasicValue<?>> usedValues) {
            this.target = target;
            this.usedValues = usedValues;
        }

        public boolean isCauseNodeEdge() {
            return target.origin == null;
        }

        public int getTargetId() {
            return target.id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null) return false;
            if (o instanceof Node) {
                return o.equals(target);
            }
            if (o instanceof Edge) {
                return ((Edge) o).getTarget().equals(target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return 31 * getTargetId();
        }

        @Override
        public String toString() {
            return "Edge{" +
                    "target=" + target +
                    ", usedValues=" + usedValues +
                    '}';
        }
    }

    /**
     * edge and node are interchangeable in situations where only equals and hashCode are used
     */
    @Getter
    public static class Node {
        private final int id;
        private @Nullable final Pair<Request<?>, Reply> origin;
        private final Set<Edge> dependsOn = new HashSet<>();

        public Node(int id, @Nullable Pair<Request<?>, Reply> origin) {
            this.id = id;
            this.origin = origin;
        }

        /**
         * for the cause node
         */
        public Node() {
            this(-1, null);
        }

        @Override
        public String toString() {
            return "Node{" +
                    "id=" + id +
                    ", origin=" + origin +
                    ", dependsOn=" + dependsOn.stream().map(n -> n.getTargetId() + "")
                    .collect(Collectors.joining(";")) +
                    '}';
        }

        boolean isCauseNode() {
            return origin == null;
        }

        boolean dependsOnCause() {
            return dependsOn.stream().anyMatch(Edge::isCauseNodeEdge);
        }

        boolean addEdge(Edge edge) {
            return dependsOn.add(edge);
        }

        boolean dependsOn(Node node) {
            return dependsOn.contains(node);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null) return false;
            if (o instanceof Node) {
                return ((Node) o).id == id;
            }
            if (o instanceof Edge) {
                return ((Edge) o).getTargetId() == id;
            }
            return false;
        }

        @Override
        public int hashCode() {
            return 31 * id;
        }

        public Set<Node> getDependsOnNodes() {
            return dependsOn.stream().map(Edge::getTarget).collect(Collectors.toSet());
        }

        public @Nullable Pair<Request<?>, Reply> getOrigin() {
            return origin;
        }
    }

    private final @Nullable Node causeNode;
    private final @Nullable Either<Request<?>, Events> cause;
    /**
     * does not contain the cause node
     */
    private final Map<Integer, Node> nodes = new HashMap<>();

    private DependencyGraph(@Nullable Node causeNode, @Nullable Either<Request<?>, Events> cause) {
        this.causeNode = causeNode;
        this.cause = cause;
        if ((causeNode == null) != (cause == null)) {
            throw new AssertionError();
        }
    }

    public DependencyGraph(@Nullable Either<Request<?>, Events> cause) {
        this(cause != null ? new Node() : null, cause);
    }

    /**
     * On multiple paths for the same value in the same object: choose the first,
     * the assumption is that this is probably the most relevant path
     * <p>
     * ... from different objects: prefer the paths of the object that is first in the partition and therefore
     * was first received and thereby typically in a lower layer
     */
    public static DependencyGraph calculate(Partition partition) {
        // collect the values
        ContainedValues causeValues = partition.hasCause() ? partition.getCausePacket().getContainedValues() : new ContainedValues();
        Map<Pair<Request<?>, Reply>, Pair<ContainedValues, ContainedValues>> containedValues = new HashMap<>();
        for (Pair<Request<?>, Reply> p : partition) {
            containedValues.put(p, p(p.first.asCombined().getContainedValues(), p.second.asCombined().getContainedValues()));
        }
        DependencyGraph graph = new DependencyGraph(partition.getCause());

        boolean hasRequestCause = partition.hasCause() && partition.getCause().isLeft();
        if (hasRequestCause && !(partition.get(0).first().equals(partition.getCause().getLeft()))) {
            throw new AssertionError();
        }

        // look for requests that only depend on the cause or values not in the set
        for (int i = 0; i < partition.size(); i++) {
            var origin = partition.get(i);
            ContainedValues requestValues = containedValues.get(origin).first; // use the request
            Map<BasicValue, TaggedBasicValue<?>> usedCauseValues = requestValues.getBasicValues().stream()
                    .filter(causeValues::containsBasicValue)
                    .collect(Collectors.toMap(v -> v, causeValues::getFirstTaggedValue));
            Map<Pair<Request<?>, Reply>, List<TaggedBasicValue<?>>> dependsOn = new HashMap<>();
            for (BasicValue value : requestValues.getBasicValues()) {
                if (usedCauseValues.containsKey(value)) { // cause has the highest priority
                    continue;
                }
                // cannot use replies of requests that were sent after receiving origin
                System.out.println(value);
                for (int j = 0; j < i; j++) {
                    var other = partition.get(j);
                    var otherContainedValues = containedValues.get(other).second; // use the reply
                    if (otherContainedValues.containsBasicValue(value)) {
                        dependsOn.computeIfAbsent(other, v -> new ArrayList<>())
                                .add(otherContainedValues.getFirstTaggedValue(value));
                    }
                }
            }

            graph.add(origin, dependsOn, usedCauseValues.values());
        }

        return graph;
    }

    void add(Pair<Request<?>, Reply> origin, Map<Pair<Request<?>, Reply>, List<TaggedBasicValue<?>>> dependsOn,
             Collection<TaggedBasicValue<?>> usedCauseValues) {
        var node = getNode(origin);
        node.dependsOn.addAll(dependsOn.entrySet().stream()
                .map(e -> new Edge(getNode(e.getKey()), e.getValue())).collect(Collectors.toList()));
        if (causeNode != null && usedCauseValues.size() > 0) {
            node.dependsOn.add(new Edge(causeNode, new ArrayList<>(usedCauseValues)));
        }
    }

    /**
     * get node or create if non existent
     */
    @SuppressWarnings("unchecked")
    Node getNode(Pair<? extends Request<?>, ? extends Reply> origin) {
        assert origin.first.getId() == origin.second.getId();
        return nodes.computeIfAbsent(origin.first.getId(), o -> new Node(origin.first.getId(), (Pair<Request<?>, Reply>) origin));
    }

    Node getNode(int id) {
        return id == -1 ? causeNode : nodes.get(id);
    }

    /**
     * Layer 0 nodes only depend on the cause node (its request or event values),
     * layers above only depend on the layers below
     * <p>
     * Layer 0 contains the cause node if present
     * <p>
     * Interestingly, I coded a similar code during my PhD:
     * https://git.scc.kit.edu/IPDSnelting/summary_cpp/-/blob/master/src/graph.hpp#L873
     */
    List<Set<Node>> computeLayers() {
        List<Set<Node>> layers = new ArrayList<>();
        Set<Node> activeNodes = new HashSet<>(nodes.values());
        Set<Node> deadNodes = new HashSet<>();
        if (causeNode != null) {
            activeNodes.remove(causeNode);
            deadNodes.add(causeNode);
            layers.add(Set.of(causeNode));
        }
        while (activeNodes.size() > 0) {
            var layer = findNodesWithOnlyDeadDependsOn(activeNodes, deadNodes);
            activeNodes.removeAll(layer);
            deadNodes.addAll(layer);
            layers.add(layer);
        }
        return layers;
    }

    Set<Node> findNodesWithOnlyDeadDependsOn(Set<Node> nodes, Set<Node> assumeDead) {
        return nodes.stream().filter(n -> assumeDead.containsAll(n.dependsOn))
                .collect(Collectors.toSet());
    }

    boolean hasCauseNode() {
        return causeNode != null;
    }
}