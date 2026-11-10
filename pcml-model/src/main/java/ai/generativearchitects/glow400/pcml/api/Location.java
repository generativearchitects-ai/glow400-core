package ai.generativearchitects.glow400.pcml.api;

/** Where a request field travels in the HTTP request: the {@code in} setting of the overlay. */
public enum Location {
    /** A segment of the route. */
    PATH,
    /** The query string. */
    QUERY,
    /** An HTTP header. */
    HEADER,
    /** The request body. */
    BODY
}
