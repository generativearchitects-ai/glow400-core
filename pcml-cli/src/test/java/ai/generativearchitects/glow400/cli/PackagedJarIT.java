package ai.generativearchitects.glow400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.emit.descriptor.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The packaged jar, started with {@code java -jar}, in the verify phase. */
class PackagedJarIT {

    private static final Path JAR = Path.of(System.getProperty("cli.jar"));

    private record Run(int exit, String out, String err) {
    }

    private static Run java(Path workingDirectory, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", JAR.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
        process.getOutputStream().close();
        byte[] out = process.getInputStream().readAllBytes();
        byte[] err = process.getErrorStream().readAllBytes();
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new Run(process.exitValue(), new String(out, StandardCharsets.UTF_8), new String(err, StandardCharsets.UTF_8));
    }

    @Test
    void theJarPrintsItsVersion(@TempDir Path dir) throws Exception {
        Run run = java(dir, "--version");
        assertThat(run.exit()).isZero();
        assertThat(run.out()).isEqualTo(Tool.NAME + " " + Tool.version() + "\n");
        assertThat(run.err()).isEmpty();
    }

    @Test
    void theJarValidatesAndGeneratesWithItsEmittersFoundInsideIt(@TempDir Path dir) throws Exception {
        Support.repo("minimal", dir.resolve("repo"));
        Run validate = java(dir, "validate", "--repo", "repo", "--format", "json");
        assertThat(validate.exit()).isZero();
        assertThat(validate.out()).contains("\"command\": \"validate\"");
        Run generate = java(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(generate.exit()).isZero();
        assertThat(Support.names(dir.resolve("out"))).contains("descriptor.json", "openapi/ordstatus.yaml");
        Run usage = java(dir, "frobnicate");
        assertThat(usage.exit()).isEqualTo(2);
    }

    @Test
    void theJarCarriesTheLicenceAndTheNoticesOfWhatItBundlesAndOnlyTheRealEmitters() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            String license = new String(zip.getInputStream(zip.getEntry("META-INF/LICENSE")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(license).contains("Apache License").contains("Version 2.0, January 2004");
            String notice = new String(zip.getInputStream(zip.getEntry("META-INF/NOTICE")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(notice).contains("Jackson");
            String own = new String(zip.getInputStream(zip.getEntry("META-INF/NOTICE-glow400-core")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(own).startsWith("glow400-core\nCopyright 2026 The glow400-core authors");
            assertThat(zip.stream().map(ZipEntry::getName)).contains("META-INF/FastDoubleParser-LICENSE", "META-INF/maven/org.snakeyaml/snakeyaml-engine/pom.properties");
            String emitters = new String(zip.getInputStream(zip.getEntry("META-INF/services/ai.generativearchitects.glow400.pcml.api.Emitter")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(emitters).contains("DescriptorEmitter").contains("OpenApiEmitter").doesNotContain("TestEmitters");
        }
    }
}
