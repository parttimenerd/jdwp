package tunnel.synth;

import jdwp.Value;
import jdwp.Value.TaggedBasicValue;
import org.junit.jupiter.api.Test;
import tunnel.synth.DependencyGraph.Edge;
import tunnel.synth.DependencyGraph.Node;

import java.util.List;
import java.util.Set;

import static jdwp.PrimitiveValue.wrap;
import static jdwp.util.Pair.p;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DependencyGraphTest {

    /** checks that node and edge are interchangeable regarding equals and hashCode */
    @Test
    public void testNodeSetContainsEdge() {
        var node = new Node(1, p(null, null));
        var otherNode = new Node(2, p(null, null));
        var nodes = Set.of(node);
        assertTrue(nodes.contains(node));
        assertFalse(nodes.contains(otherNode));
        assertTrue(nodes.contains(new Edge(node, List.of())));
        assertFalse(nodes.contains(new Edge(otherNode, List.of())));
        assertTrue(new Edge(node, List.of()).equals(new Edge(node, List.of(new TaggedBasicValue<>(null, wrap(1))))));
    }
}
