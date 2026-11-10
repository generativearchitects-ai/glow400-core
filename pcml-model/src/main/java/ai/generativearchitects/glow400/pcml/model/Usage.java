package ai.generativearchitects.glow400.pcml.model;

/**
 * The PCML {@code usage} attribute of a {@code data} or {@code struct} element: in which direction
 * a value travels between caller and program.
 */
public enum Usage {
    /** {@code usage="input"}: sent to the program. */
    INPUT,
    /** {@code usage="output"}: returned by the program. */
    OUTPUT,
    /** {@code usage="inputoutput"}: sent to and returned by the program. */
    INPUT_OUTPUT,
    /** {@code usage="inherit"}, or the attribute is absent: taken from the enclosing element. */
    INHERIT
}
