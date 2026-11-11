package ai.generativearchitects.glow400.pcml.api;

/**
 * The {@code cache} block of the overlay. Enterprise feature; the Community runtime ignores it.
 *
 * @param ttlSeconds time to live of a cached response; 0 means no caching
 */
public record CacheSettings(int ttlSeconds) {

    /** @throws IllegalArgumentException if {@code ttlSeconds} is negative */
    public CacheSettings {
        if (ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds must not be negative");
        }
    }
}
