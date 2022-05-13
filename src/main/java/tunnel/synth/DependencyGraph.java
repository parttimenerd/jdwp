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
            return dependsOn.contains(node); // TODO: check this
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
    }

    private final @Nullable Node causeNode;
    private final Map<Pair<Request<?>, Reply>, Node> nodes = new HashMap<>();

    private DependencyGraph(@Nullable Node causeNode) {
        this.causeNode = causeNode;
    }
    public DependencyGraph(@Nullable Either<Request<?>, Events> cause) {
        this(cause != null ? new Node() : null);
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
        // look for requests that only depend on the cause or values not in the set

        for (int i = 0; i < partition.size(); i++) {
            var origin = partition.get(i);
            ContainedValues requestValues = containedValues.get(origin).first; // use the request
            Map<BasicValue, TaggedBasicValue<?>> usedCauseValues = requestValues.getBasicValues().stream()
                    .filter(causeValues::containsBasicValue)
                    .collect(Collectors.toMap(v -> v, causeValues::getFirstTaggedValue));
            Map<Pair<Request<?>, Reply>, List<TaggedBasicValue<?>>> dependsOn = new HashMap<>();
            for (BasicValue value : requestValues.getBasicValues()) {
                if (usedCauseValues.containsKey(value)) { // cause has highest priority
                    break;
                }
                // cannot use replies of requests that were send after receiving origin
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

    Node getNode(Pair<Request<?>, Reply> origin) {
        return nodes.computeIfAbsent(origin, o -> new Node(nodes.size(), origin));
    }

    /**
     * Layer 0 nodes only depend on the cause node (its request or event values),
     * layers above only depend on the layers below
     * <p>
     * Interestingly, I coded a similar code during my PhD:
     * https://git.scc.kit.edu/IPDSnelting/summary_cpp/-/blob/master/src/graph.hpp#L873
     */
    List<Set<Node>> computeLayers() {
        List<Set<Node>> layers = new ArrayList<>();
        Set<Node> activeNodes = new HashSet<>(nodes.values());
        Set<Node> deadNodes = causeNode != null ? new HashSet<>(List.of(causeNode)) : new HashSet<>();
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
}