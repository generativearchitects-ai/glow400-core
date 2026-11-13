package ai.generativearchitects.glow400.diff;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.codec.Gen;
import ai.generativearchitects.glow400.pcml.codec.Properties;
import ai.generativearchitects.glow400.pcml.overlay.OverlayLoader;
import ai.generativearchitects.glow400.pcml.overlay.RepositoryResult;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Properties of the comparison, on repositories made from a random description: comparing a repository with itself gives
 * {@code NONE}, and applying a random sequence of compatible edits never gives {@code BREAKING}.
 */
class CompatibleEditsPropertyTest {

    /** A field of a program: input or output, required or not, with its length. */
    private static final class FieldSpec {
        final boolean response;
        boolean required;
        int length;

        FieldSpec(boolean response, boolean required, int length) {
            this.response = response;
            this.required = required;
            this.length = length;
        }

        /** The same field, not required. */
        FieldSpec optional() {
            this.required = false;
            return this;
        }
    }

    private static final class Spec {
        final List<List<FieldSpec>> operations = new ArrayList<>();
        String summary = "summary";
        int version = 0;

        Spec copy() {
            Spec copy = new Spec();
            operations.forEach(fields -> {
                List<FieldSpec> list = new ArrayList<>();
                fields.forEach(f -> list.add(new FieldSpec(f.response, f.required, f.length)));
                copy.operations.add(list);
            });
            copy.summary = summary;
            return copy;
        }
    }

    private static FieldSpec randomField(Gen g, boolean response) {
        return new FieldSpec(response, !response && g.bool(), g.intIn(1, 8));
    }

    private static Spec randomSpec(Gen g) {
        Spec spec = new Spec();
        int operations = g.intIn(1, 3);
        for (int i = 0; i < operations; i++) {
            List<FieldSpec> fields = new ArrayList<>();
            int count = g.intIn(1, 4);
            for (int f = 0; f < count; f++) {
                fields.add(randomField(g, g.bool()));
            }
            spec.operations.add(fields);
        }
        return spec;
    }

    /** One compatible edit: an optional request field, a response field, a longer request field, a field made optional, text, an operation. */
    private static void edit(Gen g, Spec spec) {
        List<FieldSpec> fields = spec.operations.get(g.intIn(0, spec.operations.size() - 1));
        switch (g.intIn(0, 5)) {
            case 0 -> fields.add(randomField(g, false).optional());
            case 1 -> fields.add(randomField(g, true));
            case 2 -> fields.stream().filter(f -> !f.response).findFirst().ifPresent(f -> f.length += g.intIn(1, 5));
            case 3 -> fields.stream().filter(f -> f.required).findFirst().ifPresent(f -> f.required = false);
            case 4 -> spec.summary = spec.summary + " more";
            default -> spec.operations.add(new ArrayList<>(List.of(randomField(g, false).optional(), randomField(g, true))));
        }
    }

    private static String pcml(Spec spec) {
        StringBuilder out = new StringBuilder("<?xml version=\"1.0\"?>\n<pcml version=\"6.0\">\n");
        for (int p = 0; p < spec.operations.size(); p++) {
            out.append("  <program name=\"P").append(p).append("\" path=\"/QSYS.LIB/DEMOLIB.LIB/P").append(p).append(".PGM\">\n");
            List<FieldSpec> fields = spec.operations.get(p);
            for (int f = 0; f < fields.size(); f++) {
                out.append("    <data name=\"F").append(f).append("\" type=\"char\" length=\"").append(fields.get(f).length).append("\" usage=\"")
                        .append(fields.get(f).response ? "output" : "input").append("\"/>\n");
            }
            out.append("  </program>\n");
        }
        return out.append("</pcml>\n").toString();
    }

    private static String overlay(Spec spec) {
        StringBuilder out = new StringBuilder("pcml: T.pcml\nversion: 1.0.0\noperations:\n");
        for (int p = 0; p < spec.operations.size(); p++) {
            out.append("  - id: o").append(p).append("\n    program: P").append(p).append("\n    route: /o").append(p).append("\n    method: POST\n")
                    .append("    summary: ").append(p == 0 ? spec.summary : "other").append("\n    parameters:\n");
            List<FieldSpec> fields = spec.operations.get(p);
            for (int f = 0; f < fields.size(); f++) {
                out.append("      F").append(f).append(": { name: f").append(f).append(", required: ").append(fields.get(f).required).append(" }\n");
            }
        }
        return out.toString();
    }

    private static ApiRepositoryModel load(Spec spec) throws IOException {
        Path dir = Files.createTempDirectory("glow400-diff");
        try {
            Path api = Files.createDirectories(dir.resolve("apis").resolve("T"));
            Files.writeString(api.resolve("T.pcml"), pcml(spec));
            Files.writeString(api.resolve("api.yaml"), overlay(spec));
            RepositoryResult result = OverlayLoader.loadRepository(dir);
            assertThat(result.diagnostics()).isEmpty();
            return result.model().orElseThrow();
        } finally {
            try (Stream<Path> paths = Files.walk(dir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private static void run(String name, java.util.function.Consumer<Gen> property) {
        long seed = Long.getLong(Properties.SEED_PROPERTY, new java.util.Random().nextLong());
        Properties.check(name, seed, Integer.getInteger(Properties.CASES_PROPERTY, 40), property);
    }

    @Test
    void aRepositoryComparedWithItselfIsNone() {
        run("diff with itself", g -> {
            try {
                ApiRepositoryModel model = load(randomSpec(g));
                DiffResult result = ApiDiff.compare(model, model);
                assertThat(result.apis().get(0).impact()).isEqualTo(Impact.NONE);
                assertThat(result.apis().get(0).changes()).isEmpty();
                assertThat(result.diagnostics()).isEmpty();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Test
    void aSequenceOfCompatibleEditsNeverGivesBreaking() {
        run("compatible edits", g -> {
            try {
                Spec original = randomSpec(g);
                Spec edited = original.copy();
                int edits = g.intIn(1, 6);
                for (int i = 0; i < edits; i++) {
                    edit(g, edited);
                }
                DiffResult result = ApiDiff.compare(load(original), load(edited));
                assertThat(result.apis().get(0).changes()).extracting(Change::impact).doesNotContain(Impact.BREAKING);
                assertThat(result.apis().get(0).impact()).isLessThanOrEqualTo(Impact.COMPATIBLE);
                assertThat(result.diagnostics()).extracting(d -> d.code()).doesNotContain("API002");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    @Test
    void theReverseOfACompatibleEditIsBreakingWhenSomethingWasAdded() {
        run("reverse", g -> {
            try {
                Spec original = randomSpec(g);
                Spec edited = original.copy();
                edited.operations.add(new ArrayList<>(List.of(randomField(g, true))));
                DiffResult forward = ApiDiff.compare(load(original), load(edited));
                DiffResult back = ApiDiff.compare(load(edited), load(original));
                assertThat(forward.apis().get(0).impact()).isEqualTo(Impact.COMPATIBLE);
                assertThat(back.apis().get(0).impact()).isEqualTo(Impact.BREAKING);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
