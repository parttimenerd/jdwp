package tunnel;

import ch.qos.logback.classic.Logger;
import jdwp.EventCmds.Events;
import jdwp.*;
import jdwp.JDWP.StateProperty;
import jdwp.exception.TunnelException;
import jdwp.util.Pair;
import lombok.Value;
import lombok.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.LoggerFactory;
import tunnel.ReplyCache.Cache.CacheEntry;
import tunnel.ReplyCache.Cache.EvictionCause;
import tunnel.util.TriConsumer;
import tunnel.util.TriFunction;
import tunnel.util.Util;

import java.util.*;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static jdwp.util.Pair.p;
import static tunnel.ReplyCache.Cache.EvictionCause.*;
import static tunnel.ReplyCache.Cache.EvictionCause.SIZE;
import static tunnel.ReplyCache.Statistics.ColumnType.*;

/**
 * two level cache that caches replies and evicts old ones, not thread safe
 */
public class ReplyCache implements Listener {

    private static final Logger LOG = (Logger) LoggerFactory.getLogger("ReplyCache");

    @AllArgsConstructor
    @NoArgsConstructor
    public static class Options {
        /**
         * Only use the {@link Request#invalidatesReplyCache()} and evict volatile entries
         * (affected by {@link StateProperty#TIME})
         */
        @With
        boolean conservative = false;

        /**
         * Maximum size of the fast first level cache that contains all
         * requests and replies as objects. These objects cost more memory than
         * the bit string representation in the second level cache.
         * This cache should be small, elements are evicted into the second level cache
         * (or discarded if they are affected by {@link StateProperty#TIME})
         * */
        @With
        int maximumL1Size = 100;
        /**
         * Maximum size of the second level cache that contains the replies as bit strings,
         * thereby conserving memory.
         * The size is given in number of elements and in bytes.
         */
        @With
        int maximumL2Size = 10000;
        @With
        int maximumL2ByteSize = 10_000_000;

        /**
         * The time interval that even volatile entries are kept in the cache (affected by {@link StateProperty#TIME}).
         * An example for such an entry is {@link jdwp.ReferenceTypeCmds.GetValuesRequest} which might change
         * due to concurrently writing threads.
         *
         * @see StateProperty#TIME
         */
        @With
        int timePeriodMillis = 50;

        /**
         * Period after which deposed objects are garbage collected and reused.
         *
         * @see StateProperty#GARBAGECOLLECTIONTIME
         */
        @With
        int garbageCollectionPeriodMillis = 1000;

        /**
         * Period in which classes are reloaded (wait n milliseconds for the class to appear after it is loaded)
         *
         * @see StateProperty#CLASSLOADTIME
         */
        @With
        int classLoadPeriodMillis = 10_000_000;

        /**
         * @see StateProperty#THREADLOADTIME
         */
        @With
        int threadLoadPeriodMillis = 10000;

    }

    @NoArgsConstructor
    @AllArgsConstructor
    public static class Statistics {

        @Value
        static class Info {
            int hits;
            int size;
        }

        @AllArgsConstructor
        enum ColumnType {
            STRING("%s"),
            RATE("%.3f"),
            SIZE("%.0f"),
            AVG_COUNT("%.2f"),
            COUNT("%.0f");
            final String format;
        }

        @Value
        static class Column {
            String name;
            ColumnType type;
            TriFunction<Pair<Integer, Integer>, List<Info>, List<Info>, Object> computer;
        }

        private final List<Column> columns = List.of(
                new Column("CommandSet", STRING, (p, l, r) -> p == null ? "Overall" :
                        JDWP.getCommandSetName((byte) (int) p.first) + "(" + p.first + ")"),
                new Column("CommandSet", STRING, (p, l, r) -> p == null ? "" :
                        JDWP.getCommandName((byte) (int) p.first, (byte) (int) p.second) + "(" + p.second + ")"),
                new Column("%", RATE, (p, ol, l) -> (double) l.size() / ol.size()),
                new Column("#", RATE, (p, ol, l) -> (double) l.size()),
                new Column("mean hits", AVG_COUNT, (p, ol, l) -> l.stream().mapToInt(i -> i.hits).average().orElse(0)),
                new Column("% hits > 0", RATE,
                        (p, ol, l) -> l.stream().filter(i -> i.hits > 0).count() / (double) l.size()),
                new Column("% hits > 1", RATE,
                        (p, ol, l) -> l.stream().filter(i -> i.hits > 1).count() / (double) l.size()),
                new Column("max hits", COUNT, (p, ol, l) -> (double) l.stream().mapToInt(i -> i.hits).max().orElse(0)),
                new Column("min size", COUNT, (p, ol, l) -> (double) l.stream().mapToInt(i -> i.size).min().orElse(0)),
                new Column("max size", COUNT, (p, ol, l) -> (double) l.stream().mapToInt(i -> i.size).max().orElse(0)),
                new Column("mean size", AVG_COUNT, (p, ol, l) -> l.stream().mapToInt(i -> i.size).average().orElse(0))
        );

        Map<Pair<Integer, Integer>, List<Info>> hitsPerCommandEntry = new HashMap<>();

        public void addHits(Request<?> request, CacheEntry<?> entry) {
            hitsPerCommandEntry.computeIfAbsent(p(request.getCommandSet(), request.getCommand()),
                    k -> new ArrayList<>()).add(new Info(entry.getHits(), entry.size()));
        }

        public void addHits(List<Pair<Request<?>, CacheEntry<?>>> entry) {
            entry.forEach(p -> addHits(p.first, p.second));
        }

        List<Info> combinedList() {
            return hitsPerCommandEntry.values().stream().flatMap(List::stream).collect(Collectors.toList());
        }

        private List<String> computeLine(@Nullable Pair<Integer, Integer> command,
                                         List<Info> combinedLine, List<Info> infos) {
            return columns.stream()
                    .map(c -> String.format(Locale.US, c.type.format, c.computer.apply(command, combinedLine, infos)))
                    .collect(Collectors.toList());
        }

        private List<String> combineLines(List<List<String>> columnLines) {
            List<String> headers = columns.stream().map(c -> c.name).collect(Collectors.toList());
            List<Integer> columnLengths = IntStream.range(0, headers.size())
                    .mapToObj(i -> Stream.concat(Stream.of(headers), columnLines.stream())
                            .mapToInt(x -> x.get(i).length()).max().orElse(0))
                    .collect(Collectors.toList());
            return Stream.concat(Stream.of(headers), columnLines.stream()).map(l ->
                            IntStream.range(0, columnLengths.size())
                                    .mapToObj(i -> Util.padStart(l.get(i), columnLengths.get(i), ' '))
                                    .collect(Collectors.joining(", ")))
                    .collect(Collectors.toList());
        }

        @Override
        public String toString() {
            return String.join("\n", combineLines(List.of(computeLine(null, combinedList(), combinedList()))));
        }

        /** all rows, sorted desc by their % of overall entries */
        public String toLongTable() {
            List<Info> combinedList = combinedList();
            List<Pair<Integer, Integer>> commandsSorted = hitsPerCommandEntry.keySet().stream()
                    .sorted(Comparator.comparing(p -> (double) hitsPerCommandEntry.get(p).size() / combinedList.size()).reversed())
                    .collect(Collectors.toList());
            var lines = Stream.concat(Stream.of(computeLine(null, combinedList, combinedList)),
                            commandsSorted.stream().map(c -> computeLine(c, combinedList, hitsPerCommandEntry.get(c))))
                    .collect(Collectors.toList());
            return String.join("\n", combineLines(lines));
        }

        /** number of entries overall commands */
        public int size() {
            return hitsPerCommandEntry.values().stream().mapToInt(List::size).sum();
        }

        public void add(Statistics other) {
            other.hitsPerCommandEntry.forEach((k, v) -> hitsPerCommandEntry.computeIfAbsent(k,
                    k2 -> new ArrayList<>()).addAll(v));
        }
    }

    @Getter
    static class Cache<T> {

        enum EvictionCause {
            TIME,
            SIZE,
            AFFECTS,
            REPLACE
        }

        @AllArgsConstructor
        @Getter
        @ToString
        static class RequestWithTime implements Comparable<RequestWithTime> {
            final long evictionTime;
            final Request<?> request;

            @Override
            public int compareTo(@NotNull ReplyCache.Cache.RequestWithTime o) {
                return Long.compare(evictionTime, o.evictionTime);
            }
        }

        @AllArgsConstructor
        @Getter
        static class CacheEntry<T> {
            private final T reply;
            private final long addedTimeMillis;
            private final boolean prefetched;
            private int hits;

            void incrementHits() {
                hits++;
            }

            int size() {
                assert reply instanceof Packet || reply instanceof ReplyOrError<?>;
                if (reply instanceof Packet) {
                    return ((Packet) reply).size();
                } else {
                    return ((ReplyOrError<?>) reply).toPacket(new VM(0)).size();
                }
            }
        }

        @Getter
        private final Options options;
        private final int maximumSize;
        private final int removeAtOnce;
        private final int maximumOtherSize;
        private final int removeAtOnceOtherSize;
        private final Function<T, Integer> otherSizeComputer;
        private int otherSize = 0;

        private final @Nullable TriConsumer<Request<?>, CacheEntry<T>, EvictionCause> evictionListener;
        private final Map<Request<?>, CacheEntry<T>> cache = new HashMap<>();
        private final PriorityQueue<RequestWithTime> timeBasedEvictionQueue = new PriorityQueue<>();

        Cache(Options options, int maximumSize, int maximumOtherSize, Function<T, Integer> otherSizeComputer,
              @Nullable TriConsumer<Request<?>, CacheEntry<T>, EvictionCause> evictionListener) {
            this.options = options;
            this.maximumSize = maximumSize;
            this.maximumOtherSize = maximumOtherSize;
            this.otherSizeComputer = otherSizeComputer;
            this.evictionListener = evictionListener;
            this.removeAtOnce = Math.max(1, maximumSize / 10);
            this.removeAtOnceOtherSize = Math.max(1, maximumOtherSize / 10);
        }

        Cache(Options options, int maximumSize,
              @Nullable TriConsumer<Request<?>, CacheEntry<T>, EvictionCause> evictionListener) {
            this(options, maximumSize, -1, x -> 0, evictionListener);
        }


        public void tick() {
            evictBasedOnTime(System.currentTimeMillis());
        }

        public void invalidateAndEvictIfNeeded(Request<?> requestOrEvent) {
            evictBasedOnTime(System.currentTimeMillis());
            var remove = new ArrayList<Entry<Request<?>, CacheEntry<T>>>();
            if (options.conservative) {
                if (requestOrEvent.invalidatesReplyCache()) {
                    remove.addAll(cache.entrySet());
                }
            } else {
                if (requestOrEvent.getAffects().isEmpty()) {
                    return;
                }
                for (Entry<Request<?>, CacheEntry<T>> e : cache.entrySet()) {
                    if (e.getKey().isAffectedBy(requestOrEvent)) {
                        remove.add(e);
                    }
                }
            }
            for (Entry<Request<?>, CacheEntry<T>> e : remove) {
                cache.remove(e.getKey());
                recordEviction(e.getKey(), e.getValue(), AFFECTS);
            }
            timeBasedEvictionQueue.removeIf(r -> !cache.containsKey(r.request));
            checkInvariants();
        }

        private void evictBasedOnTime(long currentTimeMillis) {
            var remove = new ArrayList<RequestWithTime>();
            for (RequestWithTime requestWithTime : timeBasedEvictionQueue) {
                if (requestWithTime.evictionTime <= currentTimeMillis) {
                    remove.add(requestWithTime);
                } else {
                    break;
                }
            }
            if (remove.isEmpty()) {
                return;
            }
            checkInvariants();
            timeBasedEvictionQueue.removeAll(remove);
            for (RequestWithTime requestWithTime : remove) {
                var entry = cache.get(requestWithTime.request);
                otherSize -= otherSizeComputer.apply(entry.reply);
                recordEviction(requestWithTime.request, Objects.requireNonNull(entry), TIME);
                cache.remove(requestWithTime.request);
            }
            checkInvariants();
        }

        private void checkInvariants() {
            if (cache.size() < timeBasedEvictionQueue.size()) {
                throw new AssertionError("cache.size() >= timeBasedEvictionQueue.size()");
            }
            if (!timeBasedEvictionQueue.stream().allMatch(r -> cache.containsKey(r.request))) {
                throw new AssertionError("timeBasedEvictionQueue.stream().allMatch(r -> cache.containsKey(r.request))");
            }
        }

        private void recordEviction(Request<?> request, CacheEntry<T> entry, EvictionCause cause) {
            if (evictionListener != null) {
                evictionListener.accept(request, entry, cause);
            }
        }

        private void evictLeastRecentlyAdded(int num) {
            var remove =
                    cache.entrySet().stream().sorted(Comparator.comparing(e -> e.getValue().getAddedTimeMillis()))
                            .limit(num).collect(Collectors.toList());
            if (remove.isEmpty()) {
                return;
            }
            for (Entry<Request<?>, CacheEntry<T>> entry : remove) {
                cache.remove(entry.getKey());
            }
            timeBasedEvictionQueue.removeIf(e -> !cache.containsKey(e.request));
            remove.forEach(e -> recordEviction(e.getKey(), e.getValue(), SIZE));
            if (otherSize != -1) {
                otherSize -= remove.stream().mapToInt(e -> otherSizeComputer.apply(e.getValue().reply)).sum();
            }
            checkInvariants();
        }

        private void evictLargest(int freedUpOtherSize) {
            var sortedByOtherSize =
                    cache.entrySet().stream().map(e -> p(e, otherSizeComputer.apply(e.getValue().getReply())))
                            .sorted(Comparator.comparing(p -> p.second)).collect(Collectors.toList());
            var remove = new ArrayList<Entry<Request<?>, CacheEntry<T>>>();
            int freedUp = 0;
            for (var i = sortedByOtherSize.size() - 1; i >= 0; i--) {
                var p = sortedByOtherSize.get(i);
                freedUp += p.second;
                remove.add(p.first);
                if (freedUp >= freedUpOtherSize) {
                    break;
                }
            }
            if (remove.isEmpty()) {
                return;
            }
            timeBasedEvictionQueue.removeIf(e -> remove.stream().anyMatch(e2 -> e2.getKey().equals(e.request)));
            remove.forEach(e -> {
                recordEviction(e.getKey(), e.getValue(), SIZE);
                cache.remove(e.getKey());
            });
            otherSize -= freedUp;
            checkInvariants();
        }

        private void evict(Request<?> request) {
            var entry = cache.get(request);
            if (entry == null) {
                return;
            }
            checkInvariants();
            if (cache.remove(request) == null) {
                throw new AssertionError("cache.remove(request) == null");
            }
            timeBasedEvictionQueue.removeIf(e -> e.request.equals(request));
            recordEviction(request, entry, REPLACE);
            otherSize -= otherSizeComputer.apply(entry.reply);
            checkInvariants();
        }

        public boolean canCache(Request<?> request) {
            return request.onlyReads();
        }

        /** does not trigger any invalidation based on effects */
        public void put(Request<?> request, T reply, boolean prefetched) {
            if (!request.onlyReads()) {
                throw new AssertionError("Only requests that only read should on should be cached");
            }
            tick(); // try to open up some space
            if (cache.size() >= maximumSize) {
                evictLeastRecentlyAdded(removeAtOnce);
            }
            if (cache.containsKey(request)) {
                evict(request);
            }
            cache.put(request, new CacheEntry<>(reply, System.currentTimeMillis(), prefetched, 0));
            var evictionTime = getEvictionTimeForRequest(request);
            if (evictionTime != -1) {
                timeBasedEvictionQueue.add(new RequestWithTime(evictionTime, request));
            }
            if (maximumOtherSize != -1 && otherSize < maximumOtherSize) {
                otherSize += otherSizeComputer.apply(reply);
                evictLargest(removeAtOnceOtherSize);
            }
            checkInvariants();
        }

        public @Nullable T get(Request<?> request) {
            var entry = cache.get(request);
            if (entry == null) {
                return null;
            }
            entry.incrementHits();
            return entry.reply;
        }

        /** time in millis when the request should be evicted, or -1 if no time based eviction */
        private long getEvictionTimeForRequest(Request<?> request) {
            long interval = LongStream.of(
                    request.isAffectedByTime() ? options.timePeriodMillis : -1,
                    request.isAffectedBy(StateProperty.GARBAGECOLLECTIONTIME) ?
                            options.garbageCollectionPeriodMillis : -1,
                    request.isAffectedBy(StateProperty.CLASSLOADTIME) ? options.classLoadPeriodMillis : -1,
                    request.isAffectedBy(StateProperty.THREADLOADTIME) ? options.threadLoadPeriodMillis : -1
            ).max().orElse(-1);
            if (interval == -1) {
                return -1;
            }
            return System.currentTimeMillis() + interval;
        }

        public Collection<Entry<Request<?>, CacheEntry<T>>> getCacheEntries() {
            return cache.entrySet();
        }

        public boolean contains(Request<?> request) {
            return cache.containsKey(request);
        }

        int size() {
            return cache.size();
        }

        @Override
        public String toString() {
            if (maximumOtherSize != -1) {
                return String.format("Cache(size=%d (%2.0f%%), other=%d (%2.0f%%))",
                        cache.size(), cache.size() / (maximumSize * 1.0) * 100,
                        otherSize, otherSize / (maximumOtherSize * 1.0) * 100);
            }
            return String.format("Cache(size=%d (%2.0f%%))", cache.size(), cache.size() / (maximumSize * 1.0) * 100);
        }
    }

    public static final Options DEFAULT_OPTIONS = new Options();

    @Getter
    private final Options options;
    private final VM vm;
    private final Cache<ReplyOrError<?>> l1Cache;
    private final Cache<Packet> l2Cache;
    @Getter
    private final Statistics prefetchedStats;
    @Getter
    private final Statistics nonPrefetchedStats;

    public ReplyCache(Options options, VM vm, List<Pair<Request<?>, Reply>> entries) {
        this(options, vm);
        for (var entry : entries) {
            put(entry.first, new ReplyOrError<>(entry.second), false);
        }
    }

    public ReplyCache(Options options, VM vm) {
        this.options = options;
        this.vm = vm;
        this.l1Cache = new Cache<>(options, options.maximumL1Size, this::l1EvictionListener);
        this.l2Cache = new Cache<>(options, options.maximumL2Size, options.maximumL2ByteSize,
                Packet::size, this::recordStats);
        this.prefetchedStats = new Statistics();
        this.nonPrefetchedStats = new Statistics();
    }

    public ReplyCache(VM vm, List<Pair<Request<?>, Reply>> entries) {
        this(DEFAULT_OPTIONS, vm, entries);
    }

    public ReplyCache(VM vm) {
        this(DEFAULT_OPTIONS, vm);
    }

    /** evict into l2 cache */
    private void l1EvictionListener(Request<?> request, CacheEntry<ReplyOrError<?>> reply, EvictionCause cause) {
        LOG.debug("Evicting {} from l1 cache due to {}", request, cause);
        if (cause == SIZE) {
            l2Cache.put(request, reply.reply.toPacket(vm), reply.prefetched);
        } else {
            recordStats(request, reply, cause);
        }
    }

    /** record stats */
    private void recordStats(Request<?> request, CacheEntry<?> reply, EvictionCause cause) {
        if (reply.prefetched) {
            prefetchedStats.addHits(request, reply);
        } else {
            nonPrefetchedStats.addHits(request, reply);
        }
    }

    /** add all remaining entries to the statistics */
    public void close() {
        Stream.concat(l1Cache.getCacheEntries().stream(), l2Cache.getCacheEntries().stream()).forEach(e -> {
            recordStats(e.getKey(), e.getValue(), SIZE);
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Statistics getStats(boolean prefetched) {
        var stats = new Statistics(new HashMap<>(prefetched ? prefetchedStats.hitsPerCommandEntry :
                nonPrefetchedStats.hitsPerCommandEntry));
        stats.addHits((List<Pair<Request<?>, CacheEntry<?>>>) (List) Stream.concat(l1Cache.getCacheEntries().stream()
                        , l2Cache.getCacheEntries().stream())
                .filter(e -> e.getValue().isPrefetched() == prefetched).map(e -> p(e.getKey(), e.getValue()))
                .collect(Collectors.toList()));
        return stats;
    }

    /** get the statistics for prefetched requests, including all entries that are currently in the caches */
    public Statistics getPrefetchedStatistics() {
        return getStats(true);
    }

    /** get the statistics for non-prefetched requests, including all entries that are currently in the caches */
    public Statistics getNonPrefetchedStatistics() {
        return getStats(false);
    }

    public Statistics getStatistics() {
        var stats = getPrefetchedStatistics();
        stats.add(getNonPrefetchedStatistics());
        return stats;
    }

    public void put(Request<?> request, ReplyOrError<?> reply, boolean prefetched) {
        assert reply.isReplyLike();
        l1Cache.put(request, reply, prefetched);
    }

    public @Nullable ReplyOrError<?> get(Request<?> request) {
        var reply = l1Cache.get(request);
        if (reply != null) {
            return (ReplyOrError<?>) reply.withNewId(request.getId());
        }
        var replyPacket = l2Cache.get(request);
        if (replyPacket == null) {
            return null;
        }
        try {
            return (ReplyOrError<?>) request.parseReply(replyPacket.toStream(vm)).withNewId(request.getId());
        } catch (TunnelException ex) {
            ex.log(LOG, "cache get " + request);
        } catch (Exception | AssertionError ex) {
            LOG.error("cache get " + request, ex);
        }
        return null;
    }


    public boolean contains(Request<?> request) {
        return l1Cache.contains(request) || l2Cache.contains(request);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof ReplyCache)) {
            return false;
        }
        var other = (ReplyCache) obj;
        return other.l2Cache.cache.keySet().equals(l2Cache.cache.keySet()) &&
                other.l1Cache.cache.keySet().equals(l1Cache.cache.keySet());
    }

    @Override
    public String toString() {
        return "L1=" + l1Cache + ", " + "L2=" + l2Cache;
    }

    public int size() {
        return l1Cache.size() + l2Cache.size();
    }

    @Override
    public void onTick() {
        l1Cache.tick();
        l2Cache.tick();
    }

    @Override
    public void onEvent(Events events) {
        l1Cache.invalidateAndEvictIfNeeded(events);
        l2Cache.invalidateAndEvictIfNeeded(events);
    }

    @Override
    public void onRequest(Request<?> request) {
        l1Cache.invalidateAndEvictIfNeeded(request);
        l2Cache.invalidateAndEvictIfNeeded(request);
    }

    @Override
    public void onReply(Request<?> request, ReplyOrError<?> reply) {
        if (l1Cache.canCache(request) && reply.isReplyLike()) {
            put(request, reply, false);
        }
        onRequest(request);
    }

    public Set<Request<?>> getCachedRequests() {
        return Stream.concat(l1Cache.getCache().keySet().stream(), l2Cache.getCache().keySet().stream())
                .collect(Collectors.toSet());
    }

    public static class DisabledReplyCache extends ReplyCache {

        public DisabledReplyCache(VM vm) {
            super(vm);
        }

        @Override
        public void put(Request<?> request, ReplyOrError<?> reply, boolean prefetched) {
        }
    }
}
