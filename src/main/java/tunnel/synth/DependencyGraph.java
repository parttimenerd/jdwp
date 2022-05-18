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
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static jdwp.util.Pair.p;

/**
 * This is used to represent the interdependencies and for layering the packets.
 */
@Getter
public class DependencyGraph {

    @Getter
    public static class Edge {
        private final Node source;
        private final Node target;
        /**
         * order is undefined
         */
        private final List<TaggedBasicValue<?>> usedValues;

        public Edge(Node source, Node target, List<TaggedBasicValue<?>> usedValues) {
            this.source = source;
            this.target = target;
            this.usedValues = usedValues;
        }

        public boolean isCauseNodeEdge() {
            return target.origin == null;
        }

        public int getSourceId() {
            return source.id;
        }

        public int getTargetId() {
            return target.id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null) return false;
            if (o instanceof Edge) {
                return ((Edge) o).getTarget().equals(target);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return 31 * getTargetId() * getSourceId();
        }

        @Override
        public String toString() {
            return "Edge{" +
                    "source=" + source +
                    ", target=" + target +
                    ", usedValues=" + usedValues +
                    '}';
        }
    }

    @Getter
    public static class Node {
        private final int id;
        private @Nullable final Pair<Request<?>, Reply> origin;
        private final Set<Edge> dependsOn = new HashSet<>();
        private final Set<Edge> dependedBy = new HashSet<>();

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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null) return false;
            if (o instanceof Node) {
                return ((Node) o).id == id;
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

        public Set<Node> getDependedByNodes() {
            return dependedBy.stream().map(Edge::getTarget).collect(Collectors.toSet());
        }

        public @Nullable Pair<Request<?>, Reply> getOrigin() {
            return origin;
        }

        /**
         * Returns all nodes that transitively depend on this node
         */
        public Set<Node> computeDependedByTransitive() {
            Set<Node> nodes = new HashSet<>();
            Stack<Node> stack = new Stack<>();
            stack.push(this);
            while (!stack.isEmpty()) {
                var current = stack.pop();
                for (Edge edge : current.dependedBy) {
                    var other = edge.target;
                    if (!nodes.contains(other)) {
                        stack.push(other);
                        nodes.add(other);
                    }
                }
            }
            return nodes;
        }

        public void addAllDependsOn(Collection<Edge> edges) {
            edges.forEach(this::addDependsOn);
        }

        public void addDependsOn(Edge edge) {
            dependsOn.add(edge);
            edge.getTarget().dependedBy.add(new Edge(edge.getTarget(), edge.getSource(), edge.usedValues));
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
        node.addAllDependsOn(dependsOn.entrySet().stream()
                .map(e -> new Edge(node, getNode(e.getKey()), e.getValue())).collect(Collectors.toList()));
        if (causeNode != null && usedCauseValues.size() > 0) {
            node.addDependsOn(new Edge(node, causeNode, new ArrayList<>(usedCauseValues)));
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

    public Set<Node> getAllNodes() {
        var allNodes = new HashSet<>(nodes.values());
        allNodes.add(causeNode);
        return allNodes;
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
    public Layers computeLayers() {
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
        return new Layers(layers);
    }

    Set<Node> findNodesWithOnlyDeadDependsOn(Set<Node> nodes, Set<Node> assumeDead) {
        return nodes.stream().filter(n -> n.dependsOn.stream().allMatch(d -> assumeDead.contains(d.target)))
                .collect(Collectors.toSet());
    }

    boolean hasCauseNode() {
        return causeNode != null;
    }

    @Getter
    public static class Layers extends AbstractList<Set<Node>> {

        private final List<Set<Node>> layers; // higher depend on lower
        private final Map<Node, Integer> nodeToLayerIndex;

        public Layers(List<Set<Node>> layers) {
            this.layers = layers;
            this.nodeToLayerIndex = IntStream.range(0, layers.size()).boxed()
                    .flatMap(i -> layers.get(i).stream().map(n -> p(i, n)))
                    .collect(Collectors.toMap(p -> p.second, p -> p.first));
        }

        @Override
        public Set<Node> get(int index) {
            return layers.get(index);
        }

        public Set<Node> getOrEmpty(int index) {
            return 0 <= index && index < layers.size() ? layers.get(index) : Set.of();
        }

        public int getLayerIndex(Node node) {
            return nodeToLayerIndex.get(node);
        }

        @Override
        public int size() {
            return layers.size();
        }

        /**
         * Returns all nodes that transitively depend on the header nodes.
         *
         * Asserts that all header nodes are on the same layer and that all dependent nodes are in a lower layer.
         * Returns null if any of the dependent nodes depends on a node outside both sets.
         */
        public @Nullable Set<Node> computeDominatedNodes(Set<Node> headerNodes) {
            if (headerNodes.stream().mapToInt(this::getLayerIndex).distinct().count() != 1) {
                throw new AssertionError(); // all header nodes have to be in the same layer
            }
            int headerLayer = getLayerIndex(headerNodes.iterator().next());
            Set<Node> dependentNodes = computeDependedByTransitive(headerNodes); // nodes "below" the header
            if (dependentNodes.isEmpty()) {
                return Set.of();
            }
            if (dependentNodes.stream().mapToInt(this::getLayerIndex).min().getAsInt() <= headerLayer) {
                throw new AssertionError(); // missing nodes from header
            }
            // check also that all nodes below only depend on nodes in the set or in the header set
            if (dependentNodes.stream().anyMatch(d -> d.dependsOn.stream()
                    .anyMatch(n -> !headerNodes.contains(n.target) && !dependentNodes.contains(n.target)))) {
                return null; // not a "closed" subset
            }
            return dependentNodes;
        }

        private Comparator<Node> nodeComparator;

        /**
         * Sort based on each node's layer, command set, command and edge paths. Only uses the is as measure of
         * last resort
         */
        public Comparator<Node> getNodeComparator() {
            if (nodeComparator == null) {
                Map<Node, Long> edgesValue = new HashMap<>();
                Function<Node, Long> computeEdgesValue = node -> node.dependsOn.stream()
                        .mapToLong(e -> e.getUsedValues().stream()
                                .mapToLong(t -> t.path.basicHashCode())
                                .reduce(1, (x, y) -> x * 31 + y)).reduce(1, (x, y) -> x * 31 + 1);
                nodeComparator = (left, right) -> {
                    int layerComp = Integer.compare(getLayerIndex(left), getLayerIndex(right));
                    if (layerComp != 0) { // order after layers first
                        return layerComp;
                    }
                    // do not use the id here yet, only use the contents of the requests
                    var leftRequest = left.getOrigin().first;
                    var rightRequest = right.getOrigin().first;
                    int commandSetComp = Integer.compare(leftRequest.getCommandSet(), rightRequest.getCommandSet());
                    if (commandSetComp != 0) {
                        return commandSetComp;
                    }
                    int commandComp = Integer.compare(leftRequest.getCommand(), rightRequest.getCommand());
                    if (commandComp != 0) {
                        return commandComp;
                    }
                    int edgeSizeComp = Integer.compare(left.dependsOn.size(), right.dependsOn.size());
                    if (edgeSizeComp != 0) {
                        return edgeSizeComp;
                    }
                    var leftEdgesVal = edgesValue.computeIfAbsent(left, computeEdgesValue);
                    var rightEdgesVal = edgesValue.computeIfAbsent(right, computeEdgesValue);
                    var edgesValComp = Long.compare(leftEdgesVal, rightEdgesVal);
                    if (edgesValComp != 0) {
                        return edgesValComp;
                    }
                    return Integer.compare(left.getId(), right.getId()); // use the ids only as a measure of last resort
                };
            }
            return nodeComparator;
        }
    }

    /**
     * Returns all nodes that transitively depend on the passed node, does not include these
     */
    public static Set<Node> computeDependedByTransitive(Set<Node> start) {
        Set<Node> nodes = new HashSet<>();
        Stack<Node> stack = new Stack<>();
        stack.addAll(start);
        while (!stack.isEmpty()) {
            var current = stack.pop();
            for (Edge edge : current.dependedBy) {
                var other = edge.target;
                if (!nodes.contains(other) && !start.contains(other)) {
                    stack.push(other);
                    nodes.add(other);
                }
            }
        }
        return nodes;
    }
}