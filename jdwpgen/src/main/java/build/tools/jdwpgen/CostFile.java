package build.tools.jdwpgen;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static build.tools.jdwpgen.CostFile.Info.DEFAULT;

/**
 * Reads and writes the cost file
 */
public class CostFile {

    static class Info {
        final int commandSet;
        final int command;
        final double cost;
        final double minCost;
        final double maxCost;
        /** count / all count */
        final double occurrence;

        private Info(int commandSet, int command, double cost, double minCost, double maxCost, double occurrence) {
            this.commandSet = commandSet;
            this.command = command;
            this.cost = cost;
            this.minCost = minCost;
            this.maxCost = maxCost;
            this.occurrence = occurrence;
        }

        static List<String> header() {
            return List.of("Command Set", "Command", "Cost", "Min Cost", "Max Cost", "Occurrence");
        }

        List<String> toStringList() {
            return List.of(
                    String.valueOf(commandSet),
                    String.valueOf(command),
                    String.format(Locale.US, "%.3f", cost),
                    String.format(Locale.US, "%.3f", minCost),
                    String.format(Locale.US, "%.3f",maxCost),
                    String.format(Locale.US, "%.6f", occurrence));
        }

        static Info DEFAULT = new Info(0, 0, 0, 0, 0, 0);
    }

    /** cmd set -> cmd -> (time in millis, number of executions) */
    private final Map<Integer, Map<Integer, Info>> infoPerCommand;

    private CostFile(Map<Integer, Map<Integer, Info>> infoPerCommand) {
        this.infoPerCommand = infoPerCommand;
    }

    public static CostFile loadJVMFile(Path path) {
        var costsInMillis = new HashMap<Integer, Map<Integer, List<Float>>>();
        var allCount = new int[] { 0 };
        try {
            Files.lines(path).forEach(l -> {
                var parts = l.split(", *");
                if (parts.length != 3) {
                    return;
                }
                var commandSet = Integer.parseInt(parts[0]);
                var command = Integer.parseInt(parts[1]);
                var cost = Float.parseFloat(parts[2]);
                costsInMillis.computeIfAbsent(commandSet, k -> new HashMap<>())
                        .computeIfAbsent(command, k -> new ArrayList<>()).add(cost);
                allCount[0]++;
            });
            return new CostFile(costsInMillis.entrySet().stream().collect(
                    Collectors.toMap((Function<Entry<Integer, Map<Integer, List<Float>>>, Integer>) Entry::getKey, e -> {
                var commandSet = e.getKey();
                var commandToCost = e.getValue();
                return commandToCost.entrySet().stream().collect(
                        Collectors.toMap(Map.Entry::getKey, e2 -> {
                            var count = e2.getValue().size();
                            var skip = (int) Math.floor(count * 0.1); // skip the lowest and largest 10%
                            e2.getValue().sort(Float::compare);
                            var cost = e2.getValue().stream().skip(skip).limit(count - 2L * skip)
                                    .mapToDouble(f -> f).average().orElse(0);
                            return new Info(commandSet, e2.getKey(), cost,
                                    e2.getValue().isEmpty() ? 0 : e2.getValue().get(0),
                                    e2.getValue().isEmpty() ? 0 : e2.getValue().get(e2.getValue().size() - 1),
                                    count / (allCount[0] * 1.0));
                        }));
            })));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static CostFile loadFile(Path path) {
        var infos = new HashMap<Integer, Map<Integer, Info>>();
        try {
            Files.lines(path).filter(l -> !l.startsWith("C")).forEach(l -> {
                var parts = l.split(", *");
                if (parts.length != 5) {
                    throw new AssertionError("Expected 5 comma separated values, got " + parts);
                }
                var commandSet = Integer.parseInt(parts[0]);
                var command = Integer.parseInt(parts[1]);
                var cost = Float.parseFloat(parts[2]);
                var minCost = Float.parseFloat(parts[3]);
                var maxCost = Float.parseFloat(parts[4]);
                var occurrence = Float.parseFloat(parts[4]);
                infos.computeIfAbsent(commandSet, HashMap::new)
                        .put(command, new Info(commandSet, command, cost, minCost, maxCost, occurrence));
            });
            return new CostFile(infos);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static CostFile empty() {
        return new CostFile(new HashMap<>());
    }

    public void store(Path path) {
        try {
            Files.writeString(path, Stream.concat(Stream.of(String.join(", ", Info.header())), infoPerCommand.entrySet().stream()
                    .sorted(Entry.comparingByKey()).map(e -> {
                var commandSet = e.getKey();
                var commandToCost = e.getValue();
                return commandToCost.entrySet().stream().sorted(Entry.comparingByKey()).map(e2 -> {
                    var command = e2.getKey();
                    var info = e2.getValue();
                    return String.join(", ", info.toStringList());
                }).collect(Collectors.joining("\n"));
            })).collect(Collectors.joining("\n")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public float getCost(int commandSet, int command) {
        return (float)infoPerCommand.getOrDefault(commandSet, Map.of()).getOrDefault(command, DEFAULT).cost;
    }
}
