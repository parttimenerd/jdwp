package tunnel;


import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.TypeConversionException;

import java.net.InetSocketAddress;
import java.util.concurrent.Callable;

@Command(name="tunnel", mixinStandardHelpOptions = true, description = "basic tunnel")
public class Main implements Callable<Integer> {

    // source: https://github.com/remkop/picocli/blob/main/picocli-examples/src/main/java/picocli/examples/typeconverter/InetSocketAddressConverterDemo.java
    static class InetSocketAddressConverter implements ITypeConverter<InetSocketAddress> {
        @Override
        public InetSocketAddress convert(String value) {
            int pos = value.lastIndexOf(':');
            if (pos < 0) {
                //throw new IllegalArgumentException("Invalid format: must be 'host:port' but was '" + value + "'");
                throw new TypeConversionException("Invalid format: must be 'host:port' but was '" + value + "'");
            }
            String adr = value.substring(0, pos);
            int port = Integer.parseInt(value.substring(pos + 1)); // invalid port shows the generic error message
            return new InetSocketAddress(adr, port);
        }
    }

    @Parameters(index = "0")
    private InetSocketAddress src;

    @Parameters(index = "1")
    private InetSocketAddress dst;

    @Override
    public Integer call() throws Exception {
        new Tunnel(src, dst).run();
        return 0;
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
