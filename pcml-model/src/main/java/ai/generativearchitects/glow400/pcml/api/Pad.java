package ai.generativearchitects.glow400.pcml.api;

/** Which side of a value is filled with the pad character: the {@code pad} setting of the overlay. */
public enum Pad {
    /** No padding: the default for every wire type except char and varchar. */
    NONE,
    /** Pad on the left (leading characters). */
    LEFT,
    /** Pad on the right (trailing characters). */
    RIGHT
}
