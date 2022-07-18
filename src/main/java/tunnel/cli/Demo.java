package tunnel.cli;

import lombok.Getter;
import lombok.SneakyThrows;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import tunnel.agent.TunnelStartingAgent;
import tunnel.cli.Main.InetSocketAddressConverter;
import tunnel.util.Util;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

@Command(name = "demo", mixinStandardHelpOptions = true,
        description = "Start a JVM process and tunnel threads")
public class Demo implements Runnable {

    @ParentCommand
    private Main mainConfig;

    @Option(names = "--run", required = true, description = "Arguments for the JVM to start a new process")
    private String run;

    @Option(names = {"--own", "--address"}, required = true, description = "open port",
            converter = InetSocketAddressConverter.class)
    @Getter
    private InetSocketAddress ownAddress;

    @Option(names = "--tunnel", required = true, arity = "1..*",
            description = "Arguments for a tunnel, must not include --own and --jvm")
    private List<String> tunnel;

    @SneakyThrows
    @Override
    public void run() {
        var jdwpAddress = Util.findInetSocketAddress();
        var curAddress = jdwpAddress;
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < tunnel.size(); i++) {
            var tunnelArg = tunnel.get(i);
            var endAddress = i == tunnel.size() - 1 ? ownAddress : Util.findInetSocketAddress();
            threads.add(startTunnel(curAddress, endAddress, tunnelArg));
            curAddress = endAddress;
        }
        startJVMProcess(jdwpAddress);
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private Thread startTunnel(InetSocketAddress jdwpAddress, InetSocketAddress ownAddress, String tunnelArg) {
        List<String> tunnelArgs =  new ArrayList<>(List.of(tunnelArg.split(" ")));
        if (tunnelArgs.contains("--jvm") || tunnelArgs.contains("--own") || tunnelArgs.contains("--address")) {
            throw new IllegalArgumentException("Tunnel arguments must not contain --jvm, --own or --address");
        }
        tunnelArgs.addAll(0, List.of("--verbose", mainConfig.getLogLevel().toString(),
                "--jvm", jdwpAddress.getPort() + "", "--own", ownAddress.getPort() + ""));
        var tunnelOptArg = tunnelArgs.stream().filter(t -> t.startsWith("--tunnel"))
                .map(t -> t.substring("--tunnel".length())).findFirst().orElse("none");
        var thread = new Thread(() -> Main.main(tunnelArgs.toArray(String[]::new)));
        thread.setName("tunnel-" + tunnelOptArg);
        thread.start();
        return thread;
    }

    @SneakyThrows
    private void startJVMProcess(InetSocketAddress jdwpAddress) {
        var jvmPath = TunnelStartingAgent.getJVMPath();
        List<String> processParts = new ArrayList<>(List.of(jvmPath.toString(),
                "-agentlib:jdwp=transport=dt_socket,server=y,address=" + jdwpAddress.getPort()));
        processParts.addAll(List.of(run.split(" ")));
        System.out.println("Start " + String.join(" ", processParts));
        var process = new ProcessBuilder(processParts).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
        new Thread(() -> {
            try {
                process.waitFor();
                System.err.println("JVM process exited");
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
