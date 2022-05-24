package tunnel.cli;

import ch.qos.logback.classic.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import tunnel.BasicTunnel;
import tunnel.Listener.LoggingListener;
import tunnel.Listener.LoggingListener.Mode;
import tunnel.synth.Partitioner;
import tunnel.synth.ProgramCollection;
import tunnel.synth.Synthesizer;

/**
 * This is the most basic endpoint that just logs a packets that go through it and
 * tries to parse them.
 */
@SuppressWarnings({"FieldMayBeFinal", "CanBeFinal"})
@Command(name = "logger", mixinStandardHelpOptions = true,
        description = "Log all packets that go through this tunnel.")
public class PacketLogger implements Runnable {

    private final static Logger LOG = Main.LOG;

    @ParentCommand
    private Main mainConfig;

    @Option(names = "--mode", description = "Output mode")
    private Mode mode = Mode.STRING;

    @SuppressWarnings("FieldCanBeLocal")
    @Option(names = "--max-length", description = "Max length of a line, -1 for no length limit")
    private int maxLineLength = 200;

    @Option(names = "--partitions", description = "Print the found partitions")
    private boolean logPartitions = false;
    @Option(names = "--programs", description = "Print the synthesized programs")
    private boolean logPrograms = false;

    @Option(names = "--overlaps", description = "Print overlapping previous programs")
    private boolean logOverlap = false;

    @Option(names = "--overlap-factor", description = "Factor to which two programs have to overlap " +
            "to be considered overlapping and logged")
    private double overlapFactor = 0.7;

    public static PacketLogger create(Main mainConfig) {
        var pl = new PacketLogger();
        pl.mainConfig = mainConfig;
        return pl;
    }

    @Override
    public void run() {
        mainConfig.setDefaultLogLevel();
        LOG.info("Starting tunnel from {} to {}", mainConfig.getJvmAddress(), mainConfig.getOwnAddress());
        var tunnel = new BasicTunnel(mainConfig.getOwnAddress(), mainConfig.getJvmAddress());
        if (logPartitions || logPrograms || logOverlap) {
            var partitioner = new Partitioner()
                    .addListener(p -> {
                        System.out.println();
                        if (logPartitions) {
                            System.out.println("Partition:");
                            System.out.println(mode == Mode.STRING ? p.toString() : p.toCode());
                            System.out.println();
                            System.out.println();
                        }
                    });
            if (logPrograms || logOverlap) {
                int[] overlapCount = new int[]{0, 0, 0}; // (all programs, all programs > 1 assignment, overlapping programs)
                ProgramCollection programCollection = new ProgramCollection(overlapFactor);
                Synthesizer synth = new Synthesizer().addListener(p -> {
                    if (logPrograms) {
                        System.out.println("Program:");
                        System.out.println(p.toPrettyString());
                        System.out.println();
                        System.out.println();
                    }
                    overlapCount[0]++;
                    if (p.getNumberOfAssignments() > 1) {
                        overlapCount[1]++;
                    }
                    if (logOverlap) {
                        programCollection.accept(p);
                    }
                });
                programCollection.addListener(o -> {
                    overlapCount[2]++;
                    System.out.println("Overlap:");
                    System.out.println("----- first ----");
                    System.out.println(o.getFirst().toPrettyString());
                    System.out.println("----- second ----");
                    System.out.println(o.getSecond().toPrettyString());
                    System.out.printf("----- overlap: %.2f ----%n", o.getOverlap());
                    System.out.printf("----- #programs = %5d  #(> 1 stmt)programs = %5d  #overlaps = %5d (%2.2f%%) %n",
                            overlapCount[0], overlapCount[1], overlapCount[2], overlapCount[2] / (overlapCount[1] / 100.0));
                });
                partitioner.addListener(synth);
            }
            tunnel.addListener(partitioner);
        }
        tunnel.addListener(new LoggingListener(mode, maxLineLength)).run();
    }

}
