package tunnel.agent;

import lombok.AllArgsConstructor;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The sole purpose of this agent is to call a JVM with its own JAR with the passed
 * arguments and run the process as long as the JVM lives.
 * <p>
 * We have to use a different process here because we cannot exclude
 * the java agent threads from being suspended in the debugger.
 * This is only valid for "real" agents written in C/C++.
 * <p>
 * Important: <code>-javaagent:...tunnel.jar</code> has to come before the JDWP agent
 * argument.
 */
public class TunnelStartingAgent {

    private static List<String> getJDWPAgentArguments() {
        String tag = "-agentlib:jdwp";
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        var optArgs = runtimeMxBean.getInputArguments().stream()
                .filter(s -> s.startsWith(tag))
                .findFirst();
        return Arrays.stream(optArgs
                .orElseGet(() -> "")
                .substring(tag.length()).split(",")).collect(Collectors.toList());
    }

    /**
     * returns the address passed to the JDWP agent
     */
    private static Optional<String> getJDWPAddress() {
        return getJDWPAgentArguments().stream().filter(a -> a.startsWith("address=")).findFirst()
                .map(a -> a.substring("address=".length()));
    }

    private static Path getAgentPath() {
        var path = Paths.get(ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(s -> s.matches("-javaagent:.*tunnel\\.jar.*")).findFirst()
                .orElseGet(() -> error(String.format("cannot find -javaagent in %s", ManagementFactory.getRuntimeMXBean().getInputArguments())))
                .substring("-javaagent:".length()).split("=", 2)[0]);
        if (!path.toFile().exists()) {
            error("-javaagent path " + path + " does not exist");
        }
        return path;
    }

    private static boolean isJavaAgentBeforeJDWPAgent() {
        boolean[] hadJavaAgent = {false};
        for (String a : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (a.startsWith("-javaagent:.*tunnel\\.jar.*")) {
                hadJavaAgent[0] = true;
            } else if (a.matches("-agentlib:jdwp")) {
                return !hadJavaAgent[0];
            }
        }
        return false;
    }

    private static Path getJVMPath() {
        Path path = Path.of(System.getProperties().getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Win") ? "java.exe" : "java");
        if (!path.toFile().exists() || !path.toFile().canExecute()) {
            error(String.format("current jvm path does not exist or is not executable (%s)", path));
        }
        return path;
    }

    private static <T> T error(String msg) {
        System.err.println(msg);
        System.exit(1);
        return null;
    }

    @AllArgsConstructor
    private static class Argument {
        private final String name;
        private final String argument;

        static Argument create(String argument) {
            if (argument.matches("[a-zA-z-]+=.*")) {
                var parts = argument.split("=", 2);
                return new Argument(parts[0], parts[1]);
            }
            return new Argument("", argument);
        }

        public List<String> toCLIArgument() {
            if (name.isEmpty()) {
                return List.of(argument);
            }
            return List.of("--" + name, argument);
        }
    }

    public static void premain(String args) {
        var arguments =
                Arrays.stream(args.split(","))
                        .map(Argument::create).collect(Collectors.toCollection(ArrayList::new));
        var jvmArg = arguments.stream().filter(a -> a.name.equals("jvm")).findFirst();
        var agentAddress = getJDWPAddress();
        if (jvmArg.isPresent()) {
            if (agentAddress.isPresent() && !agentAddress.get().equals(jvmArg.get().argument)) {
                error("jvm argument does not match the jdwp agent address argument");
            }
        } else {
            if (agentAddress.isPresent()) {
                arguments.add(1, new Argument("jvm", agentAddress.get()));
            } else {
                error("no jdwp agent present");
            }
        }
        if (isJavaAgentBeforeJDWPAgent()) {
            error("Java agent did not come before JDWP agent");
        }
        try {
            startProcess(getJVMPath(), getAgentPath(), arguments.stream().flatMap(a -> a.toCLIArgument().stream()).collect(Collectors.toList()));
        } catch (IOException e) {
            e.printStackTrace();
            error(String.format("error starting the jvm process (agent %s)", getAgentPath()));
        }
    }

    private static void startProcess(Path jvmPath, Path agentPath, List<String> arguments) throws IOException {
        List<String> processParts = new ArrayList<>(List.of(jvmPath.toString(), "-jar", agentPath.toString()));
        processParts.addAll(arguments);
        System.out.println("Start " + String.join(" ", processParts));
        var process = new ProcessBuilder(processParts).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
    }
}
