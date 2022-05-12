package tunnel;

import jdwp.EventCmds.Events;
import jdwp.ParsedPacket;
import jdwp.ReplyOrError;
import jdwp.Request;
import lombok.Getter;
import tunnel.State.WrappedPacket;

import java.util.ArrayList;
import java.util.List;

/** JDWP packet listener */
public interface Listener {

    default void onRequest(WrappedPacket<Request<?>> request) {
        onRequest(request.packet);
    }

    default void onRequest(Request<?> request) {
    }

    default void onReply(WrappedPacket<Request<?>> request, WrappedPacket<ReplyOrError<?>> reply) {
        onReply(reply.packet);
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
            var ps = packet.toString();
            if (ps.length() > 200) {
                ps = ps.substring(0, 200) + String.format("... (%d more)", ps.length() - 200);
            }
            System.out.printf("%.3f: %10s[%7d]: %s\n", System.currentTimeMillis() / 1000.0, prefix, packet.getId(), ps);
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
