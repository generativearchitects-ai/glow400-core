package ai.generativearchitects.glow400.pcml.parser;

/** How serious a {@link Diagnostic} is. */
public enum Severity {
    /** The PCML is invalid or cannot be modelled completely. */
    ERROR,
    /** The PCML is valid but suspicious. */
    WARNING,
    /** Something worth knowing that needs no action. */
    INFO
}
