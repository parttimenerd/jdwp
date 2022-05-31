package tunnel.synth;

import jdwp.AccessPath;
import jdwp.EventCmds.Events;
import jdwp.EventRequestCmds;
import jdwp.EventRequestCmds.SetRequest;
import jdwp.Request;
import jdwp.util.Pair;
import lombok.Getter;
import tunnel.synth.DependencyGraph.DoublyTaggedBasicValue;
import tunnel.synth.DependencyGraph.Edge;
import tunnel.synth.DependencyGraph.Layers;
import tunnel.synth.DependencyGraph.Node;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Program;
import tunnel.util.Either;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static tunnel.synth.program.AST.*;
import static tunnel.synth.program.Functions.createWrapperFunctionCall;

/**
 * Transforms a dependency graph into a program.
 */
public class Synthesizer extends Analyser<Synthesizer, Program> implements Consumer<Partition>  {

    @Override
    public void accept(Partition partition) {
        submit(synthesizeProgram(partition));
    }

    public static final String CAUSE_NAME = "cause";
    public static final String NAME_PREFIX = "var";
    public static final String ITER_NAME_PREFIX = "iter";

    public static Program synthesizeProgram(Partition partition) {
        return synthesizeProgram(DependencyGraph.calculate(partition));
    }

    public static Program synthesizeProgram(DependencyGraph graph) {
        // compute the layers: loop iteration headers have to reside in the layer directly
        // below the loop source
        Layers layers = graph.computeLayers();
        var names = new NodeNames();
        var program = processNodes(names, layers, layers.getAllNodesWithoutDuplicates(), 2).first;
        return new Program(graph.hasCauseNode() ?
                names.createPacketCauseCall(graph.getCause()) : null, program.getBody());
    }

    @Getter
    static class NodeNames {
        private final Map<Node, String> names;
        private int nameCount;
        private int iterNameCount;

        public NodeNames() {
            this.names = new HashMap<>();
            this.nameCount = 0;
            this.iterNameCount = 0;
        }

        public String createNewName() {
            var name = NAME_PREFIX + nameCount;
            nameCount++;
            return name;
        }

        public String createNewIterName() {
            var name = ITER_NAME_PREFIX + iterNameCount;
            iterNameCount++;
            return name;
        }

        public String get(Node node) {
            if (node.isCauseNode()) {
                return CAUSE_NAME;
            }
            return names.computeIfAbsent(node, n -> createNewName());
        }

        private RequestCall createRequestCall(Node node) {
            var origin = node.getOrigin();
            assert origin != null;
            var request = node.getOrigin().first;
            Map<AccessPath, FunctionCall> usedPaths = new HashMap<>();
            for (Edge edge : node.getDependsOn()) {
                var target = get(edge.getTarget());
                for (DoublyTaggedBasicValue<?> usedValue : edge.getUsedValues()) {
                    FunctionCall call = Functions.createGetFunctionCall(target, usedValue.getAtValueOrigin());
                    for (AccessPath atSetPath : usedValue.getAtSetTargets()) {
                        assert !usedPaths.containsKey(atSetPath);
                        usedPaths.put(atSetPath, call);
                    }
                }
            }
            request.asCombined().getTaggedValues()
                    .filter(t -> !(usedPaths.containsKey(t.getPath())))
                    .forEach(t -> usedPaths.put(t.getPath(), createWrapperFunctionCall(t.getValue())));
            if (request instanceof EventRequestCmds.SetRequest) {
                var setRequest = (SetRequest) request;
                for (int i = 0; i < setRequest.modifiers.size(); i++) {
                    var modifier = setRequest.modifiers.get(i);
                    usedPaths.put(new AccessPath("modifiers", i, "kind"),
                            createWrapperFunctionCall(wrap(modifier.getClass().getSimpleName())));
                }
            }
            return new RequestCall(request.getCommandSetName(), request.getCommandName(),
                    usedPaths.keySet().stream().sorted().map(p -> new CallProperty(p, usedPaths.get(p)))
                            .collect(Collectors.toList()));
        }

        private PacketCall createPacketCauseCall(Either<Request<?>, Events> call) {
            return call.isLeft() ? RequestCall.create(call.getLeft()) : EventsCall.create(call.getRight());
        }

        public AssignmentStatement createRequestCallStatement(Node node) {
            var call = createRequestCall(node);
            return new AssignmentStatement(ident(get(node)), call);
        }
    }

    // draft of loop detection code
    /*private static Optional<Pair<Program, Set<Node>>> isPossibleLoopSourceNode(NodeNames variables, Layers layers, Node node, int minSize) {
        assert minSize >= 2;
        var currentLayer = layers.get(layers.getLayerIndex(node));
        var layerBelow = layers.getOrEmpty(layers.getLayerIndex(node) + 1);
        if (node.getDependedBy().isEmpty()) { // no node depends on it
            return Optional.empty();
        }
        Set<Edge> connectionsToBelow = node.getDependedBy().stream()
                .filter(e -> layerBelow.contains(e.getTarget())).collect(Collectors.toSet());
        if (connectionsToBelow.isEmpty()) { // no connections to the nodes below
            return Optional.empty();
        }
        if (connectionsToBelow.stream().map(Edge::getTarget)
                .anyMatch(n -> n.getDependsOn().stream().map(Edge::getTarget)
                        .anyMatch(n2 -> n2 != node && currentLayer.contains(n2)))) {
            return Optional.empty(); // dependent nodes depend on other nodes of the current layer, possibly mixing two loops
        }

        // now find all paths that only differ in an index

        // first collect all paths (and the nodes that depend on them)
        Map<AccessPath, Set<Node>> pathToNodes = new HashMap<>();
        for (Edge edge : connectionsToBelow) {
            for (TaggedBasicValue<?> usedValue : edge.getUsedValues()) {
                var path = usedValue.path.removeTag();
                pathToNodes.computeIfAbsent(path, p -> new HashSet<>()).add(edge.getTarget());
            }
        }

        // keep in mind: nodes below might also depend on non list entries

        // find paths that are similar if we ignore the list accesses in the path
        // but only consider paths that contain list accesses
        Map<AccessPath, List<AccessPath>> fieldOnlyToRegularPaths =
                pathToNodes.keySet().stream().filter(AccessPath::containsListAccesses)
                        .collect(Collectors.groupingBy(AccessPath::removeListAccesses));
        if (fieldOnlyToRegularPaths.isEmpty()) {
            return Optional.empty();
        }
        // now check that each group only differs in one location
        // as lists in lists are harder and not supported
        // the groups do not have duplicates by design
        int differingIndex = -2; // differing index for all groups
        for (var accessPathListEntry : fieldOnlyToRegularPaths.entrySet()) {
            var group = accessPathListEntry.getValue();
            if (group.size() < minSize) { // ignore if size is too small
                return Optional.empty(); // simplification
            }
            if (differingIndex == -2) { // compute the differing index for the first time
                differingIndex = group.get(0).onlyDifferingIndex(group.get(1));
                if (differingIndex == -1) {
                    return Optional.empty();
                }
            }
            final int d = differingIndex;
            // check that the differing index is valid for all groups
            if (group.stream().anyMatch(p -> group.get(0).onlyDifferingIndex(p) != d)) {
                return Optional.empty(); // simplification
            }
        }
        final int finalDifferingIndex = differingIndex;
        // we now know that all paths differ only at a given index in their group
        // we now check all access paths are the same considering only path elements before the differing index
        AccessPath prefix = fieldOnlyToRegularPaths.entrySet().iterator().next().getValue().get(0)
                .subPath(0, differingIndex); // we made enough checks before, so this is ok
        if (fieldOnlyToRegularPaths.keySet().stream().anyMatch(p -> !p.startsWith(prefix))) {
            return Optional.empty(); // at least one path did not start with the prefix
        }
        // now we know that all paths have the structure "[prefix].[differing].[group appendix]"
        // we have to find

        // collect preliminary headers
        Map<Integer, List<AccessPath>> headerPathsPerIndex = new HashMap<>();
        for (Entry<AccessPath, List<AccessPath>> entry : fieldOnlyToRegularPaths.entrySet()) {
            for (AccessPath path : entry.getValue()) {
                var index = (Integer)path.get(differingIndex); // ok by construction
                headerPathsPerIndex.computeIfAbsent(index, p -> new ArrayList<>()).add(path);
            }
        }
        Map<Integer, Set<Node>> headersPerIndex = new HashMap<>();
        Map<Integer, Set<Node>> fullBodyPerIndex = new HashMap<>();
        for (Entry<Integer, List<AccessPath>> entry : headerPathsPerIndex.entrySet()) {
            var headers = entry.getValue().stream().flatMap(p -> pathToNodes.get(p).stream()).collect(Collectors.toSet());
            headersPerIndex.put(entry.getKey(), headers);
            Set<Node> body = layers.computeDominatedNodes(headers);
            if (body == null) { // dominated nodes might depend on nodes in upper layers
                return Optional.empty();
            }
            // check that all body nodes do not depend on other list items
            if (body.stream().anyMatch(n -> n.getDependsOn().stream().anyMatch(e -> {
                if (e.getTarget().equals(node)) { // loop source node?
                    return e.getUsedValues().stream().anyMatch(t -> t.path.startsWith(prefix)
                            && finalDifferingIndex < t.path.size() && t.path.get(finalDifferingIndex) != entry.getKey());
                }
                return false;
            }))) {
                return Optional.empty();
            }
            Set<Node> fullBody;
            if (body.isEmpty()) {
                fullBody = headers;
            } else {
                fullBody = new HashSet<>(body);
                fullBody.addAll(headers);
            }
            fullBodyPerIndex.put(entry.getKey(), fullBody);
        }
        List<Statement> finishedStatements = new ArrayList<>();
        // mark node.prefix as the iteration variable
        var iterableNameAndCall = variables.createGetCallIfNeeded(node, prefix);
        iterableNameAndCall.second.ifPresent(finishedStatements::add);
        var iterNameAndCall = variables.markIterPath(node, prefix);
        iterNameAndCall.second.ifPresent(finishedStatements::add);
        // we now have the full bodies, but they might contain loops too
        Set<Node> processedNodes = new HashSet<>();
        Program merged = null;
        for (Entry<Integer, Set<Node>> fullBodyEntry : fullBodyPerIndex.entrySet()) {
            var fullBody = fullBodyEntry.getValue();
            var bodyProgram = processNodes(variables.child(), layers, fullBody, minSize).first;
            if (merged == null) {
                merged = bodyProgram;
            } else {
                merged = merged.merge(bodyProgram); // maybe include some heuristic here
            }
            processedNodes.addAll(fullBody);
        }
        finishedStatements.add(new Loop(AST.ident(iterNameAndCall.first), AST.ident(iterableNameAndCall.first), merged.getBody()));
        variables.unmarkIterPath(node, prefix);
        return Optional.of(p(new Program(finishedStatements), processedNodes));
    }*/

    private static Pair<Program, Set<Node>> processNodes(NodeNames variables, Layers layers, Set<Node> fullBody, int minSize) {
        var fullBodySorted = new ArrayList<>(fullBody);
        fullBodySorted.sort(layers.getNodeComparator()); // makes the statement order deterministic
        List<Statement> statements = new ArrayList<>();
        Set<Node> ignoredNodes = new HashSet<>();
        for (Node node : fullBodySorted) {
            if (ignoredNodes.contains(node)) {
                continue;
            }
            if (node.isCauseNode()) {
                continue;
            }
            statements.add(variables.createRequestCallStatement(node));
            /*var loopRes = isPossibleLoopSourceNode(variables.child(), layers, node, minSize);
            loopRes.ifPresent(programSetPair -> {
                statements.addAll(programSetPair.first.getBody());
                ignoredNodes.addAll(loopRes.get().second);
            });*/
        }
        return p(new Program(statements), fullBody);
    }
}
