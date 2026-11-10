package ai.generativearchitects.glow400.pcml.api;

/** The type a field has in the API, as opposed to its PCML wire type: the {@code type} setting of the overlay. */
public enum SemanticType {
    /** Text. */
    STRING,
    /** Whole number. */
    INTEGER,
    /** Number with decimals. */
    DECIMAL,
    /** True or false. */
    BOOLEAN,
    /** Calendar date. */
    DATE,
    /** Time of day. */
    TIME,
    /** Date and time. */
    DATETIME,
    /** Raw bytes. */
    BINARY
}
