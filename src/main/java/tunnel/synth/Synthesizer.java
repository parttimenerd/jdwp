package tunnel.synth;

import jdwp.AccessPath;
import jdwp.util.Pair;
import lombok.Getter;
import tunnel.synth.DependencyGraph.DoublyTaggedBasicValue;
import tunnel.synth.DependencyGraph.Edge;
import tunnel.synth.DependencyGraph.Layers;
import tunnel.synth.DependencyGraph.Node;
import tunnel.synth.program.Functions;
import tunnel.synth.program.Program;

import java.util.*;
import java.util.stream.Collectors;

import static jdwp.util.Pair.p;
import static tunnel.synth.program.AST.*;

/**
 * Transforms a dependency graph into a program.
 */
public class Synthesizer {

    public static Program synthesizeProgram(DependencyGraph graph) {
        // compute the layers: loop iteration headers have to reside in the layer directly
        // below the loop source
        Layers layers = graph.computeLayers();
        return processNodes(new NodeNames(), layers, graph.getAllNodes(), 2).first;
    }

    @Getter
    static class NodeNames {
        public final String CAUSE_NAME = "cause";
        public final String NAME_PREFIX = "var";
        public final String ITER_NAME_PREFIX = "iter";
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
                    .forEach(t -> usedPaths.put(t.getPath(), Functions.createWrapperFunctionCall(t.getValue())));
            return new RequestCall(request.getCommandSetName(), request.getCommandName(), usedPaths.keySet().stream().sorted().map(p -> new RequestCallProperty(p, usedPaths.get(p))).collect(Collectors.toList()));
        }

        public AssignmentStatement createRequestCallStatement(Node node) {
            var call = createRequestCall(node);
            return new AssignmentStatement(ident(get(node)), call);
        }
    }

    private static Pair<Program, Set<Node>> processNodes(NodeNames variables, Layers layers, Set<Node> fullBody, int minSize) {
        var fullBodySorted = new ArrayList<>(fullBody);
        fullBodySorted.sort(layers.getNodeComparator());
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
        }
        return p(new Program(statements), fullBody);
    }
}
