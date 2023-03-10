package tunnel.synth;

import jdwp.*;
import jdwp.EventRequestCmds.SetReply;
import jdwp.EventRequestCmds.SetRequest;
import jdwp.EventRequestCmds.SetRequest.ClassMatch;
import jdwp.JDWP.Error;
import jdwp.MethodCmds.VariableTableReply;
import jdwp.MethodCmds.VariableTableRequest;
import jdwp.ReferenceTypeCmds.SourceDebugExtensionRequest;
import jdwp.StackFrameCmds.GetValuesReply;
import jdwp.StackFrameCmds.GetValuesRequest;
import jdwp.StackFrameCmds.GetValuesRequest.SlotInfo;
import jdwp.ThreadReferenceCmds.FrameCountReply;
import jdwp.ThreadReferenceCmds.FrameCountRequest;
import jdwp.util.Pair;
import jdwp.util.TestReply;
import jdwp.util.TestRequest;
import org.junit.jupiter.api.Test;
import tunnel.synth.DependencyGraph.DoublyTaggedBasicValue;
import tunnel.synth.DependencyGraph.Edge;
import tunnel.synth.DependencyGraph.Node;
import tunnel.synth.Partitioner.Partition;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Program;
import tunnel.util.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.Reference.*;
import static jdwp.Value.Type.OBJECT;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.*;
import static tunnel.synth.DependencyGraph.DEFAULT_OPTIONS;
import static tunnel.synth.Synthesizer.MINIMAL_OPTIONS;

public class DependencyGraphTest {

    @Test
    public void testCalculateBasicDiamondGraph() {
        var start = rrpair(1, 1, 2);
        var left = rrpair(2, 2, 3);
        var right = rrpair(3, 2, 4);
        var end = rrpair(4, List.of(p("left", 3), p("right", 4)),
                List.of(p("value", 5)));
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, left, right, end));
        var graph = DependencyGraph.compute(partition, DependencyGraph.MINIMAL_OPTIONS);
        assertNotEquals(graph.getCauseNode(), graph.getNode(start));
        assertNull(graph.getCauseNode().getOrigin());
        assertEquals(-1, graph.getCauseNode().getId());
        assertEquals(Set.of(), graph.getCauseNode().getDependsOnNodes());
        assertEquals(Set.of(), graph.getNode(start).getDependsOnNodes());
        assertEquals(Set.of(graph.getNode(start)), graph.getNode(left).getDependsOnNodes());
        var e = DoublyTaggedBasicValue.createDirect(new AccessPath("value"),
                start.first.getContainedValues().getFirstTaggedValue(wrap(1)));
        assertEquals(new Edge(graph.getNode(left),
                        graph.getNode(start), List.of(e)),
                graph.getNode(left).getDependsOn().iterator().next());
        assertEquals(Set.of(graph.getNode(left), graph.getNode(right)), graph.getNode(end).getDependsOnNodes());
        var layers = graph.computeLayers();
        assertEquals(4, layers.size());
        assertEquals(Set.of(graph.getNode(start)), layers.get(1));
        assertEquals(Set.of(graph.getNode(left), graph.getNode(right)), layers.get(2));
        assertEquals(Set.of(graph.getNode(end)), layers.get(3));
    }

    @Test
    public void testCalculateGraphWithDifferentReferences() {
        var start = rrvpair(1, Reference.classType(1), Reference.thread(1));
        var end = rrvpair(4, List.of(p("left", Reference.interfaceType(1)),
                        p("right", Reference.thread(1))),
                List.of(p("value", wrap(5))));
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, end));
        var graph = DependencyGraph.compute(partition);
        assertNotEquals(graph.getCauseNode(), graph.getNode(start));
        assertEquals(Set.of(), graph.getCauseNode().getDependsOnNodes());
        assertEquals(Set.of(), graph.getNode(start).getDependsOnNodes());
        assertEquals(Set.of(graph.getNode(start), graph.getCauseNode()), graph.getNode(end).getDependsOnNodes());
        var layers = graph.computeLayers();
        assertEquals(3, layers.size());
        assertEquals(Set.of(graph.getNode(start)), layers.get(1));
        assertEquals(Set.of(graph.getNode(end)), layers.get(2));
    }

    @Test
    public void testCalculateGraphWithMultipleValuesFromSameObject() {
        var start = rrpair(1, List.of(), List.of(p("left", 1), p("right", 2)));
        var end = rrpair(4, List.of(p("left", 1), p("right", 2)), List.of());
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, end));
        var graph = DependencyGraph.compute(partition);
        assertNotEquals(graph.getCauseNode(), graph.getNode(start));
        assertEquals(Set.of(), graph.getCauseNode().getDependsOnNodes());
        assertEquals(Set.of(), graph.getNode(start).getDependsOnNodes());
        assertEquals(Set.of(graph.getNode(start)), graph.getNode(end).getDependsOnNodes());
        assertEquals(
                Set.of(new AccessPath("left"), new AccessPath("right")),
                graph.getNode(end).getDependsOn().iterator().next().getUsedValues().stream()
                        .map(v -> v.getTargetPaths().get(0))
                        .collect(Collectors.toSet()));
        var layers = graph.computeLayers();
        assertEquals(3, layers.size());
        assertEquals(Set.of(graph.getNode(start)), layers.get(1));
        assertEquals(Set.of(graph.getNode(end)), layers.get(2));
    }

    @Test
    public void testCalculateGraphWithMultipleValuesFromSameObjectSwapped() {
        var start = rrpair(1, List.of(), List.of(p("left", 2), p("right", 1)));
        var end = rrpair(4, List.of(p("left", 1), p("right", 2)), List.of());
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, end));
        var graph = DependencyGraph.compute(partition, DependencyGraph.MINIMAL_OPTIONS);
        assertEquals(
                Set.of(new AccessPath("right"), new AccessPath("left")),
                graph.getNode(end).getDependsOn().iterator().next().getUsedValues().stream()
                        .map(v -> v.getTargetPaths().get(0))
                        .collect(Collectors.toSet()));
    }

    @Test
    public void testNodeComparatorWithDiamondGraph() {
        var start = rrpair(1, 1, 2);
        var left = rrpair(2, 2, 3);
        var right = rrpair(3, 2, 4);
        var end = rrpair(4, List.of(p("left", 3), p("right", 4)),
                List.of(p("value", 5)));
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, left, right, end));
        var graph = DependencyGraph.compute(partition, DependencyGraph.MINIMAL_OPTIONS);
        var layers = graph.computeLayers();
        var comparator = layers.getNodeComparator();
        assertEquals(0, comparator.compare(graph.getNode(start), graph.getNode(start)));
        assertEquals(-1, comparator.compare(graph.getNode(start), graph.getNode(left)));
        assertEquals(-1, comparator.compare(graph.getNode(left), graph.getNode(right)));
        assertEquals(-1, comparator.compare(graph.getNode(right), graph.getNode(end)));
    }

    @Test
    public void testDependedByConstruction() {
        var start = rrpair(1, 1, 2);
        var end = rrpair(2, 2, 3);
        // start -> end
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, end));
        var graph = DependencyGraph.compute(partition);
        assertEquals(Set.of(graph.getNode(end)), graph.getNode(start).getDependedByNodes());
    }

    @Test
    public void testComputeDependedByTransitive() {
        var start = rrpair(1, 1, 2);
        var end = rrpair(2, 2, 3);
        // start -> end
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, end));
        var graph = DependencyGraph.compute(partition);
        var startNode = graph.getNode(start);
        var endNode = graph.getNode(end);
        assertEquals(Set.of(), DependencyGraph.computeDependedByTransitive(Set.of(startNode, endNode)));
        assertEquals(Set.of(), DependencyGraph.computeDependedByTransitive(Set.of(endNode)));
        assertEquals(Set.of(endNode), startNode.computeDependedByTransitive());
        assertEquals(Set.of(endNode), DependencyGraph.computeDependedByTransitive(Set.of(startNode)));
        assertEquals(Set.of(), endNode.computeDependedByTransitive());
    }

    @Test
    public void testComputeDominatedNodes() {
        var start = rrpair(1, 1, 2);
        var left = rrpair(2, 2, 3);
        var right = rrpair(3, 2, 4);
        var end = rrpair(4, List.of(p("left", 3)),
                List.of(p("value", 5)));
        // start -> left -> end
        //       -> right
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, left, right, end));
        var graph = DependencyGraph.compute(partition, DependencyGraph.MINIMAL_OPTIONS);
        var layers = graph.computeLayers();
        var leftNode = graph.getNode(left);
        var endNode = graph.getNode(end);
        assertEquals(Set.of(endNode), leftNode.computeDependedByTransitive());
        assertEquals(Set.of(endNode), DependencyGraph.computeDependedByTransitive(Set.of(leftNode)));
        assertEquals(Set.of(graph.getNode(end)), layers.computeDominatedNodes(Set.of(graph.getNode(left))));
        assertEquals(Set.of(2, 3, 4),
                layers.computeDominatedNodes(Set.of(graph.getNode(start))).stream().map(Node::getId).collect(Collectors.toSet()));
    }

    @Test
    public void testGetAllNodes() {
        var partition = new Partition(null, List.of(
                p(new jdwp.VirtualMachineCmds.IDSizesRequest(174),
                        new jdwp.VirtualMachineCmds.IDSizesReply(174,
                                PrimitiveValue.wrap(8), PrimitiveValue.wrap(8), PrimitiveValue.wrap(8),
                                PrimitiveValue.wrap(8), PrimitiveValue.wrap(8)))));
        var graph = DependencyGraph.compute(partition);
        assertTrue(graph.getAllNodes().stream().allMatch(Objects::nonNull),
                "getAllNodes() should not return null values");
        assertEquals(1, graph.getAllNodesWOCause().size());
    }

    @Test
    public void testGraphWithCauseAsEntry() {
        var start = rrpair(1, 1, 2);
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start));
        var graph = DependencyGraph.compute(partition);
        assertEquals(1, graph.getAllNodesWOCause().size());
    }

    @Test
    public void testGetAllNodesWithoutDuplicates() {
        var partition = new Partition(Either.left(new jdwp.ThreadReferenceCmds.NameRequest(425824,
                new ThreadReference(1136L))), List.of(
                p(new jdwp.ThreadReferenceCmds.NameRequest(425824, new ThreadReference(1136L)),
                        new jdwp.ThreadReferenceCmds.NameReply(425824, PrimitiveValue.wrap("process reaper"))),
                p(new jdwp.ThreadReferenceCmds.ThreadGroupRequest(425825, new ThreadReference(1136L)),
                        new jdwp.ThreadReferenceCmds.ThreadGroupReply(425825, new ThreadGroupReference(2L))),
                p(new jdwp.ThreadReferenceCmds.StatusRequest(425826, new ThreadReference(1136L)),
                        new jdwp.ThreadReferenceCmds.StatusReply(425826, PrimitiveValue.wrap(1),
                                PrimitiveValue.wrap(1))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(425827, new ThreadReference(1136L),
                                PrimitiveValue.wrap(0), PrimitiveValue.wrap(1)),
                        new jdwp.ThreadReferenceCmds.FramesReply(425827, new ListValue<>(Type.LIST,
                                List.of(new ThreadReferenceCmds.FramesReply.Frame(new FrameReference(65536L),
                                        new Location(new ClassTypeReference(110L),
                                                new MethodReference(105553119273528L),
                                                PrimitiveValue.wrap((long) -1))))))),
                p(new jdwp.ThreadReferenceCmds.NameRequest(425828, new ThreadReference(1137L)),
                        new jdwp.ThreadReferenceCmds.NameReply(425828, PrimitiveValue.wrap("process reaper")))
        ));
        var graph = DependencyGraph.compute(partition);
        assertEquals(6, graph.getAllNodes().size());
        var layers = graph.computeLayers();
        assertEquals(6, layers.getAllNodes().size());
        assertEquals(6, layers.getAllNodesWithoutDuplicates().size());
    }


    @Test
    public void testGetAllNodesWithoutDuplicatesWithDuplicates() {
        Function<Integer, Pair<Request<?>, Reply>> func = id -> p(new jdwp.VirtualMachineCmds.IDSizesRequest(id),
                new jdwp.VirtualMachineCmds.IDSizesReply(id, wrap(8), wrap(8),
                        wrap(8), wrap(8), wrap(8)));
        var partition = new Partition(Either.left(func.apply(1).first),
                List.of(func.apply(1), func.apply(3), func.apply(4)));
        var graph = DependencyGraph.compute(partition);
        assertEquals(2, graph.getAllNodes().size());
        var layers = graph.computeLayers();
        assertEquals(2, layers.getAllNodes().size());
        assertEquals(2, layers.getAllNodesWithoutDuplicates().size());
    }

    @Test
    public void testUseTransformers() {
        Function<Integer, Pair<Request<?>, Reply>> func = id -> p(new jdwp.VirtualMachineCmds.IDSizesRequest(id),
                new jdwp.VirtualMachineCmds.IDSizesReply(id, wrap(8), wrap(8),
                        wrap(8), wrap(8), wrap(8)));
        var partition = new Partition(Either.left(func.apply(1).first),
                List.of(func.apply(1), p(new GetValuesRequest(2, Reference.thread(1L), Reference.frame(1L),
                                new ListValue<>(new SlotInfo(wrap(1), wrap((byte) Type.INT.getTag())))),
                        new GetValuesReply(2, new ListValue<>(Type.LIST, List.of(wrap(1)))))));
        var graph = DependencyGraph.compute(partition,
                DependencyGraph.MINIMAL_OPTIONS.withCheckPropertyNames(false).withUseTransformers(true));
        var doublies =
                graph.getNode(partition.get(1)).getDependsOn().stream().flatMap(e -> e.getUsedValues().stream()).collect(Collectors.toList());
        assertEquals(1, doublies.size());
        var doubly = doublies.get(0);
        assertFalse(doubly.isDirect());
        assertEquals(List.of(Functions.GET_TAG_FOR_VALUE), doubly.getTransformers());
        assertEquals(wrap(8), doubly.getValueAtOrigin());
        assertEquals(wrap((byte) Type.INT.getTag()), doubly.getValueAtTarget());
    }

    @Test
    public void testGetTransformers() {
        assertEquals(1, Functions.getTransformers(new VM(0), wrap(1), wrap((byte) Type.INT.getTag())).size());
    }

    /**
     * Returns a partition with empty cause, consisting of {@link VariableTableRequest} with a reply
     * of slotCount slots (SlotInfo(codeIndex=1, name=index+1, signature=I, length=1, slot=index+1)),
     * It is followed by a {@link GetValuesRequest} with a request the contains all slots of the previous request
     * (SlotInfo(slot=index + 1, sigbyte=tag of int))
     */
    static Partition getGetValuesRequestPartition(int slotCount) {
        Function<Integer, VariableTableReply.SlotInfo> slotInfoCreator = i ->
                new VariableTableReply.SlotInfo(wrap(1L), wrap("" + i),
                        wrap(Type.INT.getFirstSignatureChar()), wrap(1), wrap(i));
        Function<Integer, GetValuesRequest.SlotInfo> slotInfoCreator2 = i ->
                new GetValuesRequest.SlotInfo(wrap(i), wrap((byte) Type.INT.getTag()));
        return new Partition(null, List.of(
                p(new VariableTableRequest(1, Reference.klass(10), Reference.method(32505856)),
                        new VariableTableReply(1, wrap(2),
                                new ListValue<>(OBJECT, IntStream.range(0, slotCount)
                                        .mapToObj(i -> slotInfoCreator.apply(i + 1))
                                        .collect(Collectors.toList())))),
                p(new GetValuesRequest(2, Reference.thread(1L), Reference.frame(1L),
                                new ListValue<>(OBJECT, IntStream.range(0, slotCount)
                                        .mapToObj(i -> slotInfoCreator2.apply(i + 1))
                                        .collect(Collectors.toList()))),
                        new GetValuesReply(2, new ListValue<>(Type.LIST, List.of(wrap(1), wrap(2)))))));
    }

    /**
     * a version of the {@link SynthesizerTest#testMapCallSynthesisForGetValuesRequest()} but with a focus on different
     * DependencyGraph configurations
     */
    @Test
    public void testGetValuesRequestFindCorrectMappingWithoutTransformers() {
        var partition = getGetValuesRequestPartition(1);
        var graph = DependencyGraph.compute(partition, DEFAULT_OPTIONS.withUseTransformers(false));
        var program = Synthesizer.synthesizeProgram(graph, MINIMAL_OPTIONS);
        System.out.println(program.toPrettyString());
        assertEquals(Program.parse("(\n" +
                        "(= var0 (request Method VariableTable ('methodID')=(wrap 'method' 32505856) " +
                        "  ('refType')=(wrap 'klass' 10)))\n" +
                        "(= var1 (request StackFrame GetValues ('frame')=(wrap 'frame' 1) " +
                        "  ('thread')=(wrap 'thread' 1) " +
                        "  ('slots' 0 'sigbyte')=(wrap 'byte' 73) " +
                        "  ('slots' 0 'slot')=(get var0 'slots' 0 'slot'))))").toPrettyString(),
                program.toPrettyString());
    }

    @Test
    public void testGetValuesRequestFindCorrectMappingWithTransformers() {
        var partition = getGetValuesRequestPartition(1);
        var graph = DependencyGraph.compute(partition, DEFAULT_OPTIONS.withUseTransformers(true));
        var program = Synthesizer.synthesizeProgram(graph, MINIMAL_OPTIONS);
        System.out.println(program.toPrettyString());
        assertEquals(Program.parse("(\n" +
                        "(= var0 (request Method VariableTable ('methodID')=(wrap 'method' 32505856) " +
                        "  ('refType')=(wrap 'klass' 10)))\n" +
                        "(= var1 (request StackFrame GetValues ('frame')=(wrap 'frame' 1) " +
                        "  ('thread')=(wrap 'thread' 1) " +
                        "  ('slots' 0 'sigbyte')=(getTagForSignature (get var0 'slots' 0 'signature'))" +
                        "  ('slots' 0 'slot')=(get var0 'slots' 0 'slot'))))").toPrettyString(),
                program.toPrettyString());
    }

    @Test
    public void testSortWithDifferentOrderInPartition() {
        var pair1 =
                p(new jdwp.ClassTypeCmds.SuperclassRequest(5, new ClassTypeReference(3L)),
                        new jdwp.ClassTypeCmds.SuperclassReply(5, new ClassTypeReference(4L)));
        var pair2 =
                p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(2, new ArrayReference(1L)),
                        new jdwp.ObjectReferenceCmds.ReferenceTypeReply(2, PrimitiveValue.wrap((byte) 91),
                                new ClassReference(3L)));
        var partition1 = new Partition(null, List.of(pair1, pair2));
        var partition2 = new Partition(null, List.of(pair2, pair1));
        var dep1 = DependencyGraph.compute(partition1);
        var layers1 = dep1.computeLayers();
        assertEquals(2, layers1.size());
        var nodes1 = new ArrayList<>(layers1.getAllNodes());
        nodes1.sort(layers1.getNodeComparator());
        var dep2 = DependencyGraph.compute(partition2);
        var layers2 = dep2.computeLayers();
        assertEquals(2, layers1.size());
        var nodes2 = new ArrayList<>(layers2.getAllNodes());
        nodes2.sort(layers2.getNodeComparator());
        assertEquals(nodes1.toString(), nodes2.toString());
    }

    @Test
    public void testLoopLikePartitionWithDuplicates() {
        var partition = new Partition(Either.right(new jdwp.EventCmds.Events(5, PrimitiveValue.wrap((byte) 2),
                new ListValue<>(Type.LIST, List.of(new EventCmds.Events.Breakpoint(PrimitiveValue.wrap(41),
                        new ThreadReference(1L), new Location(new ClassTypeReference(1070L),
                        new MethodReference(105553126474952L), PrimitiveValue.wrap((long) 5))))))), List.of(
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(353, new ThreadReference(1L)),
                        new jdwp.ThreadReferenceCmds.FrameCountReply(353, PrimitiveValue.wrap(1))),
                p(new jdwp.StackFrameCmds.GetValuesRequest(367, new ThreadReference(1L), new FrameReference(131072L),
                                new ListValue<>(Type.LIST,
                                        List.of())),
                        new jdwp.StackFrameCmds.GetValuesReply(367, new ListValue<>(Type.LIST,
                                List.of(new ArrayReference(1072L), new ObjectReference(1073L),
                                        PrimitiveValue.wrap(0))))),
                p(new jdwp.ObjectReferenceCmds.ReferenceTypeRequest(369, new ObjectReference(1073L)),
                        new jdwp.ObjectReferenceCmds.ReferenceTypeReply(369, PrimitiveValue.wrap((byte) 1),
                                new ClassReference(1052L))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(370, new ArrayReference(1072L)),
                        new jdwp.ArrayReferenceCmds.LengthReply(370, PrimitiveValue.wrap(0))),
                p(new jdwp.ArrayReferenceCmds.LengthRequest(372, new ArrayReference(1072L)),
                        new jdwp.ArrayReferenceCmds.LengthReply(372, PrimitiveValue.wrap(0)))));
        var graph = DependencyGraph.compute(partition, DEFAULT_OPTIONS);
        assertEquals(5, graph.getAllNodes().size());
    }

    @Test
    public void testPartitionWithReplyLikeErrors() {
        var partition = new Partition(Either.right(new jdwp.EventCmds.Events(5, PrimitiveValue.wrap((byte) 2),
                new ListValue<>(Type.LIST, List.of(new EventCmds.Events.Breakpoint(PrimitiveValue.wrap(41),
                        new ThreadReference(1L), new Location(new ClassTypeReference(1070L),
                        new MethodReference(105553126474952L), PrimitiveValue.wrap((long) 5))))))), List.of(
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(353, new ThreadReference(1L)),
                        new jdwp.ThreadReferenceCmds.FrameCountReply(353, PrimitiveValue.wrap(1))),
                p(new SourceDebugExtensionRequest(354, new ClassReference(1070L)),
                        new ReplyOrError<>(354, SourceDebugExtensionRequest.METADATA, (short)Error.ABSENT_INFORMATION)),
                p(new jdwp.StackFrameCmds.GetValuesRequest(367, new ThreadReference(1L), new FrameReference(131072L),
                                new ListValue<>(Type.LIST,
                                        List.of())),
                        new jdwp.StackFrameCmds.GetValuesReply(367, new ListValue<>(Type.LIST,
                                List.of(new ArrayReference(1072L), new ObjectReference(1073L),
                                        PrimitiveValue.wrap(0)))))));
        var graph = DependencyGraph.compute(partition, DEFAULT_OPTIONS);
        assertEquals(4, graph.getAllNodes().size());
    }

    @Test
    public void testShortArrayLength() {
        var partition = new Partition(null, List.of(
                p(new jdwp.ArrayReferenceCmds.LengthRequest(2260662, new ArrayReference(1104L)),
                        new ReplyOrError<>(2260662, new jdwp.ArrayReferenceCmds.LengthReply(2260662,
                                PrimitiveValue.wrap(9)))),
                p(new jdwp.ArrayReferenceCmds.GetValuesRequest(2260663, new ArrayReference(1104L),
                                PrimitiveValue.wrap(0), PrimitiveValue.wrap(9)),
                        new ReplyOrError<>(2260663, new jdwp.ArrayReferenceCmds.GetValuesReply(2260663,
                                new BasicListValue<>(Type.LIST, List.of(PrimitiveValue.wrap((byte) 40),
                                        PrimitiveValue.wrap((byte) 40), PrimitiveValue.wrap((byte) 61),
                                        PrimitiveValue.wrap((byte) 32), PrimitiveValue.wrap((byte) 105),
                                        PrimitiveValue.wrap((byte) 32), PrimitiveValue.wrap((byte) 48),
                                        PrimitiveValue.wrap((byte) 41), PrimitiveValue.wrap((byte) 41))))))));
        var graph = DependencyGraph.compute(partition, DEFAULT_OPTIONS);
        var layers = graph.computeLayers();
        assertEquals(2, layers.size());
    }

    @Test
    public void testFramesUseFrameCount() {
        var partition = new Partition(null, List.of(
                p(new jdwp.ThreadReferenceCmds.FrameCountRequest(2276482, thread(1L)), new ReplyOrError<>(2276482,
                        new jdwp.ThreadReferenceCmds.FrameCountReply(2276482, wrap(5)))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(2276485, thread(1L), wrap(0), wrap(1)),
                        new ReplyOrError<>(2276485, new jdwp.ThreadReferenceCmds.FramesReply(2276485,
                                new ListValue<>(Type.LIST, List.of())))),
                p(new jdwp.ThreadReferenceCmds.FramesRequest(2276494, thread(1L), wrap(0), wrap(5)),
                        new ReplyOrError<>(2276494, new jdwp.ThreadReferenceCmds.FramesReply(2276494,
                                new ListValue<>(Type.LIST, List.of()))))));
        var graph = DependencyGraph.compute(partition, DEFAULT_OPTIONS);
        var layers = graph.computeLayers();
        assertEquals(2, layers.size());
    }

    @Test
    public void testCauseOriginNodeNotRemovedInDeduplication() {
        var partition = new Partition(Either.left(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(92461,
                klass(439L))), List.of(
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(92374, klass(439L)), new ReplyOrError<>(92374,
                        new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(92374, new ListValue<>(Type.LIST,
                                List.of())))),
                p(new jdwp.ReferenceTypeCmds.MethodsWithGenericRequest(92461, klass(439L)), new ReplyOrError<>(92461,
                        new jdwp.ReferenceTypeCmds.MethodsWithGenericReply(92461, new ListValue<>(Type.LIST,
                                List.of())))),
                p(new jdwp.ReferenceTypeCmds.SourceDebugExtensionRequest(92633, klass(439L)),
                        new ReplyOrError<>(92633, ReferenceTypeCmds.SourceDebugExtensionRequest.METADATA,
                                JDWP.Error.ABSENT_INFORMATION))
        ));
        var graph = DependencyGraph.compute(partition, DEFAULT_OPTIONS);
        assertTrue(graph.getAllNodes().stream().anyMatch(n -> n.getId() == 92461));
        var layers = graph.computeLayers();
        var nodes = layers.getAllNodesWithoutDuplicates();
        assertTrue(nodes.stream().anyMatch(n -> n.getId() == 92461));
    }

    @Test
    public void testSplitGraphOnEventsCause() {
        var graph =
                DependencyGraph.compute(new Partition(Either.right(new jdwp.EventCmds.Events(0, wrap((byte) 2),
                        new ListValue<>(Type.OBJECT,
                        List.of(new EventCmds.Events.VMStart(wrap(0), thread(1L)),
                                new EventCmds.Events.VMStart(wrap(0), thread(2L)))
                ))), List.of(
                        p(new FrameCountRequest(4, thread(1L)), new FrameCountReply(4, wrap(1))),
                        p(new FrameCountRequest(5, thread(2L)), new FrameCountReply(5, wrap(1))),
                        p(new jdwp.VirtualMachineCmds.IDSizesRequest(1582), new ReplyOrError<>(1582,
                                new jdwp.VirtualMachineCmds.IDSizesReply(1582, wrap(8), wrap(8), wrap(8), wrap(8),
                                        wrap(8)))))));
        var graphs = graph.splitOnCause();
        assertEquals(2, graphs.size());
        assertEquals(3, graphs.get(0).getAllNodes().size());
        assertEquals(3, graphs.get(1).getAllNodes().size());
        assertEquals("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")" +
                "=(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 " +
                "\"thread\")=(wrap \"thread\" 1)))\n" +
                "  (= var0 (request VirtualMachine IDSizes))\n" +
                "  (= var1 (request ThreadReference FrameCount (\"thread\")=(get cause \"events\" 0 \"thread\"))))",
                Synthesizer.synthesizeProgram(graphs.get(0)).toPrettyString());
        assertEquals("((= cause (events Event Composite (\"suspendPolicy\")=(wrap \"byte\" 2) (\"events\" 0 \"kind\")" +
                "=(wrap \"string\" \"VMStart\") (\"events\" 0 \"requestID\")=(wrap \"int\" 0) (\"events\" 0 " +
                "\"thread\")=(wrap \"thread\" 2)))\n" +
                "  (= var0 (request VirtualMachine IDSizes))\n" +
                "  (= var1 (request ThreadReference FrameCount (\"thread\")=(get cause \"events\" 0 \"thread\"))))",
                Synthesizer.synthesizeProgram(graphs.get(1)).toPrettyString());
    }

    @Test
    public void testSplitGraphOnRequestCause() {
        var graph =
                DependencyGraph.compute(new Partition(Either.left(new SetRequest(0, wrap((byte) 10), wrap((byte) 10),
                        new ListValue<>(new ClassMatch(wrap("x")), new ClassMatch(wrap("y"))))), List.of(
                        p(new SetRequest(0, wrap((byte) 10), wrap((byte) 10),
                                        new ListValue<>(new ClassMatch(wrap("x")), new ClassMatch(wrap("y")))),
                                new SetReply(0, wrap(10))),
                        p(new FrameCountRequest(4, thread(1L)), new FrameCountReply(4, wrap(1))),
                        p(new FrameCountRequest(5, thread(2L)), new FrameCountReply(5, wrap(1))),
                        p(new jdwp.VirtualMachineCmds.IDSizesRequest(1582),
                                new ReplyOrError<>(1582, new jdwp.VirtualMachineCmds.IDSizesReply(1582, wrap(8),
                                        wrap(8), wrap(8), wrap(8), wrap(8)))))));
        var graphs = graph.splitOnCause();
        assertEquals(2, graphs.size());
        assertEquals(5, graphs.get(0).getAllNodes().size());
        assertEquals(5, graphs.get(1).getAllNodes().size());
        assertEquals("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 10) (\"suspendPolicy\")=" +
                "(wrap \"byte\" 10) (\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"x\") (\"modifiers\" 0 " +
                "\"kind\")=(wrap \"string\" \"ClassMatch\")))\n" +
                "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 10) (\"suspendPolicy\")=(wrap " +
                "\"byte\" 10) (\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"x\") (\"modifiers\" 0 \"kind\")=" +
                "(wrap \"string\" \"ClassMatch\")))\n" +
                "  (= var1 (request VirtualMachine IDSizes))\n" +
                "  (= var3 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 2)))\n" +
                "  (= var2 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1))))",
                Synthesizer.synthesizeProgram(graphs.get(0)).toPrettyString());
        assertEquals("((= cause (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 10) (\"suspendPolicy\")=" +
                "(wrap \"byte\" 10) (\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"y\") (\"modifiers\" 0 " +
                "\"kind\")=(wrap \"string\" \"ClassMatch\")))\n" +
                "  (= var0 (request EventRequest Set (\"eventKind\")=(wrap \"byte\" 10) (\"suspendPolicy\")=(wrap " +
                "\"byte\" 10) (\"modifiers\" 0 \"classPattern\")=(wrap \"string\" \"y\") (\"modifiers\" 0 \"kind\")=" +
                "(wrap \"string\" \"ClassMatch\")))\n" +
                "  (= var1 (request VirtualMachine IDSizes))\n" +
                "  (= var3 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 2)))\n" +
                "  (= var2 (request ThreadReference FrameCount (\"thread\")=(wrap \"thread\" 1))))",
                Synthesizer.synthesizeProgram(graphs.get(1)).toPrettyString());
    }


    static TestRequest request(int id, Value value) {
        return new TestRequest(id, p("value", value));
    }

    static TestReply reply(int id, Value value) {
        return new TestReply(id, p("value", value));
    }

    static Pair<TestRequest, TestReply> rrpair(int id, int request, int reply) {
        return p(request(id, wrap(request)), reply(id, wrap(reply)));
    }

    static Pair<TestRequest, TestReply> rrvpair(int id, BasicValue request, BasicValue reply) {
        return p(request(id, request), reply(id, reply));
    }

    static Pair<TestRequest, TestReply> rrpair(int id, List<Pair<String, Integer>> request, List<Pair<String, Integer>> reply) {
        return p(new TestRequest(id, request.stream().map(p -> p(p.first, wrap(p.second()))).toArray(Pair[]::new)),
                new TestReply(id, reply.stream().map(p -> p(p.first, wrap(p.second()))).toArray(Pair[]::new)));
    }

    static Pair<TestRequest, TestReply> rrvpair(int id, List<Pair<String, BasicValue>> request, List<Pair<String, BasicValue>> reply) {
        return p(new TestRequest(id, request.stream().map(p -> p(p.first, p.second())).toArray(Pair[]::new)),
                new TestReply(id, reply.stream().map(p -> p(p.first, p.second())).toArray(Pair[]::new)));
    }
}
