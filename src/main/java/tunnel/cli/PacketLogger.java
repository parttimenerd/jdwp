package tunnel.cli;

import ch.qos.logback.classic.Logger;
import picocli.CommandLine.Command;
import picocli.CommandLine.ParentCommand;
import tunnel.BasicTunnel;
import tunnel.Listener.LoggingListener;

/**
 * This is the most basic endpoint that just logs a packets that go through it and
 * tries to parse them.
 */
@Command(name = "logger", mixinStandardHelpOptions = true,
        description = "Log all packets that go through this tunnel.")
public class PacketLogger implements Runnable {

    private final static Logger LOG = Main.LOG;

    @ParentCommand
    private Main mainConfig;

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
        tunnel.addListener(new LoggingListener()).run();
    }

}
