package tunnel;

import jdwp.EventCmds.Events;
import jdwp.ParsedPacket;
import jdwp.Reply;
import jdwp.ReplyOrError;
import jdwp.Request;
import jdwp.util.Pair;
import lombok.Getter;
import tunnel.State.WrappedPacket;
import tunnel.util.Either;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

import static jdwp.util.Pair.p;

/** JDWP packet listener */
public interface Listener {

    default void onRequest(WrappedPacket<Request<?>> request) {
        onRequest(request.packet);
    }

    default void onRequest(Request<?> request) {
    }

    default void onReply(WrappedPacket<Request<?>> request, WrappedPacket<ReplyOrError<?>> reply) {
        onReply(request.packet, reply.packet);
    }

    default void onReply(Request<?> request, Reply reply) {
        onReply(request, new ReplyOrError<>(reply));
    }

    default void onReply(Request<?> request, ReplyOrError<?> reply) {
        onReply(reply);
    }

    default void onReply(ReplyOrError<?> reply) {
    }

    default void onEvent(WrappedPacket<Events> event) {
        onEvent(event.packet);
    }

    default void onEvent(Events events) {
    }

    /** prints all packages */
    class LoggingListener implements Listener {

        public enum Mode {
            STRING,
            CODE
        }

        private final Mode mode;
        private final int maxLineLength;

        public LoggingListener(Mode mode, int maxLineLength) {
            this.mode = mode;
            this.maxLineLength = maxLineLength;
        }

        public LoggingListener() {
            this(Mode.STRING, 200);
        }

        @Override
        public void onRequest(Request<?> request) {
            print("Request", request);
        }

        @Override
        public void onReply(ReplyOrError<?> reply) {
            print("Reply", reply);
        }

        @Override
        public void onEvent(Events events) {
            print("Event", events);
        }

        void print(String prefix, ParsedPacket packet) {
            String pre = String.format("%.3f: %10s[%7d]: ", System.currentTimeMillis() / 1000.0, prefix, packet.getId());
            var ps = mode == Mode.STRING ? packet.toString() : packet.toCode();
            int avLength = (maxLineLength == -1 ? Integer.MAX_VALUE : maxLineLength) - pre.length();
            if (ps.length() > avLength) {
                ps = ps.substring(0, avLength) + String.format("... (%d more)", ps.length() - 200);
            }
            System.out.println(pre + ps);
        }
    }

    /** collects all packages */
    class CollectingListener implements Listener {
        @Getter
        private final List<Request<?>> requests = new ArrayList<>();
        @Getter
        private final List<ReplyOrError<?>> replies = new ArrayList<>();
        @Getter
        private final List<Events> events = new ArrayList<>();

        @Override
        public void onRequest(Request<?> request) {
            requests.add(request);
        }

        @Override
        public void onReply(ReplyOrError<?> reply) {
            replies.add(reply);
        }

        @Override
        public void onEvent(Events events) {
            this.events.add(events);
        }
    }
}
