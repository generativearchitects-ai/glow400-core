package ai.generativearchitects.slay400.http;

/**
 * The problems of reading a request that stop the binding from producing a value, in the family {@code REQ}. The codec's own
 * checks (lengths, ranges, digits, number of elements) are {@code VAL} problems that {@code OperationCodec.encode} reports
 * afterwards. A problem names the exposed path of the field and never carries a value.
 */
public enum RequestProblemCode {

    /** A body was sent without the content type {@code application/json}, with or without {@code charset=utf-8}. */
    CONTENT_TYPE("REQ001", "The body needs the content type application/json, in UTF-8"),

    /** A body was sent to an operation that declares none. */
    BODY_NOT_DECLARED("REQ002", "A body was sent, but the operation takes none"),

    /** The body is not JSON: not valid text or syntax, a property given twice, or content after the end. */
    NOT_JSON("REQ003", "The body is not valid JSON in UTF-8"),

    /** The body breaks a limit: its size, its depth, its number of values, or the length of a string or a number. */
    LIMIT_EXCEEDED("REQ004", "The body is larger or more complex than the limits allow"),

    /** A required field was not given, in the path, the query, a header or the body. */
    MISSING("REQ005", "A required field is missing"),

    /** A body object has a property that the API does not declare. */
    UNKNOWN_PROPERTY("REQ006", "The body has a property that the API does not declare"),

    /** A JSON value is of the wrong type: for example a number for a decimal, which travels as text, or null. */
    WRONG_TYPE("REQ007", "A value has the wrong JSON type"),

    /** Text does not have the written form of its type: a whole number, a decimal, a date, a time, a date-time, Base64. */
    WRONG_FORM("REQ008", "A text value does not have the form of its type"),

    /** A query parameter or a header that takes one value was given more than once. */
    REPEATED("REQ009", "A parameter that takes one value was given more than once");

    private final String code;
    private final String summary;

    RequestProblemCode(String code, String summary) {
        this.code = code;
        this.summary = summary;
    }

    /** The stable code, for example {@code REQ005}. */
    public String code() {
        return code;
    }

    /** What the code means, in one line. */
    public String summary() {
        return summary;
    }
}
