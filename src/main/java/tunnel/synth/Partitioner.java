package tunnel.synth;

import ch.qos.logback.classic.Logger;
import jdwp.AbstractParsedPacket;
import jdwp.EventCmds.Events;
import jdwp.EventCmds.Events.TunnelRequestReplies;
import jdwp.Reply;
import jdwp.ReplyOrError;
import jdwp.Request;
import jdwp.TunnelCmds.EvaluateProgramRequest;
import jdwp.util.Pair;
import lombok.EqualsAndHashCode;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;
import tunnel.Listener;
import tunnel.State.WrappedPacket;
import tunnel.synth.Partitioner.Partition;
import tunnel.util.Either;
import tunnel.util.ToCode;

import java.time.Clock;
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

    public final static Logger LOG = (Logger) LoggerFactory.getLogger("Partitioner");

    static class Timings {

        private final double breakAtTimeFactor;
        private final long minDifference; // HACK: automatically find min difference
        private final Clock clock;
        private final List<Long> replyTimes = new ArrayList<>();
        private long lastPacketTime = -1;
        private long averageDifference = 0;

        Timings(double breakAtTimeFactor, long minDifference, Clock clock) {
            this.breakAtTimeFactor = breakAtTimeFactor;
            this.clock = clock;
            this.minDifference = minDifference;
        }

        Timings(double breakAtTimeFactor, long minDifference) {
            this(breakAtTimeFactor, minDifference, Clock.systemDefaultZone());
        }

        /** returns true if it should break the partition before the current packet */
        boolean addReplyTimeAndCheckShouldBreak(long time) {
            if (shouldBreak(time)) {
                reset();
                replyTimes.add(time);
                return true;
            }
            replyTimes.add(time);
            updateAverage();
            lastPacketTime = time;
            return false;
        }

        boolean addRequestTimeAndCheckShouldBreak(long time) {
            if (shouldBreak(time)) {
                reset();
                return true;
            }
            lastPacketTime = time;
            return false;
        }

        void reset() {
            replyTimes.clear();
            lastPacketTime = -1;
        }

        long currentTimeMillis() {
            return clock.millis();
        }

        boolean shouldBreak() {
            return shouldBreak(currentTimeMillis());
        }
        boolean shouldBreak(long replyTime) {
            return replyTimes.size() > 0 && averageDifference != 0 &&
                    getReplyTimeFactor(replyTime) >= breakAtTimeFactor && getReplyTimeDifference(replyTime) >= minDifference;
        }

        double getReplyTimeFactor(long replyTime) {
            if (replyTimes.size() > 0 && averageDifference != 0) {
                return getReplyTimeDifference(replyTime) / (averageDifference * 1d);
            }
            return -1;
        }

        double getReplyTimeDifference(long replyTime) {
            if (replyTimes.size() > 0 && averageDifference != 0) {
                return replyTime - lastPacketTime;
            }
            return -1;
        }

        void updateAverage() {
            averageDifference = Math.round(IntStream.range(1, replyTimes.size())
                    .mapToLong(i -> replyTimes.get(i) - replyTimes.get(i - 1)).average().orElse(averageDifference));
        }
    }

    /**
     * invariant: cause is request => request is first statement in partition
     */
    @EqualsAndHashCode(callSuper = true)
    public static class Partition extends AbstractList<Pair<Request<?>, Reply>> implements ToCode {
        private @Nullable Either<Request<?>, Events> cause;
        private final List<Pair<? extends Request<?>, ? extends Reply>> items;

        Partition(@Nullable Either<Request<?>, Events> cause, List<Pair<? extends Request<?>, ? extends Reply>> items) {
            this.cause = cause;
            this.items = items;
            checkInvariant();
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
            checkInvariant();
            if (cause == null) {
                cause = Either.left(requestReplyPair.first);
            }
            if (items.size() > 0 && items.get(0).first.equals(requestReplyPair.first) &&
                    items.get(0).first.getId() == requestReplyPair.first.getId()) {
                return false;
            }
            var ret = items.add(requestReplyPair);
            if (!(cause == null || cause.isRight() || items.isEmpty() || items.get(0).first.equals(cause.getLeft()))) {
                //throw new AssertionError(String.format("Invariant does not hold for %s, after adding %s", this,
                // requestReplyPair));
                cause = Either.left(requestReplyPair.first);
                // might happen due to replies beeing processed after requests
                // a request might come in before the reply to an evaluate program request is processed
            }
            return ret;
        }

        private void checkInvariant() {
            assert cause == null || cause.isRight() || items.isEmpty() || items.get(0).first.equals(cause.getLeft());
            if (!(cause == null || cause.isRight() || items.isEmpty() || items.get(0).first.equals(cause.getLeft()))) {
                throw new AssertionError(String.format("Invariant does not hold for %s", this));
            }
        }

        public @Nullable Either<Request<?>, Events> getCause() {
            return cause;
        }

        public @Nullable AbstractParsedPacket getCausePacket() {
            return cause != null ? cause.get() : null;
        }
    }

    private static final Integer DEFAULT_TIMINGS_FACTOR = 100;
    private static final Integer DEFAULT_MIN_DIFFERENCE = 500;
    private final Timings timings;
    private @Nullable Partition currentPartition;

    private boolean enabled = true;

    public Partitioner(Timings timings) {
        this.timings = timings;
    }

    public Partitioner() {
        this(new Timings(DEFAULT_TIMINGS_FACTOR, DEFAULT_MIN_DIFFERENCE));
    }

    private void startNewPartition(String reason, @Nullable Either<Request<?>, Events> cause) {
        if (currentPartition != null) {
            submit(currentPartition);
        }
        currentPartition = cause == null ? null : new Partition(cause);
        if (currentPartition != null) {
            LOG.debug("Starting new partition: {}", currentPartition);
        }
        logSplitReason(reason);
    }

    private void removeRequestFromPartition(Request<?> request) {
        if (currentPartition != null) {
            if (request.equals(currentPartition.getCause())) {
                currentPartition = null;
            } else {
                currentPartition.items.removeIf(p -> p.first.equals(request));
            }
        }
    }

    @Override
    public void onRequest(WrappedPacket<Request<?>> requestPacket) {
        if (!enabled) {
            return;
        }
        var request = requestPacket.getPacket();
        boolean affected = false;
        if (request instanceof EvaluateProgramRequest) {
            if (currentPartition != null) {
                submit(currentPartition);
            }
            currentPartition = null;
            return;
        }
        if (currentPartition == null || (affected = currentPartition.isAffectedBy(request))) {
            startNewPartition(affected ? "current partition is affected by request" : "current partition is empty",
                    Either.left(request));
        }
        if (timings.addRequestTimeAndCheckShouldBreak(requestPacket.getTime())) {
            startNewPartition("???", Either.left(request)); // but this should only happen in tests
        }
    }

    @Override
    public void onEvent(Events events) {
        if (!enabled) {
            return;
        }
        boolean affected = false;
        if (events.events.size() == 0) {
            return;
        }
        assert !(events.events.get(0) instanceof TunnelRequestReplies);
        if (currentPartition == null || (affected = currentPartition.isAffectedBy(events))) {
            startNewPartition(affected ? "current partition is affected by event" : "current partition is empty",
                    Either.right(events));
        }
    }

    @Override
    public void onReply(WrappedPacket<Request<?>> requestPacket, WrappedPacket<ReplyOrError<?>> replyPacket) {
        if (!enabled) {
            return;
        }
        var request = requestPacket.getPacket();
        var reply = replyPacket.getPacket();
        if (reply.isError()) {  // start a new partition on error
            LOG.error("partition {} ended with error reply {} for request {}", currentPartition, reply, request);
            removeRequestFromPartition(request);
            startNewPartition(String.format("last reply was an error (%d for request %s)",
                    reply.getErrorCode(), request), null);
        } else {
            if (currentPartition == null) {
                startNewPartition("current partition is empty", null);
            } else if ((!request.equals(currentPartition.getCausePacket()) && currentPartition.isAffectedBy(request)) ||
                    timings.addReplyTimeAndCheckShouldBreak(replyPacket.getTime())) {
                startNewPartition("???", Either.left(request)); // but this should only happen in tests
            }
            try {
                if (request instanceof EvaluateProgramRequest) {
                    System.out.println("omit reply of " + request);
                    return;
                    // don't add these requests to partitions, as they are the result of caching in a different tunnel
                }
                if (currentPartition == null) {
                    startNewPartition("current partition is empty", Either.left(request));
                }
                currentPartition.add(p(request, reply.getReply()));
            } catch (Exception e) {
                LOG.error("Failed to add {} to partition {}", p(request, reply.getReply()), currentPartition);
                LOG.error("Failed ", e);
                startNewPartition("failed to add element to partition", null);
            }
        }
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        long time = timings.currentTimeMillis();
        if (currentPartition != null && timings.shouldBreak(time)) {
            logSplitReason(String.format("timing, %.2f times longer than last difference (difference was %.2fms)",
                    timings.getReplyTimeFactor(time), timings.getReplyTimeDifference(time)));
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

    public void enable() {
        enabled = true;
    }

    public void disable() {
        if (currentPartition != null) {
            submit(currentPartition);
        }
        currentPartition = null;
        enabled = false;
    }

    private void logSplitReason(String reason) {
        LOG.debug("Split reason: " + reason);
    }
}
