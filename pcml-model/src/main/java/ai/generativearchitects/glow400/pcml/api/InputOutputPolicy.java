package ai.generativearchitects.glow400.pcml.api;

/** What to do with an {@code inputoutput} parameter that has no overlay direction: {@code defaults.inputoutput}. */
public enum InputOutputPolicy {
    /** Treat the parameter as request and response, with a warning. */
    BOTH,
    /** Report an error. */
    FAIL
}
