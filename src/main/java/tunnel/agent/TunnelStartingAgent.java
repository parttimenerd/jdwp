package tunnel.agent;

import jdwp.util.Pair;
import lombok.AllArgsConstructor;

import javax.annotation.Nullable;
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

import static jdwp.util.Pair.p;
import static tunnel.util.Util.findInetSocketAddress;

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
                .orElseGet(() -> error(String.format("cannot find -javaagent in %s",
                        ManagementFactory.getRuntimeMXBean().getInputArguments())))
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
        var pair = prepareArguments(args, getJDWPAddress().orElse(null));
        var finalAddress = pair.first;
        var argumentLists = pair.second;
        List<Process> processes = new ArrayList<>();
        try {
            for (List<String> argumentList : argumentLists) {
                processes.add(startProcess(getJVMPath(), getAgentPath(), argumentList));
            }
        } catch (IOException e) {
            e.printStackTrace();
            error(String.format("error starting the jvm process (agent %s)", getAgentPath()));
            processes.forEach(Process::destroyForcibly);
        }
        System.out.printf("------- tunnel is listening at %s --------%n", finalAddress);
    }

    static Pair<String, List<List<String>>> prepareArguments(String args, @Nullable String jdwpAddress) {
        List<List<String>> argumentLists = new ArrayList<>();
        String previousAddress = jdwpAddress;
        for (String s : args.split(":")) {
            var pair = prepareArguments(s, previousAddress, args.endsWith(";" + s));
            previousAddress = pair.first;
            argumentLists.add(pair.second);
        }
        return p(previousAddress, argumentLists);
    }

    private static Pair<String, List<String>> prepareArguments(String args, @Nullable String previousAddress,
                                                               boolean isLast) {
        var arguments =
                Arrays.stream(args.split(","))
                        .map(Argument::create).collect(Collectors.toCollection(ArrayList::new));
        var jvmArg = arguments.stream().filter(a -> a.name.equals("jvm")).findFirst();
        var addressArg = arguments.stream().filter(a -> a.name.equals("address")).findFirst();
        if (addressArg.isEmpty()) {
            if (isLast) {
                error("missing address argument for last agent argument");
            }
            addressArg = Optional.of(new Argument("address", "" + findInetSocketAddress().getPort()));
            arguments.add(0, addressArg.get());
        }
        if (jvmArg.isPresent()) {
            if (previousAddress != null && !previousAddress.equals(jvmArg.get().argument)) {
                error("jvm argument does not match the jdwp agent address argument");
            }
        } else {
            if (previousAddress != null) {
                arguments.add(1, new Argument("jvm", previousAddress));
            } else {
                error("no jdwp agent present");
            }
        }
        if (isJavaAgentBeforeJDWPAgent()) {
            error("Java agent did not come before JDWP agent");
        }
        var stringArguments = arguments.stream().flatMap(a -> a.toCLIArgument().stream()).collect(Collectors.toList());
        return p(addressArg.get().argument, stringArguments);
    }

    private static Process startProcess(Path jvmPath, Path agentPath, List<String> arguments) throws IOException {
        List<String> processParts = new ArrayList<>(List.of(jvmPath.toString(), "-jar", agentPath.toString()));
        processParts.addAll(arguments);
        System.out.println("Start " + String.join(" ", processParts));
        var process = new ProcessBuilder(processParts).inheritIO().start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroyForcibly));
        return process;
    }
}
