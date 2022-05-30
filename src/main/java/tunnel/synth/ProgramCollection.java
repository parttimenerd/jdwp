package tunnel.synth;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import tunnel.synth.ProgramCollection.Overlap;
import tunnel.synth.program.AST.AssignmentStatement;
import tunnel.synth.program.Program;

import java.util.*;
import java.util.function.Consumer;

/** a collection of debugging programs */
public class ProgramCollection extends Analyser<ProgramCollection, Overlap> implements Consumer<Program> {

    @Getter
    @AllArgsConstructor
    @ToString
    public static class Overlap {
        private final Program first;
        private final Program second;
        private final Program overlap;
        private final double overlapFactor;
    }

    private final Map<AssignmentStatement, List<Program>> causeToPrograms = new HashMap<>();
    /** (#statements in overlap) / max(#statements in first, #... in second) >= minOverlap to be considered equal,
     * only programs with same cause are considered and if multiple programs are applicable, then
     * the one with the highest overlap is used */
    private final double minOverlap;
    /** minimal number assignment in a program to be added */
    private final int minSize = 2;

    public ProgramCollection(double minOverlap) {
        this.minOverlap = minOverlap;
    }

    @Override
    public void accept(Program program) {
        if (program.getNumberOfAssignments() >= minSize) {
            add(program);
        }
    }

    public void add(Program program) {
        var overlap = getOverlappingProgram(program);
        overlap.ifPresent(this::submit);
        causeToPrograms.computeIfAbsent(program.getFirstCallAssignment(), a -> new ArrayList<>()).add(program);
    }

    public Optional<Overlap> getOverlappingProgram(Program program) {
        if (causeToPrograms.containsKey(program.getFirstCallAssignment())) {
            var others = causeToPrograms.get(program.getFirstCallAssignment());
            return others.stream().map(p -> calculateOverlap(p, program)).filter(o -> o.getOverlapFactor() >= minOverlap)
                    .max(Comparator.comparingDouble(Overlap::getOverlapFactor));
        }
        return Optional.empty();
    }

    public static Overlap calculateOverlap(Program first, Program second) {
        var maxStatements = Math.max(first.getNumberOfAssignments(), second.getNumberOfAssignments());
        if (maxStatements == 0) {
            return new Overlap(first, second, new Program(), Double.POSITIVE_INFINITY);
        }
        var overlap = first.overlap(second);
        var overlapFactor = overlap.getNumberOfAssignments() / maxStatements;
        return new Overlap(first, second, overlap, overlapFactor);
    }
}
