package ai.generativearchitects.glow400.pcml.codec;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Whether a character is one byte in a CCSID. */
class ByteWidthTest {

    @Test
    void unicodeCcsidsAreNotSingleByte() {
        for (int ccsid : new int[] {1200, 1201, 1202, 1203, 1204, 1205, 1208, 1232, 1234, 13488, 17584, 61952}) {
            assertThat(ByteWidth.isSingleByte(ccsid)).as("CCSID " + ccsid).isFalse();
        }
    }

    @Test
    void mixedAndDoubleByteCcsidsAreNotSingleByte() {
        for (int ccsid : new int[] {930, 933, 935, 937, 939, 300, 835, 837, 1364, 5026, 5035}) {
            assertThat(ByteWidth.isSingleByte(ccsid)).as("CCSID " + ccsid).isFalse();
        }
    }

    @Test
    void commonSingleByteCcsidsAre() {
        for (int ccsid : new int[] {37, 273, 277, 278, 280, 284, 285, 297, 500, 871, 1140, 1141, 1148, 1047, 437, 850}) {
            assertThat(ByteWidth.isSingleByte(ccsid)).as("CCSID " + ccsid).isTrue();
        }
    }

    @Test
    void aCcsidTheJdkDoesNotKnowAndNoConversionAreTakenAsSingleByte() {
        for (int ccsid : new int[] {65535, 0, -1, 99999, 12345}) {
            assertThat(ByteWidth.isSingleByte(ccsid)).as("CCSID " + ccsid).isTrue();
        }
    }
}
