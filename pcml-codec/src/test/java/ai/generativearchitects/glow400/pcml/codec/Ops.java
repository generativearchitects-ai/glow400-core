package ai.generativearchitects.glow400.pcml.codec;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A small DSL to build operations and bindings by hand for the codec tests. */
final class Ops {

    static final SourceLocation AT = new SourceLocation("api.yaml", 1, 1);

    private Ops() {
    }

    // ---- wire types

    static WireType.Char chr(int length) {
        return new WireType.Char(new Length.Fixed(length), Optional.empty());
    }

    static WireType.VarChar varchar(int length) {
        return new WireType.VarChar(new Length.Fixed(length), Optional.empty(), Optional.empty());
    }

    static WireType.Int int4() {
        return new WireType.Int(4, Optional.empty());
    }

    // ---- fields

    static F field(String name, WireType wire) {
        return new F(name, wire);
    }

    static F struct(String name, F... members) {
        F f = new F(name, new WireType.Struct(name + "_T"));
        f.members.addAll(List.of(members));
        return f;
    }

    /** A fluent builder of one binding with the call-model field behind it. */
    static final class F {
        final String name;
        WireType wire;
        Count count = new Count.None();
        Direction direction = Direction.REQUEST;
        SemanticType semantic;
        Pad pad;
        char padChar = ' ';
        String nullAs = "";
        Optional<String> format = Optional.empty();
        Optional<String> trueValue = Optional.empty();
        Optional<String> falseValue = Optional.empty();
        boolean required;
        boolean hidden;
        Optional<Trim> trim = Optional.empty();
        Optional<String> example = Optional.empty();
        Optional<String> injection = Optional.empty();
        String exposed;
        final Map<String, String> attributes = new LinkedHashMap<>();
        final List<F> members = new ArrayList<>();

        F(String name, WireType wire) {
            this.name = name;
            this.wire = wire;
            this.exposed = name.toLowerCase(java.util.Locale.ROOT);
            this.semantic = defaultSemantic(wire);
            this.pad = wire instanceof WireType.Char ? Pad.RIGHT : Pad.NONE;
        }

        F type(SemanticType t) { semantic = t; return this; }
        F pad(Pad p, char c) { pad = p; padChar = c; return this; }
        F pad(Pad p) { pad = p; return this; }
        F nullAs(String s) { nullAs = s; return this; }
        F format(String f) { format = Optional.of(f); return this; }
        F booleans(String t, String f) { trueValue = Optional.of(t); falseValue = Optional.of(f); return this; }
        F trueValue(String t) { trueValue = Optional.of(t); return this; }
        F falseValue(String f) { falseValue = Optional.of(f); return this; }
        F count(int n) { count = new Count.Fixed(n); return this; }
        F countRef(String path) { count = new Count.Ref(path); return this; }
        F dir(Direction d) { direction = d; return this; }
        F response() { direction = Direction.RESPONSE; return this; }
        F internal() { direction = Direction.INTERNAL; return this; }
        F both() { direction = Direction.BOTH; return this; }
        F required() { required = true; return this; }
        F hidden() { hidden = true; return this; }
        F exposed(String e) { exposed = e; return this; }
        F trimCount(String path) { trim = Optional.of(new Trim.CountField(path)); return this; }
        F trimBlank() { trim = Optional.of(new Trim.DropBlank()); return this; }
        F example(String e) { example = Optional.of(e); return this; }
        F inject(String text) { direction = Direction.INJECTED; injection = Optional.of(text); return this; }
        F attr(String key, String value) { attributes.put(key, value); return this; }
        F wire(WireType w) { wire = w; return this; }

        FieldDef fieldDef() {
            return new FieldDef(name, wire, direction == Direction.RESPONSE ? Usage.OUTPUT : Usage.INPUT, count, attributes, AT);
        }

        FieldBinding build() {
            return build(name);
        }

        FieldBinding build(String path) {
            List<FieldBinding> children = new ArrayList<>();
            for (F member : members) {
                F copy = member;
                copy.direction = direction == Direction.INJECTED ? Direction.REQUEST : direction;
                children.add(copy.build(path + "." + member.name));
            }
            boolean isBoolean = semantic == SemanticType.BOOLEAN;
            CodecSettings codec = new CodecSettings(pad, padChar, nullAs, format,
                    isBoolean ? Optional.of(trueValue.orElse("1")) : trueValue,
                    isBoolean ? Optional.of(falseValue.orElse("0")) : falseValue);
            return new FieldBinding(path, fieldDef(), direction, injection, exposed, Optional.empty(),
                    wire instanceof WireType.Struct ? Optional.empty() : Optional.ofNullable(semantic),
                    codec, required, Optional.empty(), example, hidden, trim, children, AT);
        }
    }

    static SemanticType defaultSemantic(WireType wire) {
        return switch (wire) {
            case WireType.Char c -> SemanticType.STRING;
            case WireType.VarChar v -> SemanticType.STRING;
            case WireType.Byte b -> SemanticType.BINARY;
            case WireType.Int i -> SemanticType.INTEGER;
            case WireType.Packed p -> SemanticType.DECIMAL;
            case WireType.Zoned z -> SemanticType.DECIMAL;
            case WireType.Float f -> SemanticType.DECIMAL;
            case WireType.Date d -> SemanticType.DATE;
            case WireType.Time t -> SemanticType.TIME;
            case WireType.Timestamp t -> SemanticType.DATETIME;
            case WireType.Struct s -> null;
        };
    }

    // ---- operations

    static ApiOperation op(F... fields) {
        List<FieldBinding> bindings = new ArrayList<>();
        List<FieldDef> defs = new ArrayList<>();
        for (F f : fields) {
            bindings.add(f.build());
            defs.add(f.fieldDef());
        }
        ProgramDef program = new ProgramDef("PGM", Optional.empty(), Optional.empty(), Optional.empty(), defs, Map.of(), AT);
        return new ApiOperation("op", "1.0.0", Optional.empty(), Optional.empty(), List.of(), program, "a.pcml", "api.yaml", "/op",
                HttpMethod.POST, CallStrategy.AUTO, bindings, Optional.empty(), Optional.empty(), AT);
    }

    static OperationCodec codec(F... fields) {
        return OperationCodec.of(op(fields));
    }

    // ---- values

    static ApiValue.Text text(String s) { return new ApiValue.Text(s); }

    static ApiValue.Integer integer(long n) { return new ApiValue.Integer(java.math.BigInteger.valueOf(n)); }

    static ApiValue.Decimal decimal(String s) { return new ApiValue.Decimal(new java.math.BigDecimal(s)); }

    static ApiValue.Absent absent() { return new ApiValue.Absent(); }

    static Map<String, ApiValue> request(Object... pairs) {
        Map<String, ApiValue> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (ApiValue) pairs[i + 1]);
        }
        return map;
    }

    static ApiValue.Obj obj(Object... pairs) {
        return new ApiValue.Obj(request(pairs));
    }

    static ApiValue.Arr arr(ApiValue... elements) {
        return new ApiValue.Arr(List.of(elements));
    }
}
