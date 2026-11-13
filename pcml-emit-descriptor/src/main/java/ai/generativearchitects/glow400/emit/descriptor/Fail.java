package ai.generativearchitects.glow400.emit.descriptor;

/** A problem found while reading a descriptor; carries the diagnostic code and a message that names the place. */
final class Fail extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    Fail(String code, String message) {
        super(message, null, false, false);
        this.code = code;
    }

    String code() {
        return code;
    }

    /** A structure problem ({@code DSC003}) at a place in the document, such as {@code apis[0].name}. */
    static Fail structure(String path, String message) {
        return new Fail("DSC003", (path.isEmpty() ? "(document)" : path) + ": " + message);
    }
}
