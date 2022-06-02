package tunnel.cli;

import ch.qos.logback.classic.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import tunnel.BasicTunnel;
import tunnel.Listener.LoggingListener;
import tunnel.Listener.LoggingListener.Mode;
import tunnel.State;
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

    @Option(names = "--packets", description = "Print packets, might be confusing with info and debug log level")
    private boolean logPackets = false;

    @Option(names = "--overlap-factor", description = "Factor to which two programs have to overlap " +
            "to be considered overlapping and logged")
    private double overlapFactor = 0.7;

    @Option(names = "--tunnel", description = "Tunnel mode")
    private State.Mode tunnelMode = State.Mode.NONE;

    @Option(names = "--disable-pc", description = "Disable program cache")
    private boolean disableProgramCache = false;

    @Option(names = "--disable-rc", description = "Disable reply cache")
    private boolean disableReplyCache = false;

    public static PacketLogger create(Main mainConfig) {
        var pl = new PacketLogger();
        pl.mainConfig = mainConfig;
        return pl;
    }

    @Override
    public void run() {
        mainConfig.setDefaultLogLevel();
        LOG.info("Starting tunnel from {} to {}", mainConfig.getJvmAddress(), mainConfig.getOwnAddress());
        var tunnel = new BasicTunnel(mainConfig.getOwnAddress(), mainConfig.getJvmAddress(), tunnelMode);
        if (disableProgramCache) {
            tunnel.getState().disableProgramCache();
        }
        if (disableReplyCache) {
            tunnel.getState().disableReplyCache();
        }
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
                int[] overlapCount = new int[]{0, 0, 0, 0, 0};
                // (all programs, all programs > 1 assignment, overlapping programs, programs statement, overlapping statements)
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
                    overlapCount[3] += p.getNumberOfAssignments();
                    if (logOverlap) {
                        programCollection.accept(p);
                    }
                });
                programCollection.addListener(o -> {
                    overlapCount[2]++;
                    overlapCount[4] += o.getOverlap().getNumberOfAssignments();
                    System.out.println("Overlap:");
                    System.out.println("----- first ----");
                    System.out.println(o.getFirst().toPrettyString());
                    System.out.println("----- second ----");
                    System.out.println(o.getSecond().toPrettyString());
                    System.out.printf("----- overlap: %.2f ----%n", o.getOverlapFactor());
                    System.out.println(o.getOverlap().toPrettyString());
                    System.out.printf("----- #programs = %5d  #(> 1 stmt)programs = %5d  #overlaps = %5d (%2.2f%%) #assignments = %7d  #overlapping = %7d (%2.2f%%) %n",
                            overlapCount[0], overlapCount[1], overlapCount[2], overlapCount[2] / (overlapCount[1] / 100.0),
                            overlapCount[3], overlapCount[4], overlapCount[4] / (overlapCount[3] / 100.0));
                });
                partitioner.addListener(synth);
            }
            tunnel.addListener(partitioner);
        }
        if (logPackets) {
            tunnel.addListener(new LoggingListener(mode, maxLineLength));
        }
        tunnel.run();
    }

}
