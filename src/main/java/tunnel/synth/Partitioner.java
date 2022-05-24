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
import tunnel.State.WrappedPacket;
import tunnel.synth.Partitioner.Partition;
import tunnel.util.Either;
import tunnel.util.ToCode;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static jdwp.util.Pair.p;

/**
 * Implements a listener that splits the incoming stream of packets into partitions.
 * A partition contains a set of packets without side effects, but might reference
 * the side effect packet that probably caused the packets of the partition.
 *
 * If the side effect is a request, then the request is part of partition
 */
public class Partitioner extends Analyser<Partitioner, Partition> implements Listener {

    static class Timings {

        private final double breakAtTimeFactor;
        private final List<Long> replyTimes = new ArrayList<>();
        private long averageDifference = 0;

        Timings(double breakAtTimeFactor) {
            this.breakAtTimeFactor = breakAtTimeFactor;
        }

        /** returns true if should break the partition before the current packet */
        boolean addReplyTimeAndCheckShouldBreak(long time) {
            if (shouldBreak(time)) {
                reset();
                replyTimes.add(time);
                return true;
            }
            replyTimes.add(time);
            updateAverage();
            return false;
        }

        void reset() {
            replyTimes.clear();
        }

        boolean shouldBreak(long replyTime) {
            return replyTimes.size() > 0 && averageDifference != 0 &&
                    (replyTime - replyTimes.get(replyTimes.size() - 1)) / (averageDifference * 1d) >= breakAtTimeFactor;
        }

        void updateAverage() {
            averageDifference = Math.round(IntStream.range(1, replyTimes.size())
                    .mapToLong(i -> replyTimes.get(i) - replyTimes.get(i - 1)).average().orElse(averageDifference));
        }
    }

    @EqualsAndHashCode(callSuper = true)
    public static class Partition extends AbstractList<Pair<Request<?>, Reply>> implements ToCode {
        private final @Nullable Either<Request<?>, Events> cause;
        private final List<Pair<? extends Request<?>, ? extends Reply>> items;

        Partition(@Nullable Either<Request<?>, Events> cause, List<Pair<? extends Request<?>, ? extends Reply>> items) {
            this.cause = cause;
            this.items = items;
        }

        Partition(Either<Request<?>, Events> cause) {
            this(cause, new ArrayList<>());
        }

        public Partition(List<Pair<? extends Request<?>, ? extends Reply>> items) {
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

        @SuppressWarnings("unchecked")
        @Override
        public Pair<Request<?>, Reply> get(int index) {
            return (Pair<Request<?>, Reply>) items.get(index);
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

        /**
         * Does the passed request affect the value of any contained reply if the accompanied request would be
         * resent
         *
         * Currently, only uses the onlyReads property. This should be extended later.
         */
        public boolean isAffectedBy(Events events) {
            return !events.onlyReads();
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

    private final Integer DEFAULT_TIMINGS_FACTOR = 5;
    private final Timings timings;
    private @Nullable Partition currentPartition;

    public Partitioner() {
        this.timings = new Timings(DEFAULT_TIMINGS_FACTOR);
    }

    private void startNewPartition(@Nullable Either<Request<?>, Events> cause) {
        if (currentPartition != null) {
            submit(currentPartition);
        }
        currentPartition = new Partition(cause);
    }

    @Override
    public void onRequest(Request<?> request) {
        if (currentPartition == null || currentPartition.isAffectedBy(request)) {
            startNewPartition(Either.left(request));
        }
    }

    @Override
    public void onEvent(Events events) {
        if (currentPartition == null || currentPartition.isAffectedBy(events)) {
            startNewPartition(Either.right(events));
        }
    }

    @Override
    public void onReply(WrappedPacket<Request<?>> requestPacket, WrappedPacket<ReplyOrError<?>> replyPacket) {
        var request = requestPacket.getPacket();
        var reply = replyPacket.getPacket();
        if (reply.isError()) {  // start a new partition on error
            startNewPartition(null);
        } else {
            if (currentPartition == null) {
                startNewPartition(null);
            } else if ((!request.equals(currentPartition.getCausePacket()) && currentPartition.isAffectedBy(request)) ||
                    timings.addReplyTimeAndCheckShouldBreak(replyPacket.getTime())) {
                startNewPartition(Either.left(request)); // but this should only happen in tests
            }
            currentPartition.add(p(request, reply.getReply()));
        }
    }

    @Override
    public void onTick() {
        if (currentPartition != null && timings.shouldBreak(System.currentTimeMillis())) {
            timings.reset();
            submit(currentPartition);
            currentPartition = null;
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
