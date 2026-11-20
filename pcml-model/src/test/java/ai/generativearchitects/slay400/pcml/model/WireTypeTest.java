package ai.generativearchitects.slay400.pcml.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@link WireType#isRawBytes}: CCSID 65535 only, and only when present (kickoff 15, Part A). */
class WireTypeTest {

    @Test
    void onlyCcsid65535IsRawBytes() {
        assertThat(WireType.isRawBytes(Optional.of(65535))).isTrue();
        assertThat(WireType.isRawBytes(Optional.of(37))).isFalse();
        assertThat(WireType.isRawBytes(Optional.of(65534))).isFalse();
        assertThat(WireType.isRawBytes(Optional.empty())).isFalse();
    }
}
