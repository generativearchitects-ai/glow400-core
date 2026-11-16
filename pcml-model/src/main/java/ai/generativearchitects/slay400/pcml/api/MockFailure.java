package ai.generativearchitects.slay400.pcml.api;

/** How a mocked program call fails: what a mock case says with {@code fails} (kickoff 09, decision 79). */
public enum MockFailure {
    /** {@code unavailable}: the host cannot be reached. */
    UNAVAILABLE,
    /** {@code timeout}: the host does not answer in time. */
    TIMEOUT
}
