package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Reads one YAML file into nodes that know their line and column, and offers typed access that reports
 * {@code OVL010} (not valid YAML), {@code OVL011} (unknown key) and {@code OVL012} (missing key or invalid value)
 * at the right position.
 *
 * <p>The file is only composed into nodes: no objects are constructed, so tags cannot select arbitrary types.
 * Tags outside the YAML core schema, recursive aliases, duplicate keys, more than 50 aliases for collections and
 * very deep nesting are refused.
 */
final class YamlReader {

    /** A key of a mapping with the node of its value. */
    record Entry(String key, SourceLocation keyAt, Node value) {
    }

    /**
     * Result of {@link #compose}.
     *
     * @param valid whether the text is usable YAML; if not, {@code OVL010} has been reported
     * @param root  the root node; empty for an empty document
     */
    record Composition(boolean valid, Optional<Node> root) {
    }

    private static final int MAX_DEPTH = 64;
    /**
     * The YAML core tags, plus the tag the engine gives to values such as {@code ${NAME}}. That tag only matters when
     * objects are constructed (the engine would then substitute an environment variable); here such a value is just text.
     */
    private static final Set<Tag> CORE_TAGS = Set.of(Tag.STR, Tag.INT, Tag.FLOAT, Tag.BOOL, Tag.NULL, Tag.SEQ, Tag.MAP,
            Tag.ENV_TAG);

    private final String file;
    private final List<Diagnostic> sink;
    private int errors;

    YamlReader(String file, List<Diagnostic> sink) {
        this.file = file;
        this.sink = sink;
    }

    /** Number of errors reported through this reader so far. */
    int errorCount() {
        return errors;
    }

    SourceLocation start() {
        return new SourceLocation(file, 1, 1);
    }

    SourceLocation at(Node node) {
        Optional<Mark> mark = node.getStartMark();
        return mark.map(m -> new SourceLocation(file, m.getLine() + 1, m.getColumn() + 1)).orElseGet(this::start);
    }

    void report(String code, Severity severity, String message, SourceLocation at) {
        if (severity == Severity.ERROR) {
            errors++;
        }
        sink.add(new Diagnostic(code, severity, message, at));
    }

    void error(String code, String message, SourceLocation at) {
        report(code, Severity.ERROR, message, at);
    }

    // ------------------------------------------------------------------ composing

    /** Composes the text into its root node, reporting {@code OVL010} if it cannot be used. */
    Composition compose(String text) {
        LoadSettings settings = LoadSettings.builder()
                .setAllowDuplicateKeys(false)
                .setAllowRecursiveKeys(false)
                .setAllowNonScalarKeys(false)
                .setMaxAliasesForCollections(50)
                .setUseMarks(true)
                .setSchema(new CoreSchema())
                .setLabel(file)
                .build();
        Optional<Node> root;
        try {
            root = new Compose(settings).composeString(text);
        } catch (MarkedYamlEngineException e) {
            SourceLocation at = e.getProblemMark().or(e::getContextMark)
                    .map(m -> new SourceLocation(file, m.getLine() + 1, m.getColumn() + 1))
                    .orElseGet(this::start);
            error("OVL010", "Not valid YAML: " + e.getProblem(), at);
            return new Composition(false, Optional.empty());
        } catch (YamlEngineException e) {
            error("OVL010", "Not valid YAML: " + e.getMessage(), start());
            return new Composition(false, Optional.empty());
        } catch (StackOverflowError e) {
            error("OVL010", "Not valid YAML: nested too deeply", start());
            return new Composition(false, Optional.empty());
        }
        if (root.isPresent() && !checkTree(root.get(), 0, Collections.newSetFromMap(new IdentityHashMap<>()))) {
            return new Composition(false, Optional.empty());
        }
        return new Composition(true, root);
    }

    /** Refuses custom tags, recursion through aliases and excessive nesting. */
    private boolean checkTree(Node node, int depth, Set<Node> path) {
        if (depth > MAX_DEPTH) {
            error("OVL010", "Not valid YAML: nested more than " + MAX_DEPTH + " levels deep", at(node));
            return false;
        }
        if (!CORE_TAGS.contains(node.getTag())) {
            error("OVL010", "Not valid YAML for this tool: tag " + node.getTag().getValue() + " is not allowed", at(node));
            return false;
        }
        if (!path.add(node)) {
            error("OVL010", "Not valid YAML: an alias refers to a node that contains it", at(node));
            return false;
        }
        boolean ok = true;
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                ok = ok && checkTree(tuple.getKeyNode(), depth + 1, path) && checkTree(tuple.getValueNode(), depth + 1, path);
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                ok = ok && checkTree(item, depth + 1, path);
            }
        }
        path.remove(node);
        return ok;
    }

    // ------------------------------------------------------------------ typed access

    /** The entries of a mapping; reports {@code OVL012} and returns empty if the node is not a mapping. */
    Optional<List<Entry>> mapping(Node node, String what) {
        if (!(node instanceof MappingNode mapping)) {
            error("OVL012", what + " must be a mapping", at(node));
            return Optional.empty();
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        boolean ok = true;
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode key) {
                // Composing does not check for duplicate keys (only constructing does), so it is done here.
                if (!seen.add(key.getValue())) {
                    error("OVL010", "Not valid YAML for this tool: duplicate key '" + key.getValue() + "' in " + what, at(key));
                }
                entries.add(new Entry(key.getValue(), at(key), tuple.getValueNode()));
            } else {
                error("OVL012", "Keys of " + what + " must be plain text", at(tuple.getKeyNode()));
                ok = false;
            }
        }
        return ok ? Optional.of(entries) : Optional.empty();
    }

    /** Reports {@code OVL011} for every key that is not in {@code allowed}; returns whether all keys are known. */
    boolean knownKeys(List<Entry> entries, Set<String> allowed, String what) {
        boolean ok = true;
        for (Entry entry : entries) {
            if (!allowed.contains(entry.key())) {
                error("OVL011", "Unknown key '" + entry.key() + "' in " + what + "; allowed keys: "
                        + allowed.stream().sorted().collect(Collectors.joining(", ")), entry.keyAt());
                ok = false;
            }
        }
        return ok;
    }

    Optional<Entry> find(List<Entry> entries, String key) {
        return entries.stream().filter(e -> e.key().equals(key)).findFirst();
    }

    Optional<Located<String>> string(Entry entry) {
        if (entry.value() instanceof ScalarNode scalar
                && (scalar.getTag().equals(Tag.STR) || scalar.getTag().equals(Tag.ENV_TAG))) {
            return Optional.of(new Located<>(scalar.getValue(), at(scalar)));
        }
        error("OVL012", "'" + entry.key() + "' must be a string", at(entry.value()));
        return Optional.empty();
    }

    Optional<Located<String>> nonBlankString(Entry entry) {
        Optional<Located<String>> value = string(entry);
        if (value.isPresent() && value.get().value().isBlank()) {
            error("OVL012", "'" + entry.key() + "' must not be empty", value.get().at());
            return Optional.empty();
        }
        return value;
    }

    /** Any plain value (text, number or boolean), kept as written. Null and collections are refused. */
    Optional<Located<String>> scalarText(Node node, String what) {
        if (node instanceof ScalarNode scalar && !scalar.getTag().equals(Tag.NULL)) {
            return Optional.of(new Located<>(scalar.getValue(), at(scalar)));
        }
        error("OVL012", what + " must be a text, number or boolean", at(node));
        return Optional.empty();
    }

    Optional<Located<Boolean>> bool(Entry entry) {
        if (entry.value() instanceof ScalarNode scalar && scalar.getTag().equals(Tag.BOOL)) {
            return Optional.of(new Located<>(scalar.getValue().equalsIgnoreCase("true"), at(scalar)));
        }
        error("OVL012", "'" + entry.key() + "' must be true or false", at(entry.value()));
        return Optional.empty();
    }

    Optional<Located<Integer>> integer(Node node, String what) {
        if (node instanceof ScalarNode scalar && scalar.getTag().equals(Tag.INT)) {
            try {
                return Optional.of(new Located<>(Integer.parseInt(scalar.getValue()), at(scalar)));
            } catch (NumberFormatException e) {
                // falls through to the error below
            }
        }
        error("OVL012", what + " must be a whole number", at(node));
        return Optional.empty();
    }

    <T> Optional<Located<T>> choice(Entry entry, Map<String, T> choices) {
        Optional<Located<String>> text = string(entry);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        T value = choices.get(text.get().value());
        if (value == null) {
            error("OVL012", "'" + entry.key() + "' must be one of " + String.join(", ", choices.keySet())
                    + ", not '" + text.get().value() + "'", text.get().at());
            return Optional.empty();
        }
        return Optional.of(new Located<>(value, text.get().at()));
    }

    Optional<List<Node>> sequence(Node node, String what) {
        if (node instanceof SequenceNode sequence) {
            return Optional.of(sequence.getValue());
        }
        error("OVL012", what + " must be a list", at(node));
        return Optional.empty();
    }

    /** An ordered map from the names used in YAML to the values they stand for, for {@link #choice}. */
    static <T> Map<String, T> choices(List<String> names, List<T> values) {
        Map<String, T> map = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            map.put(names.get(i), values.get(i));
        }
        return map;
    }
}
