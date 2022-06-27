package tunnel.synth;

import jdwp.*;
import jdwp.EventCmds.Events;
import jdwp.Value.BasicValue;
import jdwp.Value.TaggedBasicValue;
import jdwp.util.Pair;
import lombok.*;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Functions.BasicValueTransformer;
import tunnel.synth.program.Functions.Function;
import tunnel.util.Either;
import tunnel.util.Hashed;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static jdwp.util.Pair.p;

/**
 * This is used to represent the interdependencies and for layering the packets.
 */
@Getter
public class DependencyGraph {

    /**
     * (targetPath)=(get originPath)  ===  (targetPath)=valueAtOrigin,
     * connects targetPath with originPath (implicit dependsOn edge)
     * <p>
     * if transformers are present, then (get originPath) is replaced by (transform (get originPath))
     */
    @Getter
    @EqualsAndHashCode
    public static class DoublyTaggedBasicValue<O extends BasicValue, T extends BasicValue> {

        private final List<AccessPath> targetPaths;
        /**
         * first path at the origin of the value
         */
        private final AccessPath originPath;
        private final O valueAtOrigin;
        private final T valueAtTarget;

        private @Nullable
        final List<BasicValueTransformer<O>> transformers;

        private DoublyTaggedBasicValue(List<AccessPath> targetPaths, AccessPath atValueOrigin, O valueAtOrigin,
                                       T valueAtTarget, @Nullable List<BasicValueTransformer<O>> transformers) {
            this.targetPaths = targetPaths;
            this.originPath = atValueOrigin;
            this.valueAtOrigin = valueAtOrigin;
            this.valueAtTarget = valueAtTarget;
            this.transformers = transformers;
            if ((transformers != null && transformers.isEmpty()) ||
                    (transformers == null && valueAtOrigin != valueAtTarget)) {
                throw new AssertionError();
            }
            assert transformers == null || transformers.stream().allMatch(t -> t.isApplicable(valueAtOrigin));
        }

        public static <T extends BasicValue> DoublyTaggedBasicValue<T, T>
        createDirect(AccessPath targetPath, TaggedBasicValue<T> taggedOrigin) {
            return createDirect(List.of(targetPath), taggedOrigin);
        }

        public static <T extends BasicValue> DoublyTaggedBasicValue<T, T>
        createDirect(List<AccessPath> targetPaths, TaggedBasicValue<T> taggedOrigin) {
            return new DoublyTaggedBasicValue<>(targetPaths, taggedOrigin.getPath(), taggedOrigin.value,
                    taggedOrigin.value, null);
        }

        public static <O extends BasicValue, T extends BasicValue> DoublyTaggedBasicValue<O, T>
        createIndirect(List<AccessPath> targetPaths, T targetValue, TaggedBasicValue<O> taggedOrigin,
                       List<BasicValueTransformer<O>> transformers) {
            return new DoublyTaggedBasicValue<>(targetPaths, taggedOrigin.getPath(), taggedOrigin.value, targetValue,
                    transformers);
        }

        @Override
        public String toString() {
            if (isDirect()) {
                return String.format("(%s)=(get %s)=%s",
                        targetPaths.stream().map(AccessPath::toString).collect(Collectors.joining("; ")),
                        originPath, valueAtOrigin);
            } else {
                return String.format("(%s)=(%s (get %s of %s))=%s",
                        targetPaths.stream().map(AccessPath::toString).collect(Collectors.joining("; ")),
                        transformers.stream().map(Function::getName).collect(Collectors.toSet()), originPath,
                        valueAtOrigin, valueAtTarget);
            }
        }

        /**
         * without any transformers and valueAtOrigin == valueAtTarget
         */
        public boolean isDirect() {
            return transformers == null;
        }

        public BasicValueTransformer<?> getSingleTransformer() {
            assert transformers != null;
            if (transformers.size() == 1) {
                return transformers.iterator().next();
            }
            return transformers.stream().min(Comparator.naturalOrder()).get();
        }

        public DoublyTaggedBasicValue<O, T> withNewTargetPaths(List<AccessPath> newPaths) {
            return new DoublyTaggedBasicValue<>(newPaths, originPath, valueAtOrigin, valueAtTarget, transformers);
        }
    }

    @Getter
    public static class Edge {
        private final Node source;
        private final Node target;
        /**
         * order is undefined
         */
        private final List<DoublyTaggedBasicValue<?, ?>> usedValues;

        public Edge(Node source, Node target, List<DoublyTaggedBasicValue<?, ?>> usedValues) {
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

        public Edge reverse() {
            return new Edge(target, source, usedValues);
        }
    }

    @Getter
    public static class Node {
        private final int id;
        private @Nullable
        final Pair<Request<?>, ReplyOrError<?>> origin;
        private final Set<Edge> dependsOn = new HashSet<>();
        private final Set<Edge> dependedBy = new HashSet<>();

        @SuppressWarnings({"unchecked", "rawtypes"})
        public Node(int id, @Nullable Pair<Request<?>, ? extends Reply> origin) {
            this.id = id;
            this.origin = origin == null ? null : (origin.second instanceof ReplyOrError<?> ?
                    (Pair<Request<?>, ReplyOrError<?>>)(Pair)origin : p(origin.first,
                    new ReplyOrError<>(origin.second)));
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

        public List<DoublyTaggedBasicValue<?, ?>> getDoublyTaggedValuesForField(AccessPath targetPathPrefix) {
            return dependsOn.stream().filter(e -> e.usedValues.stream()
                            .anyMatch(v -> v.targetPaths.stream().anyMatch(t -> t.startsWith(targetPathPrefix))))
                    .map(Edge::getUsedValues).flatMap(List::stream).collect(Collectors.toList());
        }

        public List<Edge> getDependedByField(AccessPath fieldPath) {
            return dependedBy.stream().filter(e -> e.usedValues.stream()
                            .anyMatch(v -> v.originPath.startsWith(fieldPath)))
                    .collect(Collectors.toList());
        }

        /**
         * @return [(doubly, origin node)]
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        public List<Pair<DoublyTaggedBasicValue<?, ?>, Node>> getDoublyTaggedValuesAndNodesForField(AccessPath targetPathPrefix) {
            return (List<Pair<DoublyTaggedBasicValue<?, ?>, Node>>) (List) dependsOn.stream().filter(e -> e.usedValues.stream()
                            .anyMatch(v -> v.targetPaths.stream().anyMatch(t -> t.startsWith(targetPathPrefix))))
                    .flatMap(e -> e.getUsedValues().stream().map(d -> p(d, e.getTarget()))).collect(Collectors.toList());
        }

        public @Nullable Pair<Request<?>, ReplyOrError<?>> getOrigin() {
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

        public Set<Node> computeDependedByTransitive(Set<Node> possibleNodes, Set<Node> doNotCross) {
            Set<Node> nodes = new HashSet<>();
            Stack<Node> stack = new Stack<>();
            stack.push(this);
            while (!stack.isEmpty()) {
                var current = stack.pop();
                for (Edge edge : current.dependedBy) {
                    var other = edge.target;
                    if (!nodes.contains(other) && possibleNodes.contains(other)) {
                        if (!doNotCross.contains(other)) {
                            stack.push(other);
                        }
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
            edge.getTarget().dependedBy.add(edge.reverse());
        }

        public boolean hasSameRequestClass(Request<?> request) {
            return origin != null && origin.first.getClass() == request.getClass();
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
            throw new AssertionError("(causeNode == null) != (cause == null)");
        }
    }

    public DependencyGraph(@Nullable Either<Request<?>, Events> cause) {
        this(cause != null ? new Node() : null, cause);
    }

    /**
     * configure the graph computation, allows to disable potentially expensive features
     */
    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ComputationOptions {
        /**
         * Use the transformers via {@link Functions#getTransformers(VM, BasicValue, BasicValue)}
         * to check whether a transformer at an edge could produce the required value.
         * Should be more expensive
         */
        @With
        private boolean useTransformers = true;
        /**
         * reduce complexity by just using the first matching value, should decrease the possibilities of the
         * synthesizers to synthesize more complex control structures
         */
        @With
        private boolean useFirstValueInTarget = false;
        /**
         * Check whether the access paths of origin and target are compatible, mainly used for
         * int and byte values. Could later be replaced by adding type aliases to the spec for differentiation.
         * <p>
         * Important: uses domain knowledge
         */
        @With
        public boolean checkPropertyNames = true;
    }

    public static final ComputationOptions DEFAULT_OPTIONS = new ComputationOptions();
    public static final ComputationOptions MINIMAL_OPTIONS = new ComputationOptions(false, true, false);

    /**
     * ... with default options
     */
    public static DependencyGraph compute(Partition partition) {
        return compute(partition, new ComputationOptions());
    }

    /**
     * On multiple paths for the same value in the same object: choose the first,
     * the assumption is that this is probably the most relevant path
     * <p>
     * ... from different objects: prefer the paths of the object that is first in the partition and therefore
     * was first received and thereby typically in a lower layer
     */
    public static DependencyGraph compute(Partition _partition, ComputationOptions options) {
        var partition = _partition.sortedAndDistinct();
        // collect the values
        if (partition.isEmpty()) {
            return new DependencyGraph(partition.getCause());
        }
        ContainedValues causeValues = partition.hasCause() ? partition.getCausePacket().getContainedValues() :
                new ContainedValues();
        Map<Pair<Request<?>, ReplyOrError<?>>, Pair<ContainedValues, ContainedValues>> containedValues = new HashMap<>();
        for (Pair<Request<?>, ReplyOrError<?>> p : partition) {
            containedValues.put(p, p(p.first.asCombined().getContainedValues(),
                    p.second.asCombined().getContainedValues()));
        }
        DependencyGraph graph = new DependencyGraph(partition.getCause());

        boolean hasRequestCause = partition.hasCause() && partition.getCause().isLeft();
        if (hasRequestCause && !(partition.get(0).first().equals(partition.getCause().getLeft()))) {
            throw new AssertionError("partition.get(0).first().equals(partition.getCause().getLeft())");
        }

        // look for requests that only depend on the cause or values not in the set
        for (int i = 0; i < partition.size(); i++) {
            var origin = partition.get(i);
            boolean isCause =
                    i == 0 && partition.getCause() != null && origin.first().equals(partition.getCause().get());
            ContainedValues requestValues = containedValues.get(origin).first; // use the request
            Map<BasicValue, DoublyTaggedBasicValue<?, ?>> usedCauseValues;
            if (!isCause) {
                // only check for cause values if the current request is not (!) the cause request itself
                usedCauseValues = new HashMap<>();
                for (Map.Entry<BasicValue, List<TaggedBasicValue<?>>> e : requestValues.entrySet()) {
                    // for every basic value in the cause request (
                    var matches = findMatchingValue(options, e.getKey(), e.getValue(), causeValues);
                    if (matches.size() > 0) {
                        usedCauseValues.put(e.getKey(), matches.get(0));
                        break;
                    }
                }
            } else {
                usedCauseValues = Map.of();
            }
            Map<Pair<Request<?>, ReplyOrError<?>>, List<DoublyTaggedBasicValue<?, ?>>> dependsOn = new HashMap<>();
            for (var entry : requestValues.entrySet()) { // for every basic value in the request
                var value = entry.getKey();
                if (usedCauseValues.containsKey(value)) { // cause has the highest priority
                    continue;
                }
                // cannot use replies of requests that were sent after receiving origin
                for (int j = 0; j < i; j++) {  // look into every possible request in the partition
                    var other = partition.get(j);
                    var otherContainedValues = containedValues.get(other).second; // use the reply
                    var matches = findMatchingValue(options, value, entry.getValue(), otherContainedValues);
                    if (matches.size() > 0) {
                        dependsOn.computeIfAbsent(other, x -> new ArrayList<>()).addAll(matches);
                        if (options.useFirstValueInTarget) {
                            break;
                        }
                    }
                }
            }
            graph.add(origin, dependsOn, usedCauseValues.values());
        }

        return graph;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<DoublyTaggedBasicValue<?, ?>>
    findMatchingValue(ComputationOptions options, BasicValue targetValue, List<TaggedBasicValue<?>> targetTaggeds,
                      ContainedValues otherContainedValues) {
        if (otherContainedValues.isEmpty()) {
            return List.of();
        }
        List<DoublyTaggedBasicValue<BasicValue, BasicValue>> matches = new ArrayList<>();
        if (otherContainedValues.containsBasicValue(targetValue)) {
            var targetPaths = targetTaggeds.stream()
                    .map(TaggedBasicValue::getPath).collect(Collectors.toList());
            var originValues =
                    options.useFirstValueInTarget && !options.checkPropertyNames ?
                            List.of(otherContainedValues.getFirstTaggedValue(targetValue)) :
                            otherContainedValues.getTaggedValues(targetValue);
            for (TaggedBasicValue<?> value : originValues) {
                var paths = options.checkPropertyNames ?
                        filterAccessPathsForCompatibility(targetPaths, value) : targetPaths;
                if (paths.size() > 0) {
                    matches.add((DoublyTaggedBasicValue<BasicValue, BasicValue>)
                            DoublyTaggedBasicValue.createDirect(paths, value));
                    if (options.useFirstValueInTarget) {
                        break;
                    }
                }
            }
        }
        if (options.useTransformers) {
            for (var valueAndTags : otherContainedValues.entrySet()) {
                var transformers = Functions.getTransformers(null, valueAndTags.getKey(), targetValue);
                if (transformers.size() > 0) {
                    var originValues =
                            options.useFirstValueInTarget ? List.of(valueAndTags.getValue().get(0)) :
                                    valueAndTags.getValue();
                    var targetPaths =
                            targetTaggeds.stream().map(TaggedBasicValue::getPath).collect(Collectors.toList());
                    for (TaggedBasicValue<?> value : originValues) {
                        if (options.checkPropertyNames) {
                            targetPaths = filterAccessPathsForCompatibility(targetPaths, value);
                        }
                        if (targetPaths.size() > 0) {
                            matches.add(DoublyTaggedBasicValue.createIndirect(targetPaths,
                                    targetValue,
                                    (TaggedBasicValue<BasicValue>) value,
                                    (List<BasicValueTransformer<BasicValue>>) (List) transformers));
                        }
                    }
                }
            }
        }
        return (List<DoublyTaggedBasicValue<?, ?>>) (List) matches;
    }

    private static List<AccessPath> filterAccessPathsForCompatibility(List<AccessPath> accessPaths, TaggedBasicValue<
            ?> value) {
        return accessPaths.stream().filter(p -> areAccessPathsCompatible(value, p)).collect(Collectors.toList());
    }

    private static boolean areAccessPathsCompatible(TaggedBasicValue<?> first, AccessPath second) {
        if (first.getValue() instanceof PrimitiveValue.IntValue || first.getValue() instanceof PrimitiveValue.ByteValue) {
            return areAccessPathsCompatible(first.getPath(), second);
        }
        return true;
    }

    /**
     * Checks that the property names kind of match, should work for all int and byte values
     * <p>
     * Important: this contains domain specific knowledge about the property names
     */
    private static boolean areAccessPathsCompatible(AccessPath first, AccessPath second) {
        String firstString = first.getLastStringElementOrEmpty();
        String secondString = second.getLastStringElementOrEmpty();
        return doPropertyNamesMatch(firstString, secondString);
    }

    /**
     * Checks that the property names kind of match, should work for all int and byte values
     * <p>
     * Important: this contains domain specific knowledge about the property names
     */
    private static boolean doPropertyNamesMatch(String propertyName, String propertyName2) {
        propertyName = normalizePropertyName(propertyName);
        propertyName2 = normalizePropertyName(propertyName2);
        return firstUncapitalizedPart(propertyName).equals(firstUncapitalizedPart(propertyName2)) ||
                ((propertyName.contains("value") || propertyName.contains("Value") || propertyName.contains("arg")) &&
                        (propertyName2.contains("value") || propertyName2.contains("Value") || propertyName2.equals(
                                "arg")));
    }

    private static String normalizePropertyName(String propertyName) {
        if (propertyName.equals("sigbyte")) {
            return "refTypeTag";
        }
        return propertyName.replaceAll("count", "length");
    }

    private static String firstUncapitalizedPart(String propertyName) {
        var sb = new StringBuilder();
        for (int i : propertyName.getBytes(StandardCharsets.UTF_8)) {
            if (i >= 'A' && i <= 'Z') {
                return sb.toString();
            }
            sb.append((char) i);
        }
        return sb.toString();
    }

    void add(Pair<Request<?>, ReplyOrError<?>> origin, Map<Pair<Request<?>, ReplyOrError<?>>, List<DoublyTaggedBasicValue<?, ?>>> dependsOn,
             Collection<DoublyTaggedBasicValue<?, ?>> usedCauseValues) {
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
        if (causeNode != null) {
            allNodes.add(causeNode);
        }
        return allNodes;
    }

    public Set<Node> getAllNodesWOCause() {
        return new HashSet<>(nodes.values());
    }

    /**
     * Layer 0 nodes only depend on the cause node (its request or event values),
     * layers above only depend on the layers below
     * <p>
     * Layer 0 contains the cause node if present
     * <p>
     * Interestingly, I coded a similar code during my PhD:
     * <a href="https://git.scc.kit.edu/IPDSnelting/summary_cpp/-/blob/master/src/graph.hpp#L873">...</a>
     */
    public Layers computeLayers() {
        return computeLayers(this, new HashSet<>(nodes.values()));
    }

    public static Layers computeLayers(@Nullable DependencyGraph graph, Set<Node> nodes) {
        List<Set<Node>> layers = new ArrayList<>();
        Set<Node> activeNodes = new HashSet<>(nodes);
        Set<Node> deadNodes = new HashSet<>();
        Node causeNode = graph == null ? null : graph.causeNode;
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
        return new Layers(graph, layers);
    }

    static Set<Node> findNodesWithOnlyDeadDependsOn(Set<Node> nodes, Set<Node> assumeDead) {
        return nodes.stream().filter(n ->
                        n.dependsOn.stream().allMatch(d -> assumeDead.contains(d.target) || !nodes.contains(d.target)))
                .collect(Collectors.toSet());
    }

    boolean hasCauseNode() {
        return causeNode != null;
    }

    @Getter
    public static class Layers extends AbstractList<Set<Node>> {

        private final @Nullable DependencyGraph graph;
        private final List<Set<Node>> layers; // higher depend on lower
        private final Map<Node, Integer> nodeToLayerIndex;

        public Layers(@Nullable DependencyGraph graph, List<Set<Node>> layers) {
            this.graph = graph;
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

        public boolean hasLayerIndex(Node node) {
            return nodeToLayerIndex.containsKey(node);
        }

        @Override
        public int size() {
            return layers.size();
        }

        /**
         * Returns all nodes that transitively depend on the header nodes.
         * <p>
         * Asserts that all header nodes are on the same layer and that all dependent nodes are in a lower layer.
         * Returns null if any of the dependent nodes depends on a node outside both sets.
         */
        public @Nullable Set<Node> computeDominatedNodes(Set<Node> headerNodes) {
            if (headerNodes.stream().mapToInt(this::getLayerIndex).distinct().count() != 1) {
                throw new AssertionError("all header nodes have to be in the same layer");
            }
            int headerLayer = getLayerIndex(headerNodes.iterator().next());
            Set<Node> dependentNodes = computeDependedByTransitive(headerNodes); // nodes "below" the header
            if (dependentNodes.isEmpty()) {
                return Set.of();
            }
            if (dependentNodes.stream().mapToInt(this::getLayerIndex).min().getAsInt() <= headerLayer) {
                throw new AssertionError("missing nodes from header");
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
         * Sort based on each node's layer, command set, command and edge paths. Only uses the id as measure of
         * last resort
         */
        public Comparator<Node> getNodeComparator() {
            if (nodeComparator == null) {
                nodeComparator = (left, right) -> {
                    assert left != null && right != null;
                    if ((left.isCauseNode() || (graph != null && graph.hasCauseNode() &&
                            left.origin.first.getId() == graph.cause.<ParsedPacket>get().getId())) && left.id != right.id) {
                        return -1;
                    }
                    if ((right.isCauseNode() || (graph != null && graph.hasCauseNode() &&
                            right.origin.first.getId() == graph.cause.<ParsedPacket>get().getId())) && left.id != right.id) {
                        return 1;
                    }
                    int layerComp = Long.compare(getLayerIndex(left), getLayerIndex(right));
                    if (layerComp != 0) {
                        return layerComp;
                    }
                    int comparison = Long.compare(hash(left), hash(right));
                    if (comparison == 0) {
                        return Integer.compare(left.getId(), right.getId()); // use the ids only as a measure of last resort
                    }
                    return comparison;
                };
            }
            return nodeComparator;
        }

        public Set<Node> getAllNodes() {
            return layers.stream().flatMap(Collection::stream).collect(Collectors.toSet());
        }

        /** returns the set of nodes without duplicates (and the nodes they depend on), uses hash based heuristics */
        public Set<Node> getAllNodesWithoutDuplicates() {
            Set<HashedNode> nonDuplicates = new HashSet<>();
            Set<Node> considerForRemoval = new HashSet<>();
            for (Node node : getAllNodes()) {
                var hashed = new HashedNode(node);
                if (nonDuplicates.contains(hashed)) {
                    considerForRemoval.add(node);
                } else {
                    nonDuplicates.add(hashed);
                }
            }
            considerForRemoval.addAll(computeDependedByTransitive(considerForRemoval));
            return getAllNodes().stream().filter(n -> !considerForRemoval.contains(n)).collect(Collectors.toSet());
        }

        private final Map<Node, Long> hashCache = new HashMap<>();

        long hash(Node node) {
            if (node.isCauseNode()) {
                return 0;
            }
            if (!hashCache.containsKey(node)) {
                assert node.origin != null;
                // take all basic values into account
                // this is by construction of the dependency graph equivalent to using the edges
                // assumes that the graph construction is deterministic
                hashCache.put(node, Hashed.hash((byte)node.origin.first.getCommandSet(),
                        (byte)node.origin.first.getCommand(),
                        node.origin.first.asCombined().getTaggedValues()
                                .mapToLong(t -> Hashed.hash(t.value.hashCode(), t.path.hashCode()))
                                .sorted().toArray()));
            }
            return hashCache.get(node);
        }

        class HashedNode {
            private final Node node;
            private final long hashCode;

            public HashedNode(Node node) {
                this.node = node;
                this.hashCode = hash(node);
            }

            @Override
            public int hashCode() {
                return Long.hashCode(hashCode);
            }

            @Override
            public boolean equals(Object obj) {
                if (obj instanceof HashedNode) {
                    HashedNode other = (HashedNode) obj;
                    return other.hashCode == hashCode && Objects.equals(node.origin, other.node.origin);
                }
                return false;
            }

            public Node get() {
                return node;
            }
        }
    }

    /**
     * Returns all nodes that transitively depend on the passed nodes, does not include these
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

    public static Set<Node> computeDependedByTransitive(Set<Node> start, Set<Node> possibleNodes,
                                                        Set<Node> doNotCross) {
        Set<Node> nodes = new HashSet<>();
        Stack<Node> stack = new Stack<>();
        for (Node node : start) {
            stack.push(node);
        }
        while (!stack.isEmpty()) {
            var current = stack.pop();
            for (Edge edge : current.dependedBy) {
                var other = edge.target;
                if (!nodes.contains(other) && possibleNodes.contains(other)) {
                    if (!doNotCross.contains(other)) {
                        stack.push(other);
                    }
                    nodes.add(other);
                }
            }
        }
        return nodes;
    }
}