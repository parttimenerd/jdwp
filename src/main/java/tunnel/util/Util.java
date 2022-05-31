package tunnel.util;

import ch.qos.logback.classic.Logger;
import lombok.SneakyThrows;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ServerSocket;

public class Util {
    @SneakyThrows
    public static InetSocketAddress findInetSocketAddress() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return new InetSocketAddress(socket.getLocalPort());
        }
    }

    public static void setDefaultLogLevel(ch.qos.logback.classic.Level level) {
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME))
                .setLevel(level);
    }
}
