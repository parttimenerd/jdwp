package tunnel.synth;

import jdwp.AccessPath;
import jdwp.Reference;
import jdwp.Value;
import jdwp.Value.BasicValue;
import jdwp.util.Pair;
import jdwp.util.TestReply;
import jdwp.util.TestRequest;
import org.junit.jupiter.api.Test;
import tunnel.synth.DependencyGraph.Edge;
import tunnel.synth.DependencyGraph.Node;
import tunnel.util.Either;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.*;

public class DependencyGraphTest {

    @Test
    public void testCalculateBasicDiamondGraph() {
        var start = rrpair(1, 1, 2);
        var left = rrpair(2, 2, 3);
        var right = rrpair(3, 2, 4);
        var end = rrpair(4, List.of(p("left", 3), p("right", 4)),
                List.of(p("value", 5)));
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, left, right, end));
        var graph = DependencyGraph.calculate(partition);
        assertNotEquals(graph.getCauseNode(), graph.getNode(start));
        assertNull(graph.getCauseNode().getOrigin());
        assertEquals(-1, graph.getCauseNode().getId());
        assertEquals(Set.of(), graph.getCauseNode().getDependsOnNodes());
        assertEquals(Set.of(graph.getCauseNode()), graph.getNode(start).getDependsOnNodes());
        assertEquals(Set.of(graph.getNode(start)), graph.getNode(left).getDependsOnNodes());
        assertEquals(new Edge(graph.getNode(start),
                        graph.getCauseNode(), List.of(start.first.getContainedValues().getFirstTaggedValue(wrap(1)))),
                graph.getNode(start).getDependsOn().iterator().next());
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
        var graph = DependencyGraph.calculate(partition);
        assertNotEquals(graph.getCauseNode(), graph.getNode(start));
        assertEquals(Set.of(), graph.getCauseNode().getDependsOnNodes());
        assertEquals(Set.of(graph.getCauseNode()), graph.getNode(start).getDependsOnNodes());
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
        var graph = DependencyGraph.calculate(partition);
        assertNotEquals(graph.getCauseNode(), graph.getNode(start));
        assertEquals(Set.of(), graph.getCauseNode().getDependsOnNodes());
        assertEquals(Set.of(), graph.getNode(start).getDependsOnNodes());
        assertEquals(Set.of(graph.getNode(start)), graph.getNode(end).getDependsOnNodes());
    assertEquals(
        Set.of(new AccessPath("left"), new AccessPath("right")),
        graph.getNode(end).getDependsOn().iterator().next().getUsedValues().stream()
            .map(v -> v.path)
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
        var graph = DependencyGraph.calculate(partition);
    assertEquals(
        Set.of(new AccessPath("right"), new AccessPath("left")),
        graph.getNode(end).getDependsOn().iterator().next().getUsedValues().stream()
            .map(v -> v.path)
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
        var graph = DependencyGraph.calculate(partition);
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
        var graph = DependencyGraph.calculate(partition);
        assertEquals(Set.of(graph.getNode(end)), graph.getNode(start).getDependedByNodes());
    }

    @Test
    public void testComputeDependedByTransitive() {
        var start = rrpair(1, 1, 2);
        var end = rrpair(2, 2, 3);
        // start -> end
        var partition = new Partitioner.Partition(Either.left(start.first), List.of(start, end));
        var graph = DependencyGraph.calculate(partition);
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
        var graph = DependencyGraph.calculate(partition);
        var layers = graph.computeLayers();
        var leftNode = graph.getNode(left);
        var endNode = graph.getNode(end);
        assertEquals(Set.of(endNode), leftNode.computeDependedByTransitive());
        assertEquals(Set.of(endNode), DependencyGraph.computeDependedByTransitive(Set.of(leftNode)));
        assertEquals(Set.of(graph.getNode(end)), layers.computeDominatedNodes(Set.of(graph.getNode(left))));
        assertEquals(Set.of(2, 3, 4),
                layers.computeDominatedNodes(Set.of(graph.getNode(start))).stream().map(Node::getId).collect(Collectors.toSet()));
    }

    private static TestRequest request(int id, Value value) {
        return new TestRequest(id, p("value", value));
    }

    private static TestReply reply(int id, Value value) {
        return new TestReply(id, p("value", value));
    }

    private static Pair<TestRequest, TestReply> rrpair(int id, Value request, Value reply) {
        return p(request(id, request), reply(id, reply));
    }

    private static Pair<TestRequest, TestReply> rrpair(int id, int request, int reply) {
        return p(request(id, wrap(request)), reply(id, wrap(reply)));
    }

    private static Pair<TestRequest, TestReply> rrvpair(int id, BasicValue request, BasicValue reply) {
        return p(request(id, request), reply(id, reply));
    }

    private static Pair<TestRequest, TestReply> rrpair(int id, List<Pair<String, Integer>> request, List<Pair<String, Integer>> reply) {
        return p(new TestRequest(id, request.stream().map(p -> p(p.first, wrap(p.second()))).toArray(Pair[]::new)),
                new TestReply(id, reply.stream().map(p -> p(p.first, wrap(p.second()))).toArray(Pair[]::new)));
    }

    private static Pair<TestRequest, TestReply> rrvpair(int id, List<Pair<String, BasicValue>> request, List<Pair<String, BasicValue>> reply) {
        return p(new TestRequest(id, request.stream().map(p -> p(p.first, p.second())).toArray(Pair[]::new)),
                new TestReply(id, reply.stream().map(p -> p(p.first, p.second())).toArray(Pair[]::new)));
    }
}
