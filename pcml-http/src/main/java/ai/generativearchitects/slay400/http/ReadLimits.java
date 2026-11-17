package ai.generativearchitects.slay400.http;

/**
 * What a request body may be. A body that breaks a limit is the problem {@code REQ004}, never an exception.
 *
 * @param maxBodyBytes the most bytes in the body
 * @param maxDepth     the deepest nesting of objects and arrays in the body
 * @param maxValues    the most values (scalars, objects and arrays) in the body
 */
public record ReadLimits(int maxBodyBytes, int maxDepth, int maxValues) {

    private static final ReadLimits DEFAULTS = new ReadLimits(1024 * 1024, 32, 100_000);

    /** @throws IllegalArgumentException if a limit is not positive */
    public ReadLimits {
        if (maxBodyBytes < 1 || maxDepth < 1 || maxValues < 1) {
            throw new IllegalArgumentException("every limit must be positive");
        }
    }

    /** The limits used when none are given: a body of 1 MiB, nested 32 deep, with 100,000 values. */
    public static ReadLimits defaults() {
        return DEFAULTS;
    }
}
