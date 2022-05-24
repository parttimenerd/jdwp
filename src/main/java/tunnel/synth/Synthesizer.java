package tunnel.synth;

import jdwp.AccessPath;
import jdwp.EventCmds.Events;
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

import static jdwp.util.Pair.p;
import static tunnel.synth.program.AST.*;

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
        var program = processNodes(names, layers, graph.getAllNodes(), 2).first;
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
                    .forEach(t -> usedPaths.put(t.getPath(), Functions.createWrapperFunctionCall(t.getValue())));
            return new RequestCall(request.getCommandSetName(), request.getCommandName(), usedPaths.keySet().stream().sorted().map(p -> new CallProperty(p, usedPaths.get(p))).collect(Collectors.toList()));
        }

        private PacketCall createPacketCauseCall(Either<Request<?>, Events> call) {
            var request = call.isLeft() ? call.getLeft() : call.getRight();
            Map<AccessPath, FunctionCall> usedPaths = new HashMap<>();
            request.asCombined().getTaggedValues()
                    .filter(t -> !(usedPaths.containsKey(t.getPath())))
                    .forEach(t -> usedPaths.put(t.getPath(), Functions.createWrapperFunctionCall(t.getValue())));
            var args = usedPaths.keySet().stream().sorted().map(p -> new CallProperty(p, usedPaths.get(p))).collect(Collectors.toList());
            return call.isLeft() ? new RequestCall(request.getCommandSetName(), request.getCommandName(), args)
                    : new EventsCall(request.getCommandSetName(), request.getCommandName(), args);
        }

        public AssignmentStatement createRequestCallStatement(Node node) {
            var call = createRequestCall(node);
            return new AssignmentStatement(ident(get(node)), call);
        }
    }

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
        }
        return p(new Program(statements), fullBody);
    }
}
