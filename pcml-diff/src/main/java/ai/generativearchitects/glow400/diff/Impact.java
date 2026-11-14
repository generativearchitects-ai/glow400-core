package ai.generativearchitects.glow400.diff;

/**
 * What a change means for the consumers of an API, from the least to the most serious. The impact of an API is the highest
 * impact among its changes.
 */
public enum Impact {
    /** Nothing changed. */
    NONE,
    /** Only text meant for people changed: titles, summaries, descriptions, tags, examples. */
    DOCUMENTATION,
    /** Only what a consumer cannot see changed: the program, the PCML, padding, wire formats, injected values, hidden fields. */
    INTERNAL,
    /** Consumers keep working and can use something new. */
    COMPATIBLE,
    /** Consumers can stop working. */
    BREAKING
}
