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
import tunnel.synth.program.AST;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Functions.BasicValueTransformer;
import tunnel.synth.program.Program;
import tunnel.util.Box;
import tunnel.util.Either;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static tunnel.synth.program.AST.*;
import static tunnel.synth.program.Functions.*;

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
        private boolean mapCallStatements = true;

        /**
         * Minimal number of items in a list for a list to be considered in map call synthesis.
         */
        @With
        private int mapCallStatementsMinListSize = 2;

        @With
        private boolean loops = true;
        /**
         * Minimal number of lsit items have nodes that depend on them, for a list valued node to be considered
         * a loop header node in loop synthesis
         */
        @With
        private int loopMinListSize = 2;
        /**
         * synthesize switch cases in loops to distinguish between types
         */
        @With
        private boolean switchCaseInLoop = true;
    }

    public final static SynthesizerOptions DEFAULT_OPTIONS = new SynthesizerOptions();
    public final static SynthesizerOptions MINIMAL_OPTIONS =
            new SynthesizerOptions(false, 2, false, 2, false);

    @Override
    public void accept(Partition partition) {
        submit(synthesizeProgram(partition));
    }

    public static final String CAUSE_NAME = "cause";
    public static final String NAME_PREFIX = "var";
    public static final String MAP_CALL_NAME_PREFIX = "map";
    public static final String ITER_NAME_PREFIX = "iter";

    public static Program synthesizeProgram(Partition partition) {
        return synthesizeProgram(partition, DEFAULT_OPTIONS);
    }

    public static Program synthesizeProgram(Partition partition, SynthesizerOptions options) {
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
        var program = processNodes(names, layers, layers.getAllNodesWithoutDuplicates()).first;
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
        private final Map<Integer, Map<AccessPath, Expression>> preHandledAccessPaths;

        public NodeNames(SynthesizerOptions options) {
            this.options = options;
            this.names = new HashMap<>();
            this.mapCallNames = new HashSet<>();
            this.nameCount = 0;
            this.iterNameCount = 0;
            this.preHandledAccessPaths = new HashMap<>();
        }

        private boolean hasPrehandledAccessPaths(Node node) {
            return preHandledAccessPaths.containsKey(node.getId());
        }

        private boolean isPrehandledAccessPath(Node node, AccessPath accessPath) {
            return preHandledAccessPaths.get(node.getId()).containsKey(accessPath);
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
            assert !hasPrehandledAccessPaths(node);
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

        @lombok.Value
        private static class TaggedFunctionCall {
            Node originNode;
            AccessPath accessPath;
            FunctionCall functionCall;

            @Override
            public String toString() {
                return String.format("(node=%s, %s)", originNode.getId(), functionCall);
            }
        }

        @lombok.Value
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

        /**
         * takes care of the handling of {@link #preHandledAccessPaths}
         */
        private Expression createGetFunctionCall(Node node, AccessPath path) {
            if (preHandledAccessPaths.containsKey(node.getId())) {
                for (Entry<AccessPath, Expression> e :
                        preHandledAccessPaths.get(node.getId()).entrySet()) {
                    if (path.equals(e.getKey())) {
                        return e.getValue();
                    }
                    if (path.startsWith(e.getKey())) {
                        return Functions.GET_FUNCTION.createCall(e.getValue(), path.subPath(e.getKey().size(),
                                path.size()));
                    }
                }
            }
            return Functions.GET_FUNCTION.createCall(get(node), path);
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
                    Expression call = createGetFunctionCall(edge.getTarget(), usedValue.getOriginPath());
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

        @lombok.Value
        private static class LoopIterationNodes {
            Node loopHeader;
            int iteration;
            /**
             * directly depend on the loop header node
             */
            Set<Node> innerHeader;
            Map<Node, List<Edge>> innerHeaderWithValidEdges;
            /**
             * only depend on the inner header and on nodes in levels above the loop header
             */
            Set<Node> otherNodes;
            Map<Node, List<Edge>> otherNodesWithValidEdges;

            /**
             * do all requests have the same type as the loop header request?
             */
            boolean isSingleRequest() {
                var expectedClass = loopHeader.getOrigin().first.getClass();
                return Stream.concat(innerHeader.stream(), otherNodes.stream())
                        .allMatch(n -> n.getOrigin().first.getClass().equals(expectedClass));
            }

            Set<Node> getAllNodesWithValidEdges() {
                var all = new HashMap<>(innerHeaderWithValidEdges);
                all.putAll(otherNodesWithValidEdges);
                return cloneNodesWithValidEdgesOnly(all);
            }

            Set<Node> getAllNodes() {
                return Stream.concat(innerHeader.stream(), otherNodes.stream())
                        .collect(Collectors.toSet());
            }
        }

        private Pair<List<Statement>, Set<Node>> findLoopsWithNodeAsHeader(Node node, Layers layers,
                                                                           Set<Node> usableNodes) {
            // is NameVariables the right place? probably not, but here we are
            if (!options.loops) {
                return p(List.of(), Set.of());
            }
            assert node.getOrigin() != null;
            var headerValue = node.getOrigin().second.asCombined();
            if (!headerValue.hasListValuedFields()) { // no support for nested lists (or lists nested deeper in object)
                return p(List.of(), Set.of());
            }
            if (node.getDependedByNodes().isEmpty()) {
                return p(List.of(), Set.of());
            }
            Set<Node> alreadyCapturedNodes = new HashSet<>(); // used to ensure that nodes do only depend on single
            // index of a single list valued field
            List<Statement> producedStatements = new ArrayList<>();
            Set<Node> usedNodes = new HashSet<>();
            for (var field : headerValue.getListValuedFields()) {
                var fieldValue = (ListValue<?>) headerValue.get(field);
                // find now all nodes directly dependent to every index of the list
                // every of these nodes can only depend on a single index of the list
                Map<Integer, LoopIterationNodes> loopIterationBodies = new HashMap<>();
                var skipOuter = false;
                for (int i = 0; i < fieldValue.size(); i++) {
                    var innerHeader = new HashSet<Node>();
                    var currentPath = new AccessPath(field, i);
                    for (Edge edge : node.getDependedByField(currentPath)) {
                        if (alreadyCapturedNodes.contains(edge.getTarget())) {
                            skipOuter = true;
                            break;
                        }
                        alreadyCapturedNodes.add(edge.getTarget());
                        innerHeader.add(edge.getTarget());
                    }
                    if (skipOuter) {
                        break;
                    }
                    if (innerHeader.isEmpty()) {
                        continue;
                    }
                    var innerHeaderWithValidEdges = findValidLoopNodes(node, currentPath, layers, Set.of(),
                            innerHeader);
                    if (innerHeaderWithValidEdges == null) {
                        skipOuter = true;
                        break;
                    }
                    // we extend now the collected list of nodes to include all depending nodes (transitive closure)
                    // invalid assumption: the closures have to be disjunctive and form the bodies of every iteration
                    var otherNodes = DependencyGraph.computeDependedByTransitive(innerHeader);
                    var otherNodesWithValidEdges = findValidLoopNodes(node, currentPath, layers, innerHeader,
                            otherNodes);
                    if (otherNodesWithValidEdges == null) {
                        skipOuter = true;
                        break;
                    }
                    alreadyCapturedNodes.addAll(otherNodes);
                    loopIterationBodies.put(i, new LoopIterationNodes(node, i,
                            innerHeader, innerHeaderWithValidEdges,
                            otherNodes, otherNodesWithValidEdges));
                }
                if (skipOuter || loopIterationBodies.size() < options.loopMinListSize) {
                    continue; // too small
                }
                Statement loop;
                if (loopIterationBodies.values().stream().allMatch(LoopIterationNodes::isSingleRequest)) {
                    loop = handleSingleRequestLoop(node, new AccessPath(field), loopIterationBodies);
                } else {
                    loop = handleRegularLoop(node, new AccessPath(field), loopIterationBodies);
                }
                if (loop != null) {
                    producedStatements.add(loop);
                    usedNodes.addAll(loopIterationBodies.values().stream().flatMap(b -> b.getAllNodes().stream())
                            .collect(Collectors.toSet()));
                }
            }
            return p(producedStatements, usedNodes);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Set<Node> cloneNodesWithValidEdgesOnly(Map<Node, List<Edge>> nodes) {
            Map<Node, Node> newNodesForOld = new HashMap<>();
            BiFunction<BiFunction, Node, Node> get = (getFunc, oldNode) -> {
                if (!nodes.containsKey(oldNode)) {
                    return oldNode;
                }
                if (!newNodesForOld.containsKey(oldNode)) {
                    Node newNode = new Node(oldNode.getId(), oldNode.getOrigin());
                    for (Edge edge : nodes.get(oldNode)) {
                        newNode.addDependsOn(new Edge(newNode,
                                ((BiFunction<BiFunction, Node, Node>) getFunc).apply(getFunc, edge.getTarget()),
                                edge.getUsedValues()));
                    }
                    newNodesForOld.put(oldNode, newNode);
                }
                return newNodesForOld.get(oldNode);
            };
            return nodes.keySet().stream().map(n -> get.apply(get, n)).collect(Collectors.toSet());
        }

        private @Nullable Map<Node, List<Edge>> findValidLoopNodes(Node headerNode, AccessPath currentListItemPath,
                                                                   Layers layers, Set<Node> miscNodes,
                                                                   Set<Node> nodes) {
            Map<Node, List<Edge>> nodesWithValidEdges = new HashMap<>();
            for (Node otherNode : nodes) {
                var layer = layers.getLayerIndex(headerNode);
                List<Edge> validEdges = new ArrayList<>();
                // paths that have an edge that points to the currentListItemPath
                Set<AccessPath> pathsThatPointToLoopHeader = new HashSet<>();
                // paths ... to any other valid node
                Set<AccessPath> pathsThatPointToLoopBody = new HashSet<>();
                for (Edge e : otherNode.getDependsOn()) {
                    Stream<AccessPath> validPaths;
                    if (e.getTarget().equals(headerNode)) {
                        e.getUsedValues().stream()
                                .filter(d -> d.getOriginPath().startsWith(currentListItemPath))
                                .flatMap(d -> d.getTargetPaths().stream()).forEach(pathsThatPointToLoopHeader::add);
                    } else if (miscNodes.contains(e.getTarget()) || nodes.contains(e.getTarget())) {
                        e.getUsedValues().stream().flatMap(d -> d.getTargetPaths().stream())
                                .forEach(pathsThatPointToLoopBody::add);
                    }
                }
                Set<AccessPath> allCollectedAccessPaths = new HashSet<>();
                for (Edge edge : otherNode.getDependsOn()) {
                    var n2 = edge.getTarget();
                    allCollectedAccessPaths.addAll(edge.getUsedValues().stream()
                            .flatMap(d -> d.getTargetPaths().stream()).collect(Collectors.toSet()));
                    var valid = layers.getLayerIndex(n2) < layer ||
                            n2.equals(headerNode) || miscNodes.contains(n2) || nodes.contains(n2);
                    if (!valid) {
                        continue;
                    }
                    var isLoopHeader = n2.equals(headerNode);
                    var newEdge = new Edge(otherNode, edge.getTarget(), edge.getUsedValues().stream().map(d -> {
                        var newPaths = d.getTargetPaths().stream().filter(p -> {
                            if (isLoopHeader) {
                                if (pathsThatPointToLoopHeader.contains(p)) {
                                    return true;
                                }
                            }
                            return true;
                        }).collect(Collectors.toList());
                        if (newPaths.isEmpty()) {
                            return null;
                        }
                        return d.withNewTargetPaths(newPaths);
                    }).filter(Objects::nonNull).collect(Collectors.toList()));
                    if (!allCollectedAccessPaths.stream()
                            .allMatch(p -> pathsThatPointToLoopHeader.contains(p) ||
                                    pathsThatPointToLoopBody.contains(p))) {
                        continue;
                    }
                    if (newEdge.getUsedValues().size() > 0) {
                        validEdges.add(newEdge);
                    }
                }
                if (validEdges.isEmpty()) {
                    return null;
                }
                nodesWithValidEdges.put(otherNode, validEdges);
            }
            return nodesWithValidEdges;
        }

        private Statement handleSingleRequestLoop(Node node, AccessPath field,
                                                  Map<Integer, LoopIterationNodes> loopIterationBodies) {
            // single request loop like
            //  (= var19 (request ReferenceType Interfaces ("refType")=(get var16 "typeID")))
            //  (= var22 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 2)))
            //  (= var23 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 1)))
            //  (= var24 (request ReferenceType Interfaces ("refType")=(get var19 "interfaces" 0))))
            System.out.println("do later");
            return null;
        }

        private @Nullable Loop handleRegularLoop(Node node, AccessPath field,
                                                 Map<Integer, LoopIterationNodes> loopIterationBodies) {
            String iter = createNewIterName();
            String nodeName = get(node);
            // make programs out of the loop iterations
            // replacing `(get node field index)` with `iter`
            Map<Integer, Body> iterationBodies = new HashMap<>();
            for (LoopIterationNodes iteration : loopIterationBodies.values()) {
                var currentPath = field.append(iteration.iteration);
                var nodes = iteration.getAllNodesWithValidEdges();
                var layers = DependencyGraph.computeLayers(null, nodes);
                var nodeNames = new NodeNames(options);
                nodeNames.names.put(node, nodeName);
                nodeNames.nameCount = nameCount;
                nodeNames.preHandledAccessPaths.putAll(preHandledAccessPaths);
                nodeNames.preHandledAccessPaths.computeIfAbsent(node.getId(), x -> new HashMap<>())
                        .put(currentPath, AST.ident(iter));
                iterationBodies.put(iteration.iteration, processNodes(nodeNames, layers, nodes).first.getBody());
            }
            // find a field that is used in all iterations
            // iteration -> field (relative to list item), path might be empty -> value
            Map<Integer, Map<AccessPath, jdwp.Value>> fieldUsedFromList = new HashMap<>();
            for (LoopIterationNodes iteration : loopIterationBodies.values()) {
                AccessPath currentPath = field.append(iteration.iteration);
                Map<AccessPath, jdwp.Value> fieldUsedFromListIteration =
                        iteration.getInnerHeader().stream()
                                .flatMap(h -> h.getDependsOn().stream()
                                        .flatMap(e -> e.getTarget().equals(node) ?
                                                e.getUsedValues().stream()
                                                        .filter(d -> d.getOriginPath().startsWith(currentPath))
                                                        .map(d -> Map.entry(d.getOriginPath().subPath(currentPath.size(),
                                                                d.getOriginPath().size()),
                                                                (jdwp.Value) d.getValueAtOrigin()))
                                                : Stream.empty()))
                                .distinct().collect(Collectors.toMap(Entry::getKey, Entry::getValue));
                fieldUsedFromList.put(iteration.iteration, fieldUsedFromListIteration);
            }
            // find fields that is used in all iterations, check whether type tag is unequal
            // field -> type tags -> bodys // TODO: extend to more or use transformers
            Map<AccessPath, Map<Integer, List<Body>>> commonFields = null;
            for (var entry : fieldUsedFromList.entrySet()) {
                var fieldsToValue = entry.getValue();
                var body = iterationBodies.get(entry.getKey());
                var tags = fieldsToValue.entrySet().stream()
                        .collect(Collectors.toMap(Entry::getKey, e -> e.getValue().type.getTag()));
                if (commonFields == null) {
                    commonFields = new HashMap<>();
                    for (Entry<AccessPath, Integer> e : tags.entrySet()) {
                        Map<Integer, List<Body>> map = new HashMap<>();
                        map.put(e.getValue(), new ArrayList<>(List.of(body)));
                        commonFields.put(e.getKey(), map);
                    }
                } else {
                    for (AccessPath path : tags.keySet()) {
                        if (commonFields.containsKey(path)) {
                            commonFields.get(path).computeIfAbsent(tags.get(path), x -> new ArrayList<>()).add(body);
                        }
                    }
                }
            }
            // now choose the field with the most diversity
            var possibleField = commonFields.entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue().size())).map(Entry::getKey);
            if (possibleField.isEmpty()) {
                return null;
            }
            Body loopBody;
            if (!options.switchCaseInLoop || (commonFields.get(possibleField.get()).size() == 1 &&
                    loopIterationBodies.size() == ((ListValue<?>) field.access(node.getOrigin().second.asCombined())).size())) {
                // not enough diversity: neither enough different types, nor missing iterations which might be explained
                // with different types
                // but: we can just merge all iterations into one (hopefully without crashing the JVM)
                loopBody = mergeBodies(iterationBodies.keySet().stream()
                        .sorted().map(iterationBodies::get).collect(Collectors.toList()));
            } else {
                var switchExpression =
                        GET_TAG_FOR_VALUE.createCall(possibleField.get().size() > 0 ?
                                GET_FUNCTION.createCall(ident(iter), possibleField.get()) : ident(iter));
                // create a switch case over the type and merge all bodies for the same type
                Map<Integer, List<Body>> bodiesPerType = commonFields.get(possibleField.get());
                loopBody = new Body(List.of(new SwitchStatement(switchExpression, bodiesPerType.entrySet().stream()
                        .sorted(Comparator.comparingInt(Entry::getKey))
                        .map(e -> new CaseStatement(Functions.createWrapperFunctionCall(wrap((byte) (int) e.getKey())),
                                mergeBodies(e.getValue())))
                        .collect(Collectors.toList()))));
            }
            return new Loop(ident(iter), GET_FUNCTION.createCall(nodeName, field), loopBody);
        }

        private Body mergeBodies(List<Body> bodies) {
            var body = bodies.get(0);
            for (var i = 1; i < bodies.size(); i++) {
                body = body.merge(bodies.get(i));
            }
            return body;
        }
    }

    private static Pair<Program, Set<Node>> processNodes(NodeNames variables, Layers layers, Set<Node> fullBody) {
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
            var foundLoops = variables.findLoopsWithNodeAsHeader(node, layers,
                    fullBody.stream().filter(n -> !ignoredNodes.contains(n)).collect(Collectors.toSet()));
            if (!foundLoops.first.isEmpty()) {
                ignoredNodes.addAll(foundLoops.second);
                statements.addAll(foundLoops.first);
            }
        }
        return p(new Program(statements), fullBody);
    }
}
