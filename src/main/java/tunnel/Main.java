package tunnel;


import ch.qos.logback.classic.Logger;
import lombok.Getter;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import picocli.CommandLine;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

import java.net.InetSocketAddress;

/**
 * Main interface for the tunnel
 */
@Command(name = "tunnel", mixinStandardHelpOptions = true, description = "tunnel",
        subcommands = {PacketLogger.class})
public class Main {

    // source: https://github.com/remkop/picocli/blob/main/picocli-examples/src/main/java/picocli/examples/typeconverter/InetSocketAddressConverterDemo.java
    static class InetSocketAddressConverter implements ITypeConverter<InetSocketAddress> {
        @Override
        public InetSocketAddress convert(String value) {
            var parts = value.split(":");
            if (value.matches("(\\*:)?[0-9]+")) {
                return new InetSocketAddress(Integer.parseInt(parts[parts.length - 1]));
            } else if (value.matches(".*:[0-9]+") && parts.length == 2) {
                return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
            }
            throw new TypeConversionException(String.format("Address %s has invalid format", value));
        }
    }

    public final static Logger LOG = (Logger) LoggerFactory.getLogger("JDWP-Tunnel");

    @Option(names = {"--own", "--address"}, required = true, description = "open port",
            converter = InetSocketAddressConverter.class)
    @Getter
    private InetSocketAddress ownAddress;

    @Option(names = "--jvm", required = true,
            description = "JDWP address, if omitted use address of JDWP argument",
            converter = InetSocketAddressConverter.class)
    @Getter
    private InetSocketAddress jvmAddress;

    @Option(names = "--verbose")
    private Level logLevel = Level.INFO;

    void setDefaultLogLevel() {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
                .setLevel(ch.qos.logback.classic.Level.toLevel(logLevel.toString()));
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).setCaseInsensitiveEnumValuesAllowed(true).execute(args));
    }
}
