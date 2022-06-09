package build.tools.jdwpgen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;

/**
 * Reads and writes the cost file
 */
public class CostFile {

    private final Map<Integer, Map<Integer, Float>> costsInMillis;

    private CostFile(Map<Integer, Map<Integer, Float>> costsInMillis) {
        this.costsInMillis = costsInMillis;
    }

    public static CostFile load(Path path) {
        var costsInMillis = new HashMap<Integer, Map<Integer, List<Float>>>();
        try {
            Files.lines(path).forEach(l -> {
                var parts = l.split(", *");
                assert parts.length == 3;
                var commandSet = Integer.parseInt(parts[0]);
                var command = Integer.parseInt(parts[1]);
                var cost = Float.parseFloat(parts[2]);
                costsInMillis.computeIfAbsent(commandSet, k -> new HashMap<>())
                        .computeIfAbsent(command, k -> new ArrayList<>()).add(cost);
            });
            return new CostFile(costsInMillis.entrySet().stream().collect((Collector<? super Entry<Integer,
                    Map<Integer, List<Float>>>, Object, Map<Integer, Map<Integer, Float>>>) Collectors.toMap((Function<Entry<Integer, Map<Integer, List<Float>>>, Integer>) e -> e.getKey(), e -> {
                var commandSet = e.getKey();
                var commandToCost = e.getValue();
                return commandToCost.entrySet().stream().collect(
                        Collectors.toMap((Function<Entry<Integer, List<Float>>, Integer>) Entry::getKey, e2 -> {
                            var count = e2.getValue().size();
                            var skip = (int) Math.floor(count * 0.1); // skip the lowest and largest 10%
                            var cost = e2.getValue().stream().skip(skip).limit(count - 2 * skip)
                                    .mapToDouble(f -> f).average().orElse(0);
                            return (Float) (float) cost;
                        }));
            })));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static CostFile empty() {
        return new CostFile(new HashMap<>());
    }

    public void store(Path path) {
        try {
            Files.writeString(path, costsInMillis.entrySet().stream().map(e -> {
                var commandSet = e.getKey();
                var commandToCost = e.getValue();
                return commandToCost.entrySet().stream().map(e2 -> {
                    var command = e2.getKey();
                    var cost = e2.getValue();
                    return String.format(Locale.US, "%d, %d, %f", commandSet, command, cost);
                }).collect(Collectors.joining("\n"));
            }).collect(Collectors.joining("\n")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public float getCost(int commandSet, int command) {
        return costsInMillis.getOrDefault(commandSet, Map.of()).getOrDefault(command, 0f);
    }
}
