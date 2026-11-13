package ai.generativearchitects.glow400.pcml.codec;

import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.List;
import java.util.Optional;

/**
 * Chooses the codec of a scalar field and checks that its configuration can be encoded and decoded at all: the semantic
 * type must suit the wire type, and every setting must be usable. Whatever is wrong is added to {@code issues}; the
 * codec returned for such a field must not be used.
 */
final class ScalarCodecs {

    private ScalarCodecs() {
    }

    static ScalarCodec create(FieldBinding binding, List<String> issues) {
        FieldDef field = binding.field();
        WireType wire = field.wire();
        CodecSettings settings = binding.codec();
        SemanticType semantic = binding.semanticType().orElse(null);
        boolean requestSide = binding.direction() == Direction.REQUEST || binding.direction() == Direction.BOTH;
        String type = wireName(wire);

        if (semantic != SemanticType.BOOLEAN && (settings.trueValue().isPresent() || settings.falseValue().isPresent())) {
            issues.add("trueValue and falseValue apply only to boolean fields");
        }
        boolean text = wire instanceof WireType.Char || wire instanceof WireType.VarChar;
        if (!text && settings.pad() != Pad.NONE) {
            issues.add("pad applies only to char and varchar fields, not to " + type);
        }
        if (semantic == null) {
            issues.add("the field has no semantic type");
            return new Unusable();
        }

        return switch (wire) {
            case WireType.Char c -> text(binding, c.length(), false, c.ccsid(), requestSide, issues);
            case WireType.VarChar v -> text(binding, v.length(), true, v.ccsid(), requestSide, issues);
            case WireType.Int i -> integer(i, semantic, issues);
            case WireType.Packed p -> decimal(p.length(), p.precision(), semantic, type, issues);
            case WireType.Zoned z -> decimal(z.length(), z.precision(), semantic, type, issues);
            case WireType.Float f -> floating(f, semantic, issues);
            case WireType.Byte b -> bytes(b, semantic, requestSide, issues);
            case WireType.Date d -> temporal(TemporalCodec.Kind.DATE, SemanticType.DATE, semantic, type, issues);
            case WireType.Time t -> temporal(TemporalCodec.Kind.TIME, SemanticType.TIME, semantic, type, issues);
            case WireType.Timestamp t -> temporal(TemporalCodec.Kind.TIMESTAMP, SemanticType.DATETIME, semantic, type, issues);
            case WireType.Struct s -> {
                issues.add("a struct is not a scalar");
                yield new Unusable();
            }
        };
    }

    static String wireName(WireType wire) {
        return switch (wire) {
            case WireType.Char c -> "char";
            case WireType.VarChar v -> "varchar";
            case WireType.Int i -> "int";
            case WireType.Packed p -> "packed";
            case WireType.Zoned z -> "zoned";
            case WireType.Float f -> "float";
            case WireType.Byte b -> "byte";
            case WireType.Date d -> "date";
            case WireType.Time t -> "time";
            case WireType.Timestamp t -> "timestamp";
            case WireType.Struct s -> "struct";
        };
    }

    private static ScalarCodec notAllowed(SemanticType semantic, String wire, List<String> issues) {
        issues.add("semantic type " + semantic + " is not allowed for wire type " + wire);
        return new Unusable();
    }

    // ------------------------------------------------------------------ char and varchar

    private static ScalarCodec text(FieldBinding binding, Length lengthOf, boolean varchar, Optional<Integer> ccsid,
                                    boolean requestSide, List<String> issues) {
        String type = varchar ? "varchar" : "char";
        SemanticType semantic = binding.semanticType().orElseThrow();
        if (semantic == SemanticType.BINARY) {
            return notAllowed(semantic, type, issues);
        }
        CodecSettings settings = binding.codec();
        int length = lengthOf instanceof Length.Fixed fixed ? fixed.value() : -1;
        if (length < 0 && requestSide) {
            issues.add("a " + type + " field whose length is given by another field is not supported yet on the request side");
        }
        if (ccsid.isPresent() && !ByteWidth.isSingleByte(ccsid.get())) {
            issues.add("CCSID " + ccsid.get() + " uses more than one byte per character, which is not supported yet");
        }
        if ("twobyte".equals(binding.field().attributes().get("chartype"))) {
            issues.add("chartype twobyte is not supported yet");
        }
        if (length >= 0 && settings.nullAs().codePointCount(0, settings.nullAs().length()) > length) {
            issues.add("nullAs is longer than the field");
        }

        String trueValue = settings.trueValue().orElse(CodecSettings.DEFAULT_TRUE_VALUE);
        String falseValue = settings.falseValue().orElse(CodecSettings.DEFAULT_FALSE_VALUE);
        if (semantic == SemanticType.BOOLEAN) {
            if (length >= 0 && (trueValue.codePointCount(0, trueValue.length()) > length
                    || falseValue.codePointCount(0, falseValue.length()) > length)) {
                issues.add("trueValue or falseValue is longer than the field");
            }
            if (trueValue.equals(falseValue)) {
                issues.add("trueValue and falseValue are equal");
            }
        }

        DatePattern pattern = null;
        if (semantic == SemanticType.DATE || semantic == SemanticType.TIME || semantic == SemanticType.DATETIME) {
            pattern = pattern(settings.format(), semantic, length, varchar, issues);
        }
        return new TextCodec(length, varchar, semantic, settings.pad(), settings.padChar(), settings.nullAs(), pattern, trueValue, falseValue);
    }

    /** The pattern of a date-like field on char: the given one, or the default for the field's length; null if unusable. */
    private static DatePattern pattern(Optional<String> given, SemanticType semantic, int length, boolean varchar, List<String> issues) {
        String text = given.orElse(defaultFormat(semantic, length));
        if (text == null) {
            issues.add("no format is given and there is no default format for a " + semantic.name().toLowerCase(java.util.Locale.ROOT)
                    + " in a field of this length");
            return null;
        }
        DatePattern.Parsed parsed = DatePattern.parse(text);
        if (parsed.pattern().isEmpty()) {
            issues.add("the format is not supported: " + parsed.error().orElse(""));
            return null;
        }
        DatePattern pattern = parsed.pattern().get();
        Optional<String> unsuitable = switch (semantic) {
            case DATE -> pattern.problemForDate();
            case TIME -> pattern.problemForTime();
            default -> pattern.problemForDateTime();
        };
        if (unsuitable.isPresent()) {
            issues.add("the format does not suit the type: " + unsuitable.get());
            return null;
        }
        if (length >= 0 && (varchar ? pattern.outputLength() > length : pattern.outputLength() != length)) {
            issues.add("the format produces " + pattern.outputLength() + " characters, which " + (varchar ? "do not fit" : "is not the length of")
                    + " the field");
            return null;
        }
        return pattern;
    }

    /** DATE yyyyMMdd on 8 and yyyy-MM-dd on 10; TIME HHmmss on 6 and HH:mm:ss on 8; nothing else. */
    private static String defaultFormat(SemanticType semantic, int length) {
        if (semantic == SemanticType.DATE) {
            return length == 8 ? "yyyyMMdd" : length == 10 ? "yyyy-MM-dd" : null;
        }
        if (semantic == SemanticType.TIME) {
            return length == 6 ? "HHmmss" : length == 8 ? "HH:mm:ss" : null;
        }
        return null;
    }

    // ------------------------------------------------------------------ numbers, bytes, dates

    private static ScalarCodec integer(WireType.Int wire, SemanticType semantic, List<String> issues) {
        if (semantic != SemanticType.INTEGER) {
            return notAllowed(semantic, "int", issues);
        }
        int bytes = wire.lengthBytes();
        if (bytes != 2 && bytes != 4 && bytes != 8) {
            issues.add("an int field of " + bytes + " bytes is not supported: use 2, 4 or 8");
            return new Unusable();
        }
        int bits = bytes * 8;
        boolean unsigned = wire.precision().isPresent() && wire.precision().get() == bits;
        if (wire.precision().isPresent() && !unsigned && wire.precision().get() != bits - 1) {
            issues.add("the precision of an int field of " + bytes + " bytes must be " + (bits - 1) + " (signed) or " + bits + " (unsigned)");
            return new Unusable();
        }
        return new NumberCodecs.IntCodec(bytes, unsigned);
    }

    private static ScalarCodec decimal(int length, int scale, SemanticType semantic, String type, List<String> issues) {
        boolean allowed = semantic == SemanticType.DECIMAL || semantic == SemanticType.INTEGER && scale == 0;
        if (!allowed) {
            if (semantic == SemanticType.INTEGER) {
                issues.add("semantic type INTEGER is not allowed for a " + type + " field with decimal places");
                return new Unusable();
            }
            return notAllowed(semantic, type, issues);
        }
        if (length < 1 || scale > length) {
            issues.add("a " + type + " field needs at least one digit and no more decimals than digits");
            return new Unusable();
        }
        return new NumberCodecs.DecimalCodec(length, scale, semantic);
    }

    private static ScalarCodec floating(WireType.Float wire, SemanticType semantic, List<String> issues) {
        if (semantic != SemanticType.DECIMAL) {
            return notAllowed(semantic, "float", issues);
        }
        if (wire.lengthBytes() != 4 && wire.lengthBytes() != 8) {
            issues.add("a float field of " + wire.lengthBytes() + " bytes is not supported: use 4 or 8");
            return new Unusable();
        }
        return new NumberCodecs.FloatCodec(wire.lengthBytes());
    }

    private static ScalarCodec bytes(WireType.Byte wire, SemanticType semantic, boolean requestSide, List<String> issues) {
        if (semantic != SemanticType.BINARY) {
            return notAllowed(semantic, "byte", issues);
        }
        int length = wire.length() instanceof Length.Fixed fixed ? fixed.value() : -1;
        if (length < 0 && requestSide) {
            issues.add("a byte field whose length is given by another field is not supported yet on the request side");
        }
        return new BytesCodec(length);
    }

    private static ScalarCodec temporal(TemporalCodec.Kind kind, SemanticType allowed, SemanticType semantic, String type, List<String> issues) {
        if (semantic != allowed) {
            return notAllowed(semantic, type, issues);
        }
        return new TemporalCodec(kind);
    }

    /** Stands in for a field that cannot be encoded; never used, because such an operation has no codec. */
    private static final class Unusable implements ScalarCodec {
        private static IllegalStateException fail() {
            return new IllegalStateException("The field cannot be encoded: see OperationCodec.check");
        }

        @Override
        public Optional<WireValue> absent() {
            throw fail();
        }

        @Override
        public WireValue blank() {
            throw fail();
        }

        @Override
        public Optional<WireValue> encode(ApiValue value, Where where, Problems problems) {
            throw fail();
        }

        @Override
        public Optional<WireValue> encodeInjected(String text, Where where, Problems problems) {
            throw fail();
        }

        @Override
        public boolean supportsInjection() {
            throw fail();
        }

        @Override
        public Optional<ApiValue> decode(WireValue value, Where where, Problems problems) {
            throw fail();
        }

        @Override
        public boolean isBlank(WireValue value) {
            throw fail();
        }
    }
}
