package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.MockCase;
import ai.generativearchitects.glow400.pcml.api.MockDefinition;
import ai.generativearchitects.glow400.pcml.api.MockFailure;
import ai.generativearchitects.glow400.pcml.api.MockValue;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;

/**
 * Reads one mock file, {@code apis/<FOLDER>/mock/<operation id>.yaml}, into a {@link MockDefinition}. Everything that is wrong
 * with the file as a file, whether it is not YAML, has the wrong shape, has an unknown key or breaks a limit, is
 * {@code MCK001}. The names and values are not looked at here: they are checked against the program where the codec can say
 * whether a field carries them.
 */
final class MockParser {

    /** The largest mock file, in bytes (checked by the loader before the file is read). */
    static final long MAX_BYTES = 1024 * 1024;

    private static final Set<String> TOP_KEYS = Set.of("cases");
    private static final Set<String> CASE_KEYS = Set.of("name", "when", "returns", "delayMs", "fails");
    private static final Map<String, MockFailure> FAILS = YamlReader.choices(List.of("unavailable", "timeout"),
            List.of(MockFailure.UNAVAILABLE, MockFailure.TIMEOUT));

    private final String path;
    private final List<Diagnostic> found = new ArrayList<>();
    private final YamlReader yaml;

    MockParser(String path) {
        this.path = path;
        this.yaml = new YamlReader(path, found);
    }

    /**
     * Parses the text.
     *
     * @param operationId the id of the operation, from the file name
     * @param text        the file
     * @param sink        where the diagnostics ({@code MCK001}) are added
     * @return the definition; empty if the file is not valid
     */
    Optional<MockDefinition> parse(String operationId, String text, List<Diagnostic> sink) {
        Optional<MockDefinition> definition = read(operationId, text);
        for (Diagnostic d : found) {
            sink.add(new Diagnostic("MCK001", Severity.ERROR, d.message(), d.location()));
        }
        return found.isEmpty() ? definition : Optional.empty();
    }

    private Optional<MockDefinition> read(String operationId, String text) {
        YamlReader.Composition composition = yaml.compose(text);
        if (!composition.valid()) {
            return Optional.empty();
        }
        if (composition.root().isEmpty() || GatewayParser.isNull(composition.root().get())) {
            yaml.error("OVL012", "The mock file is empty: it needs 'cases'", yaml.start());
            return Optional.empty();
        }
        Optional<List<YamlReader.Entry>> top = yaml.mapping(composition.root().get(), "the mock file");
        if (top.isEmpty()) {
            return Optional.empty();
        }
        yaml.knownKeys(top.get(), TOP_KEYS, "the mock file");
        Optional<YamlReader.Entry> casesEntry = yaml.find(top.get(), "cases");
        if (casesEntry.isEmpty()) {
            yaml.error("OVL012", "Missing required key 'cases'", yaml.at(composition.root().get()));
            return Optional.empty();
        }
        Optional<List<Node>> items = yaml.sequence(casesEntry.get().value(), "'cases'");
        if (items.isEmpty()) {
            return Optional.empty();
        }
        if (items.get().size() > MockDefinition.MAX_CASES) {
            yaml.error("OVL012", "A mock file has at most " + MockDefinition.MAX_CASES + " cases", casesEntry.get().keyAt());
            return Optional.empty();
        }
        List<MockCase> cases = new ArrayList<>();
        for (Node item : items.get()) {
            mockCase(item).ifPresent(cases::add);
        }
        return Optional.of(new MockDefinition(operationId, cases, new SourceLocation(path, 1, 1)));
    }

    private Optional<MockCase> mockCase(Node node) {
        Optional<List<YamlReader.Entry>> entries = yaml.mapping(node, "a case");
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        yaml.knownKeys(entries.get(), CASE_KEYS, "a case");
        Optional<String> name = yaml.find(entries.get(), "name").flatMap(yaml::nonBlankString).map(Located::value);
        Map<String, MockValue> when = values(yaml.find(entries.get(), "when"), "'when'");
        Map<String, MockValue> returns = values(yaml.find(entries.get(), "returns"), "'returns'");
        Optional<Integer> delay = yaml.find(entries.get(), "delayMs").flatMap(e -> delay(e.value()));
        Optional<MockFailure> fails = yaml.find(entries.get(), "fails").flatMap(e -> yaml.choice(e, FAILS)).map(Located::value);
        if (fails.isPresent() && yaml.find(entries.get(), "returns").isPresent() && !returns.isEmpty()) {
            yaml.error("OVL012", "A case with 'fails' has no 'returns'", yaml.find(entries.get(), "returns").get().keyAt());
        }
        return Optional.of(new MockCase(name, when, returns, delay, fails, yaml.at(node)));
    }

    private Optional<Integer> delay(Node node) {
        Optional<Located<Integer>> number = yaml.integer(node, "'delayMs'");
        if (number.isPresent() && (number.get().value() < 0 || number.get().value() > MockCase.MAX_DELAY_MS)) {
            yaml.error("OVL012", "'delayMs' must be from 0 to " + MockCase.MAX_DELAY_MS + ", not " + number.get().value(), number.get().at());
            return Optional.empty();
        }
        return number.map(Located::value);
    }

    /** The values of a {@code when} or a {@code returns}; empty when the key is absent or has errors. */
    private Map<String, MockValue> values(Optional<YamlReader.Entry> entry, String what) {
        Map<String, MockValue> values = new LinkedHashMap<>();
        if (entry.isEmpty()) {
            return values;
        }
        Optional<List<YamlReader.Entry>> members = yaml.mapping(entry.get().value(), what);
        if (members.isPresent()) {
            for (YamlReader.Entry member : members.get()) {
                value(member.value(), "the value of '" + member.key() + "'").ifPresent(v -> values.put(member.key(), v));
            }
        }
        return values;
    }

    private Optional<MockValue> value(Node node, String what) {
        SourceLocation at = yaml.at(node);
        if (node instanceof ScalarNode scalar) {
            Tag tag = scalar.getTag();
            String text = scalar.getValue();
            if (tag.equals(Tag.STR) || tag.equals(Tag.ENV_TAG)) {
                return Optional.of(new MockValue.Text(text, at));
            }
            if (tag.equals(Tag.BOOL)) {
                return Optional.of(new MockValue.Bool(text.equalsIgnoreCase("true"), at));
            }
            if (tag.equals(Tag.INT)) {
                try {
                    return Optional.of(new MockValue.Whole(new BigInteger(text), at));
                } catch (NumberFormatException e) {
                    yaml.error("OVL012", what + " must be a whole number written in decimal digits", at);
                    return Optional.empty();
                }
            }
            if (tag.equals(Tag.FLOAT)) {
                return Optional.of(new MockValue.Real(text, at));
            }
            yaml.error("OVL012", what + " must not be empty", at);
            return Optional.empty();
        }
        if (node instanceof SequenceNode sequence) {
            List<MockValue> elements = new ArrayList<>();
            boolean ok = true;
            for (Node item : sequence.getValue()) {
                Optional<MockValue> element = value(item, "an element of a list");
                if (element.isPresent()) {
                    elements.add(element.get());
                } else {
                    ok = false;
                }
            }
            return ok ? Optional.of(new MockValue.Array(elements, at)) : Optional.empty();
        }
        Optional<List<YamlReader.Entry>> members = yaml.mapping(node, what);
        if (members.isEmpty()) {
            return Optional.empty();
        }
        Map<String, MockValue> struct = new LinkedHashMap<>();
        boolean ok = true;
        for (YamlReader.Entry member : members.get()) {
            Optional<MockValue> value = value(member.value(), "the value of '" + member.key() + "'");
            if (value.isPresent()) {
                struct.put(member.key(), value.get());
            } else {
                ok = false;
            }
        }
        return ok ? Optional.of(new MockValue.Struct(struct, at)) : Optional.empty();
    }
}
