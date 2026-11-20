package ai.generativearchitects.slay400.pcml.codec;

/**
 * How many scalar values a response may still hold, counted while decoding (kickoff 14, decision 113). Once the
 * limit is passed, nothing more is built: {@link #take()} keeps returning {@code false} and {@link #exceeded()}
 * stays {@code true} for the rest of the call, so the loops that walk an array or a struct can stop early.
 */
final class ValueBudget {

    private final long limit;
    private long count;
    private boolean exceeded;
    private boolean reported;

    ValueBudget(long limit) {
        this.limit = limit;
    }

    /**
     * Takes the budget for one scalar value. The value that reaches exactly {@code limit} is still allowed (it is
     * not "one over" yet); only the next one after it fails, and that is the one call where this flips
     * {@link #exceeded()} to {@code true} and returns {@code false}.
     */
    boolean take() {
        if (exceeded) {
            return false;
        }
        if (count >= limit) {
            exceeded = true;
            return false;
        }
        count++;
        return true;
    }

    boolean exceeded() {
        return exceeded;
    }

    /** {@code true} the first time this is called after the limit was reached; {@code false} every time after. */
    boolean reportOnce() {
        if (reported) {
            return false;
        }
        reported = true;
        return true;
    }
}
