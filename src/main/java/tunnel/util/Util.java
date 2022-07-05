package tunnel.util;

import ch.qos.logback.classic.Logger;
import lombok.SneakyThrows;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    public static <T extends Comparable<T>> int compareOptionals(Optional<? extends T> x, Optional<? extends T> y) {
        if (x.isPresent() && y.isPresent()) {
            return x.get().compareTo(y.get());
        } else if (x.isPresent()) {
            return 1;
        } else if (y.isPresent()) {
            return -1;
        } else {
            return 0;
        }
    }

    public static <T> T or(T x, T y) {
        return x == null ? y : x;
    }

    @SuppressWarnings({"unchecked"})
    public static <T> List<List<T>> split(List<T> list, T splitter) {
        var ret = new ArrayList<List<T>>();
        var current = new ArrayList<T>();
        for (T t : list) {
            if (t.equals(splitter)) {
                ret.add(current);
                current = new ArrayList<>();
            } else {
                current.add(t);
            }
        }
        if (current.size() > 0) {
            ret.add(current);
        }
        return ret;
    }
}
