package tunnel;

import jdwp.EventCmds.Events;
import jdwp.ParsedPacket;
import jdwp.Reply;
import jdwp.ReplyOrError;
import jdwp.Request;
import jdwp.util.Pair;
import lombok.Getter;
import tunnel.State.WrappedPacket;
import tunnel.util.MultiColumnLogbackLayout;
import tunnel.util.ToStringMode;

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

    /** called between packets if no packet is coming */
    default void onTick() {}

    /** prints all packages */
    class LoggingListener implements Listener {

        private final ToStringMode mode;
        private final int maxLineLength;

        public LoggingListener(ToStringMode mode, int maxLineLength) {
            this.mode = mode;
            this.maxLineLength = maxLineLength;
        }

        public LoggingListener() {
            this(ToStringMode.STRING, 200);
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
            var ps = mode.format(packet);
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

    /**
     * Separates the execution of the listener caller from the listener execution.
     * Blocking listener operations do not block the listener caller.
     */
    class ThreadedListener<L extends Listener> implements Listener {

        private static class Element {
            boolean tick = false;
            WrappedPacket<Request<?>> request = null;
            Pair<WrappedPacket<Request<?>>, WrappedPacket<ReplyOrError<?>>> reply = null;
            WrappedPacket<Events> events = null;
        }

        private volatile boolean closed = false;
        private final LinkedBlockingQueue<Element> queue;

        public ThreadedListener(L listener) {
            this.queue = new LinkedBlockingQueue<>();
            var currentCol = MultiColumnLogbackLayout.getCurrentColumn();
            new Thread(() -> {
                MultiColumnLogbackLayout.setCurrentColumn(currentCol);
                while (!closed) {
                    try {
                        var elem = queue.take();
                        if (elem.tick) {
                            listener.onTick();
                        } else if (elem.request != null) {
                            listener.onRequest(elem.request);
                        } else if (elem.reply != null) {
                            listener.onReply(elem.reply.first, elem.reply.second);
                        } else {
                            assert elem.events != null;
                            listener.onEvent(elem.events);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }

        @Override
        public void onRequest(WrappedPacket<Request<?>> request) {
            var elem = new Element();
            elem.request = request;
            queue.add(elem);
        }

        @Override
        public void onReply(WrappedPacket<Request<?>> request, WrappedPacket<ReplyOrError<?>> reply) {
            var elem = new Element();
            elem.reply = p(request, reply);
            queue.add(elem);
        }

        @Override
        public void onEvent(WrappedPacket<Events> event) {
            var elem = new Element();
            elem.events = event;
            queue.add(elem);
        }

        public void onTick() {
            var elem = new Element();
            elem.tick = true;
            queue.add(elem);
        }

        public void close() {
            this.closed = true;
        }
    }
}
