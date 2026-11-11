package ai.generativearchitects.glow400.pcml.api;

/** How a program parameter takes part in the API: the {@code direction} of the overlay, or derived from PCML usage. */
public enum Direction {
    /** Sent by the caller to the program. */
    REQUEST,
    /** Returned by the program to the caller. */
    RESPONSE,
    /** Sent by the caller and returned by the program. */
    BOTH,
    /** Used by the call (for example an array count or an error flag) but not exposed. */
    INTERNAL,
    /** Filled by the runtime from an {@code inject} entry; not exposed. */
    INJECTED
}
