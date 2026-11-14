package ai.generativearchitects.glow400.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.codec.Gen;
import ai.generativearchitects.glow400.pcml.codec.Properties;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** For any valid repository and any valid name and version, writing then reading succeeds and keeps the content digest. */
class BundlePropertyTest {

    @TempDir
    static Path dir;

    private static final String NAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String VERSION_ALPHABET = "abcXYZ0123456789.-_+";

    @Test
    void writeThenReadAlwaysSucceedsAndPreservesTheContentDigest() {
        Map<String, Path> roots = new HashMap<>();
        Properties.check("bundle round trip", Long.getLong(Properties.SEED_PROPERTY, new java.util.Random().nextLong()),
                Integer.getInteger(Properties.CASES_PROPERTY, 60), g -> {
                    String fixture = g.pick(Support.FIXTURES);
                    Path root = roots.computeIfAbsent(fixture, f -> Support.copy(f, dir.resolve(f)));
                    String name = g.text(NAME_ALPHABET, 1, 1) + g.text(NAME_ALPHABET + "._-", 0, 20);
                    String version = g.text(VERSION_ALPHABET, 1, BundleInfo.MAX_VERSION_LENGTH);
                    Optional<String> repository = g.bool() ? Optional.of(g.text(Gen.LETTERS, 1, 20)) : Optional.empty();
                    Optional<String> revision = g.bool() ? Optional.of(g.text("0123456789abcdef", 7, 40)) : Optional.empty();
                    BundleInfo info = new BundleInfo(name, version, repository, revision);
                    ApiRepositoryModel model = Support.model(root);
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    BundleResult written = BundleWriter.write(root, model, Support.emitted(model), info, out);
                    assertThat(written.diagnostics()).isEmpty();
                    BundleReadResult read = BundleReader.read(Support.file(dir.resolve("out"), out.toByteArray()));
                    assertThat(read.diagnostics()).isEmpty();
                    Manifest manifest = read.bundle().orElseThrow().manifest();
                    assertThat(manifest.contentDigest()).isEqualTo(written.manifest().orElseThrow().contentDigest());
                    assertThat(manifest.name()).isEqualTo(name);
                    assertThat(manifest.version()).isEqualTo(version);
                    assertThat(manifest.repository()).isEqualTo(repository);
                    assertThat(manifest.revision()).isEqualTo(revision);
                });
    }

    @Test
    void theContentDigestDoesNotDependOnTheNameOrTheVersion() {
        Path root = Support.copy("minimal", dir.resolve("m"));
        ApiRepositoryModel model = Support.model(root);
        String a = BundleWriter.write(root, model, Support.emitted(model), new BundleInfo("one", "1", Optional.empty(), Optional.empty()),
                new ByteArrayOutputStream()).manifest().orElseThrow().contentDigest();
        String b = BundleWriter.write(root, model, Support.emitted(model), new BundleInfo("two", "2", Optional.of("r"), Optional.of("v")),
                new ByteArrayOutputStream()).manifest().orElseThrow().contentDigest();
        assertThat(a).isEqualTo(b);
    }
}
