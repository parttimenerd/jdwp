package tunnel.synth;

import jdwp.AccessPath;
import jdwp.EventCmds.Events;
import jdwp.EventRequestCmds;
import jdwp.EventRequestCmds.SetRequest;
import jdwp.Request;
import jdwp.Value.BasicValue;
import jdwp.Value.CombinedValue;
import jdwp.Value.ListValue;
import jdwp.Value.TaggedBasicValue;
import jdwp.util.Pair;
import lombok.*;
import org.jetbrains.annotations.Nullable;
import tunnel.synth.DependencyGraph.DoublyTaggedBasicValue;
import tunnel.synth.DependencyGraph.Edge;
import tunnel.synth.DependencyGraph.Layers;
import tunnel.synth.DependencyGraph.Node;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Functions.BasicValueTransformer;
import tunnel.synth.program.Program;
import tunnel.util.Box;
import tunnel.util.Either;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static tunnel.synth.program.AST.*;
import static tunnel.synth.program.Functions.GET_FUNCTION;
import static tunnel.synth.program.Functions.createWrapperFunctionCall;

/**
 * Transforms a dependency graph into a program.
 *
 * Can currently synthesize {@link PacketCall}s and {@link MapCallStatement}s
 */
public class Synthesizer extends Analyser<Synthesizer, Program> implements Consumer<Partition> {

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class SynthesizerOptions {
        /**
         * Synthesize {@link MapCallStatement}s?
         * This synthesis is rather expensive.
         */
        @With
        private boolean mapCallStatements = false;

        /**
         * Minimal number of items in a list for a list to be considered in map call synthesis.
         */
        @With
        private int mapCallStatementsMinListSize = 2;
    }

    public final static SynthesizerOptions DEFAULT_OPTIONS = new SynthesizerOptions();
    public final static SynthesizerOptions MINIMAL_OPTIONS = new SynthesizerOptions(false, 2);

    @Override
    public void accept(Partition partition) {
        submit(synthesizeProgram(partition));
    }

    public static final String CAUSE_NAME = "cause";
    public static final String NAME_PREFIX = "var";
    public static final String MAP_CALL_NAME_PREFIX = "map";
    public static final String ITER_NAME_PREFIX = "iter";

    public static Program synthesizeProgram(Partition partition) {
        try {
            return synthesizeProgram(DependencyGraph.compute(partition));
        } catch (AssertionError e) {
            throw new AssertionError("Failed to synthesize program for partition: " + partition.toCode(), e);
        }
    }

    public static Program synthesizeProgram(DependencyGraph graph) {
        return synthesizeProgram(graph, DEFAULT_OPTIONS);
    }

    public static Program synthesizeProgram(DependencyGraph graph, SynthesizerOptions options) {
        // compute the layers: loop iteration headers have to reside in the layer directly
        // below the loop source
        Layers layers = graph.computeLayers();
        var names = new NodeNames(options);
        var program = processNodes(names, layers, layers.getAllNodesWithoutDuplicates(), 2).first;
        return new Program(graph.hasCauseNode() ?
                names.createPacketCauseCall(graph.getCause()) : null, program.getBody());
    }

    @Getter
    static class NodeNames {
        private final SynthesizerOptions options;
        private final Map<Node, String> names;
        private final Set<String> mapCallNames;
        private int nameCount;
        private int iterNameCount;

        public NodeNames(SynthesizerOptions options) {
            this.options = options;
            this.names = new HashMap<>();
            this.mapCallNames = new HashSet<>();
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

        /**
         * Returns all synthesized {@link MapCallStatement}s for the given node.
         * <p>
         * Limitations:
         * - no nested lists supported (only top level list fields)
         * - chooses the first viable solution (should not be a problem in practice)
         * - can only iterate over one list for every list valued field
         * - only supports list valued fields with combined value entries
         * <p>
         * Idea: a common sequence of requests is the following
         * <pre>
         * ...
         * (= var8 (request Method VariableTableWithGeneric
         *      ("methodID")=(get cause "events" 0 "location" "methodRef")
         *      ("refType")=(get cause "events" 0 "location" "declaringType")))
         * # -> array of (("codeIndex")=... ("name")=... ("signature")="..." ("length")=... ("slot")=...)
         * (= var9 (request ThreadReference Frames ("length")=(get var5 "frameCount")
         *      ("startFrame")=(wrap "int" 0) ("thread")=(get cause "events" 0 "thread")))
         * ...
         * (= var14 (request StackFrame GetValues ("frame")=(get var9 "frames" 0 "frameID")
         *      ("thread")=(get cause "events" 0 "thread")
         *      ("slots" 0 "sigbyte")=(wrap "byte" 91) ("slots" 0 "slot")=(get var1 "slots" 0 "slot")
         *      ("slots" 1 "sigbyte")=(wrap "byte" 73) ("slots" 1 "slot")=(get var5 "frameCount")))
         *      (= var15 (request ObjectReference ReferenceType ("object")=(get var14 "values" 0)))
         *      (= var16 (request ArrayReference Length("arrayObject")=(get var14 "values" 0)))
         * </pre>
         * <p>
         * This method deals with the observation that the slot parameter of GetValues is essentially equal to
         * the
         * slot property of the VariableTableWithGeneric reply
         */
        private Map<AccessPath, MapCallStatement> searchForMapCalls(Node node) {
            var origin = node.getOrigin();
            assert origin != null;
            var request = origin.first.asCombined();
            if (!request.hasListValuedFields()) {
                return Map.of(); // we can only work with list-valued fields
            }
            var fields = request.getListValuedFields(); // these are the fields that are interesting
            var fieldPaths = fields.stream().map(AccessPath::new).collect(Collectors.toList());
            var map = new HashMap<AccessPath, MapCallStatement>();
            for (AccessPath fieldPath : fieldPaths) { // we can work on every field independently
                var forField = processMapCallSearchForField(node, request, fieldPath);
                if (forField != null) {
                    map.put(fieldPath, forField);
                }
            }
            return map;
        }

        private @Nullable MapCallStatement
        processMapCallSearchForField(Node node, CombinedValue request, AccessPath fieldPath) {
            var listValue = (ListValue<?>) fieldPath.access(request);
            var fieldDoubliesAndNodes =
                    node.getDoublyTaggedValuesAndNodesForField(fieldPath);
            // the fieldDoublies are all the values that are assigned to fields of items of the field
            if (listValue.isEmpty() || listValue.size() < options.mapCallStatementsMinListSize) {
                return null; // we can only work with non-empty lists
            }
            var firstListEntry = listValue.get(0);
            // we have to check the type of the list entries
            // the assumption is that the list entries are of the same type
            if (firstListEntry instanceof BasicValue) {
                return null;
                // TODO: currently no support for basic valued lists, but this case should never happen in practice
                //       so we're probably ok
            } else if (firstListEntry instanceof CombinedValue) {
                return processMapCallSearchForCombinedValueList(node, listValue, (CombinedValue) firstListEntry,
                        fieldPath, fieldDoubliesAndNodes);
            }
            return null; // we cannot work with types like lists (nested lists would fairly complicate things)
        }

        @Value
        private static class TaggedFunctionCall {
            Node originNode;
            AccessPath accessPath;
            FunctionCall functionCall;

            @Override
            public String toString() {
                return String.format("(node=%s, %s)", originNode.getId(), functionCall);
            }
        }

        @Value
        private static class TaggedFunctionCallOrBasicValue {
            TaggedFunctionCall taggedFunctionCall;
            BasicValue basicValue;
        }

        private @Nullable MapCallStatement
        processMapCallSearchForCombinedValueList(Node node, ListValue<?> listValue,
                                                 CombinedValue firstListEntry, AccessPath fieldPath,
                                                 List<Pair<DoublyTaggedBasicValue<?, ?>, Node>> fieldDoubliesAndNodes) {
            var listSize = listValue.size();
            var properties = firstListEntry.getKeys();

            // we want to fill the propertyToAccessors map, but first we have to collect all the accessors
            // and values (where no accessor is found)

            Map<Integer, Map<String, List<TaggedFunctionCallOrBasicValue>>> indexToPropertyToAccessors =
                    new HashMap<>();

            // we first collect all accessors for each property and list item
            // index in list -> property name -> accessors
            for (var fieldDoublyAndNode : fieldDoubliesAndNodes) {
                var fieldDoubly = fieldDoublyAndNode.first;
                var accessors = doublyToAccessors(fieldDoublyAndNode);
                for (AccessPath targetPath : fieldDoubly.getTargetPaths()) { // accessors can have multiple targets
                    // assumption: path has the form [..., index, field]
                    if (!targetPath.endsWith(Integer.class, String.class)) {
                        return null;
                    }
                    var index = (Integer) targetPath.get(-2);
                    var property = (String) targetPath.get(-1);
                    indexToPropertyToAccessors.computeIfAbsent(index, i -> new HashMap<>())
                            .computeIfAbsent(property, p -> new ArrayList<>())
                            .addAll(accessors.stream()
                                    .map(a -> new TaggedFunctionCallOrBasicValue(a, null))
                                    .collect(Collectors.toList()));
                }
            }
            // we now collect all basic values for each property and list item where we did not find any accessor
            for (int i = 0; i < listSize; i++) {
                var propToAcc = indexToPropertyToAccessors.computeIfAbsent(i, x -> new HashMap<>());
                for (String property : properties) {
                    if (!propToAcc.containsKey(property)) {
                        propToAcc.put(property, List.of(new TaggedFunctionCallOrBasicValue(null,
                                (BasicValue) ((CombinedValue) listValue.get(i)).get(property))));
                    }
                }
            }


            // we now have entries in indexToPropertyToAccessors for each list item and all its properties
            // we can now use these to try to synthesize a map call
            // the main idea is that we use the first list item as a base and compute the cut over all list items
            // for overlapping we consider two tagged function calls the same if
            //    1. they have the access paths and the same target node (value false in the stored pair), or
            //    2. they have the same target node
            //      - and their access paths only differ in their prior to last entry,
            //      - with the condition that this is the list index
            //      - we record this information with the value true in the stored pair
            // property -> [(index == list index, function call / constant)]
            Map<String, List<Pair<Boolean, TaggedFunctionCallOrBasicValue>>> propertyToAccessors = new HashMap<>();

            for (var property : properties) { // for every property get the accessors / constants
                List<Pair<Boolean, TaggedFunctionCallOrBasicValue>> base = null;
                for (int i = 0; i < listValue.size(); i++) {
                    var targetIndex = i;
                    var accessorOrConstants = indexToPropertyToAccessors.get(i).get(property);
                    var pairs = accessorOrConstants.stream().map(a -> {
                        if (a.taggedFunctionCall != null) {
                            var originIndex = (int) a.taggedFunctionCall.accessPath.get(-2);
                            return p(originIndex == targetIndex, a);
                        }
                        return p(false, a);
                    }).collect(Collectors.toList());
                    if (i == 0) { // easy, we do not have to do any overlapping
                        base = pairs;
                        continue;
                    }
                    // we now have i > 0
                    base = overlapTaggedFunctionLists(base, pairs);
                    if (base.isEmpty()) { // no overlap, so we can stop the whole endeavor
                        return null;
                    }
                }
                propertyToAccessors.put(property, base);
            }
            // we now have a map of all overlapping accessors for each property
            // the goal now is to produce a map call statement
            // the only thing that we do not know is whether the indexed property accessors have the same origin node
            // this is important as we cannot iterate over multiple origins in a single map call
            // fixing this would be simple, but it should not happen in practice, so we skip it for now
            // the simple heuristic is that we choose the first origin node that we come cross.
            // for choosing the accessors for every property we use the following preference hierarchy:
            //   1. indexed property accessors with the same origin node
            //   2. non indexed property accessors
            //   3. basic values
            Box<Pair<Node, AccessPath>> indexedOriginNode = new Box<>(null); // origin node, base path
            Map<String, CallProperty> propertyToCallProperty = new HashMap<>();
            var iterName = ITER_NAME_PREFIX + mapCallNames.size();
            for (var property : properties) {
                var accessors = propertyToAccessors.get(property).stream()
                        .filter(p -> !p.first || indexedOriginNode.get() == null || // check for same origin if needed
                                p.second.taggedFunctionCall.originNode.equals(indexedOriginNode.get().first))
                        .max((f, s) -> {
                            if (f == s) {
                                return 0;
                            }
                            if (f.first) {
                                return 1;
                            }
                            if (s.first) {
                                return -1;
                            }
                            if (f.second.taggedFunctionCall != null) {
                                return 1;
                            }
                            if (s.second.taggedFunctionCall != null) {
                                return -1;
                            }
                            return 1;
                        });
                if (accessors.isPresent()) {
                    var foundPair = accessors.get();
                    if (foundPair.first) {
                        indexedOriginNode.set(p(foundPair.second.taggedFunctionCall.originNode,
                                foundPair.second.taggedFunctionCall.accessPath.subPath(0, -2)));
                    }
                    FunctionCall call;
                    if (foundPair.second.basicValue != null) { // we have a constant value
                        call = Functions.createWrapperFunctionCall(foundPair.second.basicValue);
                    } else { // we have a function call
                        if (foundPair.first) { // we have to process index accessors differently
                            // we have to transform the accessor to make it use the iter variable instead of the node
                            // access, but we have possibly transformers, so we have two cases
                            var accessor = foundPair.second.taggedFunctionCall.functionCall;
                            var accessorFunction = accessor.getFunction();
                            if (accessorFunction instanceof BasicValueTransformer) {
                                // if we have a basic transformer, it only affects its inner expression which should
                                // be a get function call (by construction)
                                var getCall = (FunctionCall) accessor.getArguments().get(0);
                                call = ((BasicValueTransformer<?>) accessorFunction)
                                        .createCall(subGetCall(foundPair.second.taggedFunctionCall.accessPath,
                                                getCall, indexedOriginNode.get().second, iterName));
                            } else {
                                call = subGetCall(foundPair.second.taggedFunctionCall.accessPath,
                                        foundPair.second.taggedFunctionCall.functionCall,
                                        indexedOriginNode.get().second, iterName);
                            }
                        } else {
                            call = foundPair.second.taggedFunctionCall.functionCall; // simple
                        }
                    }
                    propertyToCallProperty.put(property, new CallProperty(new AccessPath(property), call));
                } else {
                    // we could not find any valid overlap
                    return null;
                }
            }
            String mapCallName = MAP_CALL_NAME_PREFIX + mapCallNames.size();
            mapCallNames.add(mapCallName);
            return new MapCallStatement(ident(mapCallName),
                    Functions.GET_FUNCTION.createCall(
                            get(indexedOriginNode.get().first),
                            indexedOriginNode.get().second),
                    ident(iterName),
                    propertyToCallProperty.entrySet().stream().sorted(Entry.comparingByKey())
                            .map(Entry::getValue).collect(Collectors.toList()));
        }

        /**
         * replace node + base path access with access to iter
         */
        private FunctionCall subGetCall(AccessPath path, FunctionCall getCall, AccessPath basePath, String iterName) {
            var getFunction = getCall.getFunction();
            assert getFunction.equals(GET_FUNCTION);
            // the get function has as its first argument the variable to access (identifier)
            // the other arguments are literals related to the path
            assert path.startsWith(basePath);
            return Functions.GET_FUNCTION.createCall(iterName, path.subPath(basePath.size() + 1, path.size()));
        }

        /**
         * find the overlaps between the passed two lists, as described before
         * TODO: might be problematic of other is not sorted
         */
        private List<Pair<Boolean, TaggedFunctionCallOrBasicValue>>
        overlapTaggedFunctionLists(
                List<Pair<Boolean, TaggedFunctionCallOrBasicValue>> base,
                List<Pair<Boolean, TaggedFunctionCallOrBasicValue>> other) {
            if (base.isEmpty() || other.isEmpty()) { // the simple case
                return List.of();
            }
            // yes, the following has quadratic complexity, but should hopefully not be a problem
            return base.stream().map(b -> {
                TaggedFunctionCallOrBasicValue proper = null;
                TaggedFunctionCallOrBasicValue underIsSameIndexFalseAssumption = null;
                for (Pair<Boolean, TaggedFunctionCallOrBasicValue> o : other) {
                    if (b.first) { // reference another array at the list index
                        if (!o.first) {
                            if (b.second.equals(o.second)) {
                                underIsSameIndexFalseAssumption = b.second;
                                // we mistakenly set the boolean to true if we do not find any other
                            }
                            continue;
                        }
                        // now we also know that o has to be a function call and not a basic value
                        assert b.second.taggedFunctionCall != null && o.second.taggedFunctionCall != null;
                        // now we only have to check that both have the same origin node and start with the same base
                        AccessPath basePath = b.second.taggedFunctionCall.accessPath.subPath(0, -2);
                        if (o.second.taggedFunctionCall.originNode.equals(b.second.taggedFunctionCall.originNode) &&
                                o.second.taggedFunctionCall.accessPath.startsWith(basePath)) {
                            proper = o.second;
                            break;
                        }
                    }
                    if (b.second.equals(o.second)) { // both have to be the same
                        proper = b.second;
                        break;
                    }
                }
                if (proper != null) {
                    return p(b.first, b.second);
                } else if (underIsSameIndexFalseAssumption != null) {
                    return p(false, underIsSameIndexFalseAssumption);
                }
                return null;
            }).filter(Objects::nonNull).collect(Collectors.toList());
        }

        private List<TaggedFunctionCall> doublyToAccessors(Pair<DoublyTaggedBasicValue<?, ?>, Node> doublyAndOrigin) {
            var originNode = doublyAndOrigin.second;
            var origin = get(originNode);
            var doubly = doublyAndOrigin.first;
            FunctionCall call = Functions.createGetFunctionCall(origin, doubly.getOriginPath());
            if (doubly.isDirect()) {
                return List.of(new TaggedFunctionCall(originNode, doubly.getOriginPath(), call));
            }
            return doubly.getTransformers().stream()
                    .map(t -> new TaggedFunctionCall(originNode, doubly.getOriginPath(), t.createCall(call)))
                    .collect(Collectors.toList());
        }

        private List<Statement> createRequestCallStatements(Node node) {
            var statements = new ArrayList<Statement>();
            var origin = node.getOrigin();
            assert origin != null;
            var request = origin.first;
            Map<AccessPath, Expression> usedPaths = new HashMap<>();
            Set<AccessPath> ignoredPaths = new HashSet<>();
            if (options.mapCallStatements) {
                searchForMapCalls(node).entrySet().stream().sorted(Entry.comparingByKey()).forEach(e -> {
                    node.getOrigin().first.asCombined().getTaggedValues().map(TaggedBasicValue::getPath)
                            .filter(p -> p.startsWith(e.getKey())).forEach(ignoredPaths::add);
                    usedPaths.put(e.getKey(), e.getValue().getVariable());
                    statements.add(e.getValue());
                });
            }
            for (Edge edge : node.getDependsOn()) {
                var target = get(edge.getTarget());
                for (DoublyTaggedBasicValue<?, ?> usedValue : edge.getUsedValues().stream()
                        .sorted(Comparator.comparing(DoublyTaggedBasicValue::getOriginPath))
                        .collect(Collectors.toList())) {
                    FunctionCall call = Functions.createGetFunctionCall(target, usedValue.getOriginPath());
                    if (!usedValue.isDirect()) {
                        call = usedValue.getSingleTransformer().createCall(call);
                    }
                    for (AccessPath atSetPath : usedValue.getTargetPaths()) {
                        //assert !usedPaths.containsKey(atSetPath);
                        if (!usedPaths.containsKey(atSetPath) && !ignoredPaths.contains(atSetPath)) {
                            usedPaths.put(atSetPath, call);
                        }
                    }
                }
            }
            request.asCombined().getTaggedValues()
                    .filter(t -> !(usedPaths.containsKey(t.getPath()) || ignoredPaths.contains(t.getPath())))
                    .forEach(t -> usedPaths.put(t.getPath(), createWrapperFunctionCall(t.getValue())));
            if (request instanceof EventRequestCmds.SetRequest) {
                var setRequest = (SetRequest) request;
                for (int i = 0; i < setRequest.modifiers.size(); i++) {
                    var modifier = setRequest.modifiers.get(i);
                    usedPaths.put(new AccessPath("modifiers", i, "kind"),
                            createWrapperFunctionCall(wrap(modifier.getClass().getSimpleName())));
                }
            }
            var requestCall = new RequestCall(request.getCommandSetName(), request.getCommandName(),
                    usedPaths.keySet().stream().sorted().map(p -> new CallProperty(p, usedPaths.get(p)))
                            .collect(Collectors.toList()));
            statements.add(new AssignmentStatement(ident(get(node)), requestCall));
            return statements;
        }

        private PacketCall createPacketCauseCall(Either<Request<?>, Events> call) {
            return call.isLeft() ? RequestCall.create(call.getLeft()) : EventsCall.create(call.getRight());
        }
    }

    // draft of loop detection code
    /*private static Optional<Pair<Program, Set<Node>>> isPossibleLoopSourceNode(NodeNames variables, Layers layers,
    Node node, int minSize) {
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
            return Optional.empty(); // dependent nodes depend on other nodes of the current layer, possibly mixing
            two loops
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
            var headers = entry.getValue().stream().flatMap(p -> pathToNodes.get(p).stream()).collect(Collectors
            .toSet());
            headersPerIndex.put(entry.getKey(), headers);
            Set<Node> body = layers.computeDominatedNodes(headers);
            if (body == null) { // dominated nodes might depend on nodes in upper layers
                return Optional.empty();
            }
            // check that all body nodes do not depend on other list items
            if (body.stream().anyMatch(n -> n.getDependsOn().stream().anyMatch(e -> {
                if (e.getTarget().equals(node)) { // loop source node?
                    return e.getUsedValues().stream().anyMatch(t -> t.path.startsWith(prefix)
                            && finalDifferingIndex < t.path.size() && t.path.get(finalDifferingIndex) != entry.getKey
                            ());
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
        finishedStatements.add(new Loop(AST.ident(iterNameAndCall.first), AST.ident(iterableNameAndCall.first),
        merged.getBody()));
        variables.unmarkIterPath(node, prefix);
        return Optional.of(p(new Program(finishedStatements), processedNodes));
    }*/

    private static Pair<Program, Set<Node>> processNodes(NodeNames variables, Layers layers, Set<Node> fullBody,
                                                         int minSize) {
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
            statements.addAll(variables.createRequestCallStatements(node));
            /*var loopRes = isPossibleLoopSourceNode(variables.child(), layers, node, minSize);
            loopRes.ifPresent(programSetPair -> {
                statements.addAll(programSetPair.first.getBody());
                ignoredNodes.addAll(loopRes.get().second);
            });*/
        }
        return p(new Program(statements), fullBody);
    }
}
