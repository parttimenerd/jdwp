package tunnel;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import jdwp.Reply;
import jdwp.Request;
import jdwp.util.Pair;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** caches replies and evicts old ones, not thread safe */
public class ReplyCache {

    public static final int DEFAULT_MAXIMUM_SIZE = 1000;
    public static final int DEFAULT_EXPIRATION_TIME = 1000; // one second
    final Cache<Request<?>, Reply> cache;

    public ReplyCache(int maximumSize, int expirationTime, List<Pair<Request<?>, Reply>> entries) {
        cache = CacheBuilder.newBuilder().maximumSize(maximumSize)
                .expireAfterAccess(Duration.ofMillis(expirationTime)).build();
        for (Pair<Request<?>, Reply> entry : entries) {
            cache.put(entry.first, entry.second);
        }
    }

    public ReplyCache(int maximumSize, int expirationTime) {
        this(maximumSize, expirationTime, List.of());
    }

    public ReplyCache(List<Pair<Request<?>, Reply>> entries) {
        this(DEFAULT_MAXIMUM_SIZE, DEFAULT_EXPIRATION_TIME, entries);
    }

    public ReplyCache() {
        this(DEFAULT_MAXIMUM_SIZE, DEFAULT_EXPIRATION_TIME);
    }

    public void invalidate() {
        cache.invalidateAll();
    }

    public void put(Request<?> request, Reply reply) {
        cache.put(request, reply);
    }

    /** returns a cached reply if possible, with the same id as the request */
    public Reply get(Request<?> request) {
        return Optional.ofNullable(cache.getIfPresent(request))
                .map(r -> (Reply)r.withNewId(request.getId())).orElse(null);
    }

    public boolean contains(Request<?> request) {
        return cache.getIfPresent(request) != null;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ReplyCache && ((ReplyCache) obj).cache.asMap().equals(cache.asMap());
    }

    @Override
    public int hashCode() {
        return cache.hashCode();
    }

    @Override
    public String toString() {
        return cache.asMap().toString();
    }
}
