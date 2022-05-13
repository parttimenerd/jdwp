package tunnel.synth;

import jdwp.AbstractParsedPacket;
import jdwp.EventCmds.Events;
import jdwp.Reply;
import jdwp.ReplyOrError;
import jdwp.Request;
import jdwp.util.Pair;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.Nullable;
import tunnel.Listener;
import tunnel.synth.Partitioner.Partition;
import tunnel.util.Either;
import tunnel.util.ToCode;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static jdwp.util.Pair.p;

/**
 * Implements a listener that splits the incoming stream of packets into partitions.
 * A partition contains a set of packets without side effects, but might reference
 * the side effect packet that probably caused the packets of the partition.
 *
 * If the side effect is a request, then the request is part of partition
 */
public class Partitioner extends Analyser<Partitioner, Partition> implements Listener {

    @EqualsAndHashCode(callSuper = true)
    public static class Partition extends AbstractList<Pair<Request<?>, Reply>> implements ToCode {
        private final @Nullable Either<Request<?>, Events> cause;
        private final List<Pair<Request<?>, Reply>> items;

        Partition(@Nullable Either<Request<?>, Events> cause, List<Pair<Request<?>, Reply>> items) {
            this.cause = cause;
            this.items = items;
        }

        Partition(Either<Request<?>, Events> cause) {
            this(cause, new ArrayList<>());
        }

        public Partition(List<Pair<Request<?>, Reply>> items) {
            this(null, items);
        }

        Partition() {
            this(null, new ArrayList<>());
        }

        public boolean hasCause() {
            return cause != null;
        }

        @Override
        public int size() {
            return items.size();
        }

        @Override
        public Pair<Request<?>, Reply> get(int index) {
            return items.get(index);
        }

        @Override
        public String toString() {
            return (hasCause() ? cause.toString() + " => " : "") +
                    String.format("{%s}", items.stream()
                            .map(p -> String.format("%s -> %s", p.first, p.second))
                            .collect(Collectors.joining(",  ")));
        }

        @Override
        public String toCode() {
            return String.format("new Partition(%s, %s)", cause == null ? "null" : cause.toCode(),
                    String.format("List.of(\n%s)", items.stream()
                    .map(p -> String.format("\tp(%s, %s)", p.first.toCode(), p.second.toCode()))
                    .collect(Collectors.joining(",\n"))));
        }

        /**
         * Does the passed request affect the value of any contained reply if the accompanied request would be
         * resent
         *
         * Currently, only uses the onlyReads property. This should be extended later.
         */
        public boolean isAffectedBy(Request<?> request) {
            return !request.onlyReads();
        }

        @Override
        public boolean add(Pair<Request<?>, Reply> requestReplyPair) {
            return items.add(requestReplyPair);
        }

        public @Nullable Either<Request<?>, Events> getCause() {
            return cause;
        }

        public @Nullable AbstractParsedPacket getCausePacket() {
            return cause != null ? cause.get() : null;
        }
    }

    private @Nullable Partition currentPartition;

    public Partitioner() {
    }



    @Override
    public void onEvent(Events events) {
        Listener.super.onEvent(events);
    }

    private void startNewPartition(@Nullable Either<Request<?>, Events> cause) {
        if (currentPartition != null) {
            submit(currentPartition);
        }
        currentPartition = new Partition(cause);
    }

    @Override
    public void onRequest(Request<?> request) {
        if (currentPartition != null) {
            if (currentPartition.isAffectedBy(request)) {
                startNewPartition(Either.left(request));
            }
        }
    }

    @Override
    public void onReply(Request<?> request, ReplyOrError<?> reply) {
        if (reply.isError()) {  // start a new partition on error
            startNewPartition(null);
        } else {
            if (currentPartition == null) {
                startNewPartition(null);
            } else if (!request.equals(currentPartition.getCausePacket()) && currentPartition.isAffectedBy(request)) {
                startNewPartition(Either.left(request)); // but this should only happen in tests
            }
            currentPartition.add(p(request, reply.getReply()));
        }
    }

    @SafeVarargs
    public final void processReplies(Pair<Request<?>, Reply>... replies) {
        for (Pair<Request<?>, Reply> p : replies) {
            onRequest(p.first);
            onReply(p.first, p.second);
        }
    }

    @Override
    public void close() {
        if (currentPartition != null) {
            submit(currentPartition);
        }
    }
}
