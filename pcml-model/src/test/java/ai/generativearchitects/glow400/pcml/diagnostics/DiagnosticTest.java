package ai.generativearchitects.glow400.pcml.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import org.junit.jupiter.api.Test;

class DiagnosticTest {

    private static final SourceLocation AT = new SourceLocation("api.yaml", 3, 7);

    @Test
    void textContainsLocationSeverityCodeAndMessage() {
        assertThat(new Diagnostic("OVL012", Severity.ERROR, "bad value", AT))
                .hasToString("api.yaml:3:7: error OVL012: bad value");
        assertThat(new Diagnostic("OVL001", Severity.WARNING, "w", AT)).hasToString("api.yaml:3:7: warning OVL001: w");
        assertThat(new Diagnostic("PCML007", Severity.INFO, "i", AT)).hasToString("api.yaml:3:7: info PCML007: i");
    }

    @Test
    void componentsAreKept() {
        Diagnostic d = new Diagnostic("API001", Severity.ERROR, "m", AT);
        assertThat(d.code()).isEqualTo("API001");
        assertThat(d.severity()).isEqualTo(Severity.ERROR);
        assertThat(d.message()).isEqualTo("m");
        assertThat(d.location()).isEqualTo(AT);
    }

    @Test
    void nullsAndBlankCodeAreRejected() {
        assertThatThrownBy(() -> new Diagnostic(null, Severity.INFO, "m", AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Diagnostic(" ", Severity.INFO, "m", AT)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Diagnostic("X", null, "m", AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Diagnostic("X", Severity.INFO, null, AT)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Diagnostic("X", Severity.INFO, "m", null)).isInstanceOf(NullPointerException.class);
    }
}
