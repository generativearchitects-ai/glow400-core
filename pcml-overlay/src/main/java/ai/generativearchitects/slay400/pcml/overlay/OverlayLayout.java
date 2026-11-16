package ai.generativearchitects.slay400.pcml.overlay;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.common.ScalarStyle;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Where the parameter entries of an overlay file are in its text: character offsets, for a tool that changes the text
 * in place without writing the file again. It reads only what the editing needs, and says, for every entry or
 * {@code parameters} mapping whose shape is not one it can describe simply, why not. Offsets are positions in the
 * {@link String} (UTF-16 units), not code points.
 *
 * <p>An operation is identified by its line and column, as {@link OperationSettings#source()} reports them, so that it
 * can be matched with what the overlay parser found.
 */
public final class OverlayLayout {

    /** How a parameter entry is written. */
    public enum Shape {
        /** {@code P0X: { name: x }}, the braces and everything between them on one line. */
        FLOW,
        /** {@code P0X:} followed by a block mapping, one key per line. */
        BLOCK,
        /** Anything else; see {@link Entry#why()}. */
        UNSUPPORTED
    }

    /**
     * One key of an entry.
     *
     * @param key        the key
     * @param value      the value as the loader reads it, when it is a text; empty otherwise
     * @param valueStart offset of the first character of the value, quote included
     * @param valueEnd   offset after the last character of the value
     * @param simple     whether the value is a plain or quoted text on one line, with no anchor, so that it can be replaced
     */
    public record Pair(String key, String value, int valueStart, int valueEnd, boolean simple) {
    }

    /**
     * One entry under {@code parameters}.
     *
     * @param shape  how it is written
     * @param why    for {@link Shape#UNSUPPORTED}, a fixed phrase saying what is in the way; empty otherwise
     * @param pairs  the keys of the entry, in file order
     * @param open   for {@link Shape#FLOW}, the offset of {@code {}; otherwise -1
     * @param close  for {@link Shape#FLOW}, the offset of {@code }}; otherwise -1
     * @param indent for {@link Shape#BLOCK}, the number of characters in front of the entry's keys on their line; otherwise -1
     * @param end    for {@link Shape#BLOCK}, the offset after the last character of the entry; otherwise -1
     */
    public record Entry(Shape shape, String why, List<Pair> pairs, int open, int close, int indent, int end) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Entry {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(why, "why");
            pairs = List.copyOf(pairs);
        }
    }

    /**
     * The {@code parameters} of one operation, when it is a block mapping.
     *
     * @param indent  the number of characters in front of the entries' keys on their line
     * @param end     the offset after the last character of the last entry
     * @param entries the entries by parameter name
     */
    public record Parameters(int indent, int end, Map<String, Entry> entries) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Parameters {
            entries = Map.copyOf(entries);
        }
    }

    /**
     * One operation.
     *
     * @param line       line of the operation in the file, 1-based, as {@link OperationSettings#source()}
     * @param column     column, 1-based
     * @param parameters the block {@code parameters} mapping; empty if there is none, or if it is not one the editing can
     *                   append to
     * @param why        when {@code parameters} is empty, a fixed phrase saying why
     */
    public record Operation(int line, int column, Optional<Parameters> parameters, String why) {

        /** @throws NullPointerException if an argument is {@code null} */
        public Operation {
            Objects.requireNonNull(parameters, "parameters");
            Objects.requireNonNull(why, "why");
        }
    }

    private final List<Operation> operations;

    private OverlayLayout(List<Operation> operations) {
        this.operations = List.copyOf(operations);
    }

    /** The operations of the file, in file order. */
    public List<Operation> operations() {
        return operations;
    }

    /** The operation at a position, as reported by {@link OperationSettings#source()}. */
    public Optional<Operation> operationAt(int line, int column) {
        return operations.stream().filter(o -> o.line() == line && o.column() == column).findFirst();
    }

    /**
     * Reads the layout of an overlay text.
     *
     * @return the layout; empty if the text is not YAML or not a mapping at the top
     */
    public static Optional<OverlayLayout> read(String text) {
        Objects.requireNonNull(text, "text");
        Optional<Node> root;
        try {
            root = new Compose(LoadSettings.builder().setAllowDuplicateKeys(false).setUseMarks(true)
                    .setSchema(new CoreSchema()).setMaxAliasesForCollections(50).build()).composeString(text);
        } catch (YamlEngineException | StackOverflowError e) {
            return Optional.empty();
        }
        if (root.isEmpty() || !(root.get() instanceof MappingNode top)) {
            return Optional.empty();
        }
        Reader reader = new Reader(text);
        List<Operation> operations = new ArrayList<>();
        Node operationsNode = value(top, "operations");
        if (operationsNode != null) {
            if (operationsNode instanceof SequenceNode sequence) {
                for (Node item : sequence.getValue()) {
                    if (item instanceof MappingNode mapping) {
                        operations.add(reader.operation(mapping));
                    }
                }
            }
        } else {
            operations.add(reader.operation(top));
        }
        return Optional.of(new OverlayLayout(operations));
    }

    private static Node value(MappingNode mapping, String key) {
        for (NodeTuple tuple : mapping.getValue()) {
            if (tuple.getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equals(key)) {
                return tuple.getValueNode();
            }
        }
        return null;
    }

    private static final class Reader {
        private final String text;
        private final boolean bmp;

        Reader(String text) {
            this.text = text;
            this.bmp = text.length() == text.codePointCount(0, text.length());
        }

        /** Offset of a mark, which counts code points. */
        int at(Optional<Mark> mark) {
            int index = mark.orElseThrow().getIndex();
            return bmp ? index : text.offsetByCodePoints(0, index);
        }

        int start(Node node) {
            return at(node.getStartMark());
        }

        int end(Node node) {
            return at(node.getEndMark());
        }

        int lineStart(int offset) {
            int i = offset;
            while (i > 0 && text.charAt(i - 1) != '\n' && text.charAt(i - 1) != '\r') {
                i--;
            }
            return i;
        }

        Operation operation(MappingNode node) {
            Mark mark = node.getStartMark().orElseThrow();
            int line = mark.getLine() + 1;
            int column = mark.getColumn() + 1;
            Node parameters = value(node, "parameters");
            if (parameters == null) {
                return new Operation(line, column, Optional.empty(), "the operation has no parameters mapping");
            }
            if (!(parameters instanceof MappingNode mapping) || mapping.getFlowStyle() != FlowStyle.BLOCK
                    || mapping.getValue().isEmpty()) {
                return new Operation(line, column, Optional.empty(), "parameters is not a block mapping");
            }
            if (parameters.getAnchor().isPresent()) {
                return new Operation(line, column, Optional.empty(), "parameters has an anchor");
            }
            Map<String, Entry> entries = new LinkedHashMap<>();
            int indent = -1;
            int end = -1;
            for (NodeTuple tuple : mapping.getValue()) {
                ScalarNode key = (ScalarNode) tuple.getKeyNode(); // keys that are not text are refused when composing
                int keyStart = start(key);
                int keyIndent = keyStart - lineStart(keyStart);
                if (indent < 0) {
                    indent = keyIndent;
                } else if (indent != keyIndent) {
                    return new Operation(line, column, Optional.empty(), "the entries of parameters are not aligned");
                }
                int entryEnd = lastEnd(tuple);
                if (entryEnd < 0) {
                    return new Operation(line, column, Optional.empty(), "an entry of parameters ends in a block scalar");
                }
                end = entryEnd;
                entries.put(key.getValue(), entry(tuple, key, keyStart, entryEnd));
            }
            return new Operation(line, column, Optional.of(new Parameters(indent, end, entries)), "");
        }

        Entry entry(NodeTuple tuple, ScalarNode key, int keyStart, int entryEnd) {
            Node value = tuple.getValueNode();
            if (anchored(value) || key.getAnchor().isPresent()) {
                return unsupported("anchor or alias");
            }
            if (text.substring(lineStart(keyStart), entryEnd).contains("\t")) {
                return unsupported("tab character");
            }
            if (!(value instanceof MappingNode mapping)) {
                return unsupported(value instanceof ScalarNode s && s.getTag().equals(Tag.NULL)
                        ? "entry without a value" : "the entry is not a mapping");
            }
            List<Pair> pairs = new ArrayList<>();
            for (NodeTuple pair : mapping.getValue()) {
                ScalarNode pairKey = (ScalarNode) pair.getKeyNode();
                Node v = pair.getValueNode();
                int vs = start(v);
                int ve = end(v);
                boolean simple = v instanceof ScalarNode s && !s.getTag().equals(Tag.NULL) && vs < ve
                        && (s.getScalarStyle() == ScalarStyle.PLAIN || s.getScalarStyle() == ScalarStyle.SINGLE_QUOTED
                        || s.getScalarStyle() == ScalarStyle.DOUBLE_QUOTED)
                        && !oneLine(vs, ve).equals("");
                pairs.add(new Pair(pairKey.getValue(), v instanceof ScalarNode scalar ? scalar.getValue() : "", vs, ve, simple));
            }
            if (mapping.getFlowStyle() == FlowStyle.FLOW) {
                int open = start(mapping);
                int close = end(mapping) - 1;
                if (text.charAt(open) != '{' || text.charAt(close) != '}') {
                    return unsupported("a tag on the mapping");
                }
                if (!oneLine(open, close + 1).equals(text.substring(open, close + 1))) {
                    return unsupported("flow mapping over several lines");
                }
                if (lineStart(open) != lineStart(keyStart)) {
                    return unsupported("flow mapping not on the line of its key");
                }
                return new Entry(Shape.FLOW, "", pairs, open, close, -1, -1);
            }
            if (mapping.getValue().isEmpty()) {
                return unsupported("entry without a value");
            }
            int first = start(mapping.getValue().get(0).getKeyNode());
            return new Entry(Shape.BLOCK, "", pairs, -1, -1, first - lineStart(first), entryEnd);
        }

        private String oneLine(int from, int to) {
            String part = text.substring(from, to);
            return part.indexOf('\n') >= 0 || part.indexOf('\r') >= 0 ? "" : part;
        }

        private static Entry unsupported(String why) {
            return new Entry(Shape.UNSUPPORTED, why, List.of(), -1, -1, -1, -1);
        }

        /** Whether the node or anything below it has an anchor, which also covers every node an alias refers to. */
        private static boolean anchored(Node node) {
            if (node.getAnchor().isPresent()) {
                return true;
            }
            if (node instanceof MappingNode mapping) {
                for (NodeTuple t : mapping.getValue()) {
                    if (anchored(t.getKeyNode()) || anchored(t.getValueNode())) {
                        return true;
                    }
                }
            } else if (node instanceof SequenceNode sequence) {
                for (Node item : sequence.getValue()) {
                    if (anchored(item)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Offset after the last character of an entry; -1 if it ends in a block scalar. */
        private int lastEnd(NodeTuple tuple) {
            int keyEnd = end(tuple.getKeyNode());
            int valueEnd = lastEnd(tuple.getValueNode());
            if (valueEnd < 0) {
                return -1;
            }
            return Math.max(keyEnd, valueEnd);
        }

        private int lastEnd(Node node) {
            if (node instanceof ScalarNode scalar) {
                if (scalar.getScalarStyle() == ScalarStyle.LITERAL || scalar.getScalarStyle() == ScalarStyle.FOLDED) {
                    return -1;
                }
                return scalar.getValue().isEmpty() && scalar.getScalarStyle() == ScalarStyle.PLAIN ? 0 : end(node);
            }
            if (node instanceof MappingNode mapping) {
                if (mapping.getFlowStyle() == FlowStyle.FLOW || mapping.getValue().isEmpty()) {
                    return end(node);
                }
                return lastEnd(mapping.getValue().get(mapping.getValue().size() - 1));
            }
            if (node instanceof SequenceNode sequence) {
                if (sequence.getFlowStyle() == FlowStyle.FLOW || sequence.getValue().isEmpty()) {
                    return end(node);
                }
                return lastEnd(sequence.getValue().get(sequence.getValue().size() - 1));
            }
            return end(node);
        }
    }
}
