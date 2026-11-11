package ai.generativearchitects.glow400.pcml.api;

/** How the runtime calls the program: the {@code call} setting of the overlay. */
public enum CallStrategy {
    /** Chosen by the runtime from the descriptor. */
    AUTO,
    /** Through the PCML definition. */
    PCML,
    /** Through a raw program call. */
    RAW
}
