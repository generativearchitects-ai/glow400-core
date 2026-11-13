package ai.generativearchitects.glow400.pcml.codec;

import java.nio.charset.Charset;
import java.util.Set;

/**
 * Whether one character is one byte in a CCSID. A char field's {@code length} counts bytes of the host encoding, so the
 * codec, which does no byte conversion, can only count characters correctly where a character is a byte.
 *
 * <p>Unicode CCSIDs and well-known double-byte and mixed-byte CCSIDs are listed. For the others the JDK's own code page
 * {@code Cp<ccsid>} decides: if it needs more than one byte for some character the CCSID is multi-byte. A CCSID that is
 * neither listed nor known to the JDK, and 65535 ("no conversion"), is taken as single-byte.
 */
final class ByteWidth {

    /** Unicode and UTF CCSIDs: UTF-16 and UCS-2 in several forms, UTF-8 and UTF-32. */
    private static final Set<Integer> UNICODE = Set.of(1200, 1201, 1202, 1203, 1204, 1205, 1208, 1232, 1234, 13488, 17584, 61952);

    /**
     * Well-known double-byte and mixed-byte CCSIDs (Japanese, Korean and Chinese), whether or not the JDK has a code page
     * for them. The list is not complete: a CCSID that is neither listed nor known to the JDK is taken as single-byte.
     */
    private static final Set<Integer> MULTI_BYTE = Set.of(300, 301, 835, 837, 930, 931, 932, 933, 935, 936, 937, 938, 939, 941, 942,
            943, 947, 948, 949, 950, 951, 1363, 1364, 1371, 1381, 1386, 1388, 1390, 1392, 1399, 4396, 5026, 5035, 5039, 5050, 5123,
            16684, 33722);

    private ByteWidth() {
    }

    /** Whether every character of the CCSID takes exactly one byte, as far as is known. */
    static boolean isSingleByte(int ccsid) {
        if (UNICODE.contains(ccsid) || MULTI_BYTE.contains(ccsid)) {
            return false;
        }
        try {
            return Charset.forName("Cp" + ccsid).newEncoder().maxBytesPerChar() <= 1.0f;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }
}
