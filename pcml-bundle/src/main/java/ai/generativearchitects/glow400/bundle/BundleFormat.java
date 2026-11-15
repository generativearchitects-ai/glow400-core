package ai.generativearchitects.glow400.bundle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/** What the writer and the reader agree on: the layout, the limits and the checksums. */
final class BundleFormat {

    /** The file extension of a bundle. It is provisional, so it is written in one place. */
    static final String EXTENSION = ".rpgapi";
    /** The version of the bundle format this code writes and reads. */
    static final int SCHEMA_VERSION = 1;

    static final String MANIFEST = "manifest.json";
    static final String DESCRIPTOR = "descriptor.json";
    static final String GATEWAY = "gateway.yaml";

    /** The most characters of an entry name. */
    static final int MAX_PATH_LENGTH = 255;

    /**
     * What a bundle may hold: the most entries, the most bytes of one entry and of all entries together.
     *
     * @param maxEntries    the most entries
     * @param maxEntryBytes the most bytes of one entry
     * @param maxTotalBytes the most bytes of all entries together
     */
    record Limits(int maxEntries, long maxEntryBytes, long maxTotalBytes) {
        /** The limits of a bundle. */
        static final Limits STANDARD = new Limits(4096, 16L * 1024 * 1024, 128L * 1024 * 1024);
    }

    /** The entry names the layout allows. */
    private static final List<Pattern> LAYOUT = List.of(
            Pattern.compile("manifest\\.json"),
            Pattern.compile("descriptor\\.json"),
            Pattern.compile("gateway\\.yaml"),
            Pattern.compile("openapi/[a-z0-9][a-z0-9._-]*\\.yaml"),
            Pattern.compile("apis/[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*\\.pcml"),
            Pattern.compile("apis/[A-Za-z0-9][A-Za-z0-9._-]*/api\\.ya?ml"));

    private BundleFormat() {
    }

    /** Whether the layout allows an entry with this name. */
    static boolean allowed(String path) {
        return path.length() <= MAX_PATH_LENGTH && LAYOUT.stream().anyMatch(p -> p.matcher(path).matches());
    }

    /** Why a name cannot be an entry name at all, or empty if it can: absolute, {@code ..}, backslash, empty segment, directory. */
    static String unsafe(String path) {
        if (path.isEmpty()) {
            return "an empty name";
        }
        if (path.startsWith("/")) {
            return "an absolute path";
        }
        if (path.contains("\\")) {
            return "a backslash";
        }
        if (path.endsWith("/")) {
            return "a directory entry";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) {
                return "an empty path segment";
            }
            if (segment.equals("..") || segment.equals(".")) {
                return "a '.' or '..' segment";
            }
        }
        return "";
    }

    /** The longest name or text shown in a message, in characters of the original. */
    static final int SHOWN_LENGTH = 100;

    /** The longest message of another program shown in a message. */
    static final int SHOWN_MESSAGE_LENGTH = 300;

    /** A name from an untrusted file, for a message: printable ASCII as it is, everything else as {@code \\uXXXX}, cut at 100 characters. */
    static String shown(String name) {
        return shown(name, SHOWN_LENGTH);
    }

    /** Like {@link #shown(String)}, cut at {@code limit} characters of the original. */
    static String shown(String text, int limit) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            if (i == limit) {
                return out.append("...").toString();
            }
            char c = text.charAt(i);
            if (c >= 0x20 && c <= 0x7e) {
                out.append(c);
            } else {
                out.append(String.format("\\u%04x", (int) c));
            }
        }
        return out.toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    /** {@code sha256:} and the SHA-256 of one line per file, {@code <sha256>  <path>}, in the order of the list. */
    static String contentDigest(List<Manifest.FileEntry> files) {
        StringBuilder lines = new StringBuilder();
        files.forEach(f -> lines.append(f.sha256()).append("  ").append(f.path()).append('\n'));
        return "sha256:" + sha256(lines.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The version of this generator, the one the descriptor names too. */
    static String generatorVersion() {
        return ai.generativearchitects.glow400.emit.descriptor.Tool.version();
    }
}
