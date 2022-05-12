package tunnel.cli;

import ch.qos.logback.classic.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import tunnel.BasicTunnel;
import tunnel.Listener.LoggingListener;
import tunnel.Listener.LoggingListener.Mode;
import tunnel.Partitioner;

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
        if (logPartitions) {
            tunnel.addListener(new Partitioner()
                    .addListener(p -> {
                        System.out.println();
                        System.out.println(mode == Mode.STRING ? p.toString() : p.toCode());
                        System.out.println();
                    }));
        }
        tunnel.addListener(new LoggingListener(mode, maxLineLength)).run();
    }

}
