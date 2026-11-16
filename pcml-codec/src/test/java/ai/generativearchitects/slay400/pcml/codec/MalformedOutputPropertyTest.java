package ai.generativearchitects.slay400.pcml.codec;

import static ai.generativearchitects.slay400.pcml.codec.Ops.chr;
import static ai.generativearchitects.slay400.pcml.codec.Ops.field;
import static ai.generativearchitects.slay400.pcml.codec.Ops.int4;
import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.slay400.pcml.model.WireType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * For random operations, valid output decodes without a problem, and output made wrong in one place, at any depth, never
 * makes {@code decode} throw and always gives at least one problem and no response.
 */
class MalformedOutputPropertyTest {

    /** A field of a random operation: a scalar or a struct, and an array of either when {@code count} is above 0. */
    private record Node(int kind, List<Node> members, int count) {
        boolean group() {
            return kind < 0;
        }

        Node element() {
            return new Node(kind, members, 0);
        }
    }

    private static Node randomNode(Gen g, int depth) {
        int count = g.bool() ? 0 : g.intIn(1, 3);
        if (depth > 0 && g.intIn(0, 2) == 0) {
            List<Node> members = new ArrayList<>();
            for (int i = g.intIn(1, 3); i > 0; i--) {
                members.add(randomNode(g, depth - 1));
            }
            return new Node(-1, members, count);
        }
        return new Node(g.intIn(0, 2), List.of(), count);
    }

    private static Ops.F build(String name, Node node) {
        Ops.F f;
        if (node.group()) {
            Ops.F[] members = new Ops.F[node.members().size()];
            for (int i = 0; i < members.length; i++) {
                members[i] = build("M" + i, node.members().get(i));
            }
            f = Ops.struct(name, members);
        } else {
            f = field(name, switch (node.kind()) {
                case 0 -> chr(3);
                case 1 -> int4();
                default -> new WireType.Packed(5, 2);
            });
        }
        f = f.response();
        return node.count() > 0 ? f.count(node.count()) : f;
    }

    private static WireValue valid(Node node) {
        if (node.count() > 0) {
            List<WireValue> elements = new ArrayList<>();
            for (int i = 0; i < node.count(); i++) {
                elements.add(valid(node.element()));
            }
            return new WireValue.Array(elements);
        }
        if (node.group()) {
            return new WireValue.Struct(node.members().stream().map(MalformedOutputPropertyTest::valid).toList());
        }
        return switch (node.kind()) {
            case 0 -> new WireValue.Text("abc");
            case 1 -> new WireValue.Integer(BigInteger.ONE);
            default -> new WireValue.Decimal(new BigDecimal("1.00"));
        };
    }

    /** The value made wrong in exactly one place: a wrong kind, a wrong number of elements or members, at a random depth. */
    private static WireValue wrong(Gen g, Node node, WireValue value) {
        if (node.count() > 0) {
            List<WireValue> elements = new ArrayList<>(((WireValue.Array) value).elements());
            switch (g.intIn(0, 2)) {
                case 0 -> {
                    return new WireValue.Text("x");
                }
                case 1 -> {
                    return new WireValue.Array(elements.subList(0, elements.size() - 1));
                }
                default -> {
                    int i = g.intIn(0, elements.size() - 1);
                    elements.set(i, wrongElement(g, node.element(), elements.get(i)));
                    return new WireValue.Array(elements);
                }
            }
        }
        return wrongElement(g, node, value);
    }

    private static WireValue wrongElement(Gen g, Node node, WireValue value) {
        if (!node.group()) {
            return g.bool() ? new WireValue.Struct(List.of()) : new WireValue.Array(List.of());
        }
        List<WireValue> members = new ArrayList<>(((WireValue.Struct) value).members());
        switch (g.intIn(0, 3)) {
            case 0 -> {
                return new WireValue.Text("x");
            }
            case 1 -> {
                return new WireValue.Struct(members.subList(0, members.size() - 1));
            }
            case 2 -> {
                members.add(new WireValue.Text("x"));
                return new WireValue.Struct(members);
            }
            default -> {
                int i = g.intIn(0, members.size() - 1);
                members.set(i, wrong(g, node.members().get(i), members.get(i)));
                return new WireValue.Struct(members);
            }
        }
    }

    @Test
    void outputWrongInOnePlaceIsNeverThrownAtAndAlwaysReported() {
        Properties.check("malformed output", g -> {
            List<Node> nodes = new ArrayList<>();
            for (int i = g.intIn(1, 4); i > 0; i--) {
                nodes.add(randomNode(g, 3));
            }
            Ops.F[] fields = new Ops.F[nodes.size()];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = build("F" + i, nodes.get(i));
            }
            OperationCodec codec = Ops.codec(fields);
            List<WireValue> output = new ArrayList<>();
            nodes.forEach(n -> output.add(valid(n)));
            DecodeResult good = codec.decode(output);
            assertThat(good.problems()).as("valid output").isEmpty();
            int at = g.intIn(0, nodes.size() - 1);
            output.set(at, wrong(g, nodes.get(at), output.get(at)));
            DecodeResult bad = codec.decode(output);
            assertThat(bad.problems()).as("wrong output at F" + at).isNotEmpty();
            assertThat(bad.response()).isEmpty();
            assertThat(bad.error()).isEmpty();
        });
    }

    @Test
    void outputWithTheWrongNumberOfParametersIsReported() {
        Properties.check("wrong parameter count", g -> {
            Ops.F[] fields = new Ops.F[g.intIn(1, 4)];
            List<WireValue> output = new ArrayList<>();
            for (int i = 0; i < fields.length; i++) {
                fields[i] = field("F" + i, chr(3)).response();
                output.add(new WireValue.Text("abc"));
            }
            OperationCodec codec = Ops.codec(fields);
            output.add(new WireValue.Text("extra"));
            assertThat(codec.decode(output).problems()).extracting(ValueProblem::code).containsExactly("VAL101");
            output.remove(output.size() - 1);
            output.remove(output.size() - 1);
            assertThat(codec.decode(output).problems()).extracting(ValueProblem::code).containsExactly("VAL101");
        });
    }
}
