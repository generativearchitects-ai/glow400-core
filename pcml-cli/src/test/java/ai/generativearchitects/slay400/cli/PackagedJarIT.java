package ai.generativearchitects.slay400.cli;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.emit.descriptor.Tool;
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
        return javaWithInput(workingDirectory, "", args);
    }

    private static Run javaWithInput(Path workingDirectory, String input, String... args) throws IOException, InterruptedException {
        List<String> command = new java.util.ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar", JAR.toString()));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(workingDirectory.toFile()).start();
        process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
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
    void theJarValidatesGeneratesAndBundlesAMockedRepository(@TempDir Path dir) throws Exception {
        Support.repo("mocked", dir.resolve("repo"));
        assertThat(java(dir, "validate", "--repo", "repo").exit()).isZero();
        Run generate = java(dir, "generate", "--repo", "repo", "--out", "out");
        assertThat(generate.exit()).isZero();
        assertThat(Support.names(dir.resolve("out"))).contains("mock/orders.json", "descriptor.json", "openapi/orders.yaml");
        Run bundle = java(dir, "bundle", "--repo", "repo", "--name", "orders", "--version", "1.0.0", "--out", "orders.rpgapi");
        assertThat(bundle.exit()).isZero();
        try (ZipFile zip = new ZipFile(dir.resolve("orders.rpgapi").toFile())) {
            assertThat(zip.stream().map(ZipEntry::getName)).contains("mock/orders.json", "apis/ORDERS/mock/ordstatus.yaml");
        }
    }

    @Test
    void theJarTakesAFolderOfPcmlToABundleWithInitAndAnnotate(@TempDir Path dir) throws Exception {
        ScaffoldFixtures.pcmlOnly(dir.resolve("repo"));
        Run init = java(dir, "init", "--repo", "repo");
        assertThat(init.exit()).isZero();
        assertThat(init.err()).contains("created repo/gateway.yaml").contains("found 2 PCML files, created 2 api.yaml");
        Run failing = java(dir, "validate", "--repo", "repo");
        assertThat(failing.exit()).isEqualTo(1);
        assertThat(failing.out()).contains("error OVL001");
        Run annotate = javaWithInput(dir, "request\n\nboth\n\nrequest\n\n\nresponse\n\n", "annotate", "--repo", "repo");
        assertThat(annotate.exit()).isZero();
        assertThat(annotate.err()).contains("GETBAL.DET  inputoutput -> request").contains("ORDSTATUS.P0QTY  inputoutput -> response");
        assertThat(java(dir, "validate", "--repo", "repo").exit()).isZero();
        Run bundle = java(dir, "bundle", "--repo", "repo", "--name", "orders", "--version", "1.0.0", "--out", "orders.rpgapi");
        assertThat(bundle.exit()).isZero();
        assertThat(dir.resolve("orders.rpgapi")).isRegularFile();
        assertThat(javaWithInput(dir, "", "annotate", "--repo", "repo").err()).isEqualTo("nothing to annotate\n");
    }

    @Test
    void theJarCarriesTheLicenceAndTheNoticesOfWhatItBundlesAndOnlyTheRealEmitters() throws IOException {
        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            String license = new String(zip.getInputStream(zip.getEntry("META-INF/LICENSE")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(license).contains("Apache License").contains("Version 2.0, January 2004");
            String notice = new String(zip.getInputStream(zip.getEntry("META-INF/NOTICE")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(notice).contains("Jackson");
            String own = new String(zip.getInputStream(zip.getEntry("META-INF/NOTICE-slay400-core")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(own).startsWith("slay400-core\nCopyright 2026 The slay400-core authors");
            assertThat(zip.stream().map(ZipEntry::getName)).contains("META-INF/FastDoubleParser-LICENSE", "META-INF/maven/org.snakeyaml/snakeyaml-engine/pom.properties");
            String emitters = new String(zip.getInputStream(zip.getEntry("META-INF/services/ai.generativearchitects.slay400.pcml.api.Emitter")).readAllBytes(), StandardCharsets.UTF_8);
            assertThat(emitters).contains("DescriptorEmitter").contains("OpenApiEmitter").contains("MockEmitter").doesNotContain("TestEmitters");
        }
    }
}
