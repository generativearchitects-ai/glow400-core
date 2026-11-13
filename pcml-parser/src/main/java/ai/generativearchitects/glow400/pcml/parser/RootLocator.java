package ai.generativearchitects.glow400.pcml.parser;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds where the root element of an XML document starts.
 *
 * <p>The XML parser reports where an event ends and says nothing about white space in the prolog, so for the root
 * element it cannot tell where the start tag begins when a DOCTYPE, comments or blank lines come first. This class reads
 * the prolog itself: it skips the XML declaration, comments, processing instructions and the DOCTYPE (with its internal
 * subset) and returns the position of the first {@code <} of the root element.
 */
final class RootLocator {

    /** A position in the document: 1-based line and column of a character. */
    record Position(int line, int column) {
    }

    private static final Pattern DECLARED_ENCODING =
            Pattern.compile("^<\\?xml[^>]*?encoding\\s*=\\s*[\"']([A-Za-z][A-Za-z0-9._-]*)[\"']");

    private RootLocator() {
    }

    /** The position of the root element's {@code <}, or empty if the prolog cannot be read this way. */
    static Optional<Position> find(byte[] bytes) {
        String text = decode(bytes);
        if (text == null) {
            return Optional.empty();
        }
        int index = skipProlog(text);
        return index < 0 ? Optional.empty() : Optional.of(positionOf(text, index));
    }

    // ------------------------------------------------------------------ decoding

    /** The document as text without a byte order mark, or {@code null} if its encoding is not recognised. */
    private static String decode(byte[] b) {
        int n = b.length;
        if (n >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, n - 3, StandardCharsets.UTF_8);
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, n - 2, StandardCharsets.UTF_16BE);
        }
        if (n >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, n - 2, StandardCharsets.UTF_16LE);
        }
        if (n >= 4 && b[0] == 0x00 && b[1] == 0x3C && b[2] == 0x00 && b[3] == 0x3F) {
            return new String(b, StandardCharsets.UTF_16BE);
        }
        if (n >= 4 && b[0] == 0x3C && b[1] == 0x00 && b[2] == 0x3F && b[3] == 0x00) {
            return new String(b, StandardCharsets.UTF_16LE);
        }
        Charset declared = declaredEncoding(b);
        return declared == null ? null : new String(b, declared);
    }

    /** The encoding named in the XML declaration, UTF-8 if there is none, {@code null} if it is not supported. */
    private static Charset declaredEncoding(byte[] b) {
        String start = new String(b, 0, Math.min(b.length, 200), StandardCharsets.ISO_8859_1);
        Matcher matcher = DECLARED_ENCODING.matcher(start);
        if (!matcher.find()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(matcher.group(1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ scanning

    /** The index of the root element's {@code <}, or -1 if the prolog is not well-formed enough to tell. */
    private static int skipProlog(String text) {
        int n = text.length();
        int i = 0;
        while (true) {
            while (i < n && isWhitespace(text.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return -1;
            }
            if (text.startsWith("<?", i)) {
                i = after(text, "?>", i + 2);
            } else if (text.startsWith("<!--", i)) {
                i = after(text, "-->", i + 4);
            } else if (text.startsWith("<!DOCTYPE", i)) {
                i = skipDoctype(text, i + 9);
            } else if (text.charAt(i) == '<' && i + 1 < n && isNameStart(text.charAt(i + 1))) {
                return i;
            } else {
                return -1;
            }
            if (i < 0) {
                return -1;
            }
        }
    }

    /** The index just after the next {@code terminator} at or after {@code from}, or -1. */
    private static int after(String text, String terminator, int from) {
        int end = text.indexOf(terminator, from);
        return end < 0 ? -1 : end + terminator.length();
    }

    /** Skips a DOCTYPE declaration, quoted literals and the internal subset included; returns the index after its {@code >}. */
    private static int skipDoctype(String text, int from) {
        int n = text.length();
        int i = from;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                i = after(text, String.valueOf(c), i + 1);
            } else if (c == '[') {
                i = skipInternalSubset(text, i + 1);
            } else if (c == '>') {
                return i + 1;
            } else {
                i++;
            }
            if (i < 0) {
                return -1;
            }
        }
        return -1;
    }

    /** Skips the internal subset, which ends at the first {@code ]} outside comments, instructions and quotes. */
    private static int skipInternalSubset(String text, int from) {
        int n = text.length();
        int i = from;
        while (i < n) {
            char c = text.charAt(i);
            if (text.startsWith("<!--", i)) {
                i = after(text, "-->", i + 4);
            } else if (text.startsWith("<?", i)) {
                i = after(text, "?>", i + 2);
            } else if (c == '"' || c == '\'') {
                i = after(text, String.valueOf(c), i + 1);
            } else if (c == ']') {
                return i + 1;
            } else {
                i++;
            }
            if (i < 0) {
                return -1;
            }
        }
        return -1;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_' || c == ':' || c >= 0x80;
    }

    /** Line and column of an index; a line ends at LF, CR or CR LF, as XML parsers count them. */
    private static Position positionOf(String text, int index) {
        int line = 1;
        int column = 1;
        for (int k = 0; k < index; k++) {
            char c = text.charAt(k);
            if (c == '\n') {
                line++;
                column = 1;
            } else if (c == '\r') {
                line++;
                column = 1;
                if (text.charAt(k + 1) == '\n') {
                    k++;
                }
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }
}
