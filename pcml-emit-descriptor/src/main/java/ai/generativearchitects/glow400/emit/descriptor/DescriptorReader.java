package ai.generativearchitects.glow400.emit.descriptor;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.CacheSettings;
import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.Direction;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.HttpMethod;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.api.Location;
import ai.generativearchitects.glow400.pcml.api.Pad;
import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.diagnostics.Diagnostic;
import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Reads a {@code descriptor.json} back into an {@link ApiRepositoryModel}.
 *
 * <p>The reader never throws for bad input: a document that is not JSON ({@code DSC001}), has another schema version
 * ({@code DSC002}) or does not have the structure of version 1 ({@code DSC003}) gives one diagnostic and no model.
 * Every key must be present and no other key is allowed. The document size, the nesting depth and the length of a string
 * are limited, and no type is taken from the input. Elements get one fixed source location that names the descriptor
 * file, since a descriptor carries no positions.
 *
 * <p>The model has the settings the descriptor holds: {@code defaults.char} and the emitter list of
 * {@code gateway.yaml} are not part of it, so the model has their standard values.
 */
public final class DescriptorReader {

    /** The name of the file, and of the fixed location of every element read from it. */
    public static final String FILE = DescriptorEmitter.FILE;

    private static final SourceLocation HERE = new SourceLocation(FILE, 1, 1);

    private final Map<String, FieldDef> fields = new LinkedHashMap<>();

    private DescriptorReader() {
    }

    /** Reads the document; the stream is left open. */
    public static DescriptorResult read(InputStream in) {
        Object tree;
        try {
            tree = JsonTree.read(in);
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            SourceLocation where = at == null || at.getLineNr() < 0 ? HERE : new SourceLocation(FILE, at.getLineNr(), Math.max(0, at.getColumnNr()));
            return failure("DSC001", "The document is not valid JSON: " + e.getOriginalMessage(), where);
        } catch (IOException e) {
            return failure("DSC001", "The document cannot be read: " + e.getClass().getSimpleName(), HERE);
        }
        try {
            return new DescriptorResult(Optional.of(new DescriptorReader().model(tree)), List.of());
        } catch (Fail fail) {
            return failure(fail.code(), fail.getMessage(), HERE);
        }
    }

    private static DescriptorResult failure(String code, String message, SourceLocation where) {
        return new DescriptorResult(Optional.empty(), List.of(new Diagnostic(code, Severity.ERROR, message, where)));
    }

    // ------------------------------------------------------------------ document

    private ApiRepositoryModel model(Object tree) {
        Obj root = Obj.of(tree, "");
        Object version = root.raw("schemaVersion");
        if (!(version instanceof Number number) || number instanceof Double || number instanceof Float
                || number instanceof java.math.BigDecimal) {
            throw Fail.structure("schemaVersion", "expected a whole number");
        }
        if (!(number instanceof Integer || number instanceof Long) || number.longValue() != DescriptorWriter.SCHEMA_VERSION) {
            throw new Fail("DSC002", "Unsupported descriptor schema version " + number + "; this reader supports version "
                    + DescriptorWriter.SCHEMA_VERSION);
        }
        Obj generator = root.object("generator");
        generator.string("name");
        generator.string("version");
        generator.end();
        Obj defaults = root.object("defaults");
        InputOutputPolicy policy = defaults.choice("inputoutput", InputOutputPolicy.class, DescriptorReader::lower);
        int threshold = defaults.integer("largeArrayThreshold");
        defaults.end();
        GatewayDefaults gateway = guard(defaults.at("largeArrayThreshold"),
                () -> new GatewayDefaults(GatewayDefaults.standard().charCodec(), policy, GatewayDefaults.standard().emitters(), threshold));

        List<ApiDefinition> apis = new ArrayList<>();
        List<ApiOperation> operations = new ArrayList<>();
        List<Object> items = root.array("apis");
        for (int i = 0; i < items.size(); i++) {
            ApiDefinition api = api(Obj.of(items.get(i), "apis[" + i + "]"));
            apis.add(api);
            operations.addAll(api.operations());
        }
        root.end();
        return guard("apis", () -> new ApiRepositoryModel(gateway, operations, apis));
    }

    private ApiDefinition api(Obj o) {
        String name = o.string("name");
        String title = o.string("title");
        String version = o.string("version");
        String overlay = o.string("overlay");
        List<ApiOperation> operations = new ArrayList<>();
        List<Object> items = o.array("operations");
        for (int i = 0; i < items.size(); i++) {
            operations.add(operation(Obj.of(items.get(i), o.at("operations") + "[" + i + "]"), version, overlay));
        }
        o.end();
        return guard(o.path(), () -> new ApiDefinition(name, title, version, overlay, operations));
    }

    private ApiOperation operation(Obj o, String version, String overlay) {
        String id = o.string("id");
        Optional<String> summary = Optional.ofNullable(o.nullableString("summary"));
        Optional<String> description = Optional.ofNullable(o.nullableString("description"));
        List<String> tags = o.strings("tags");
        String route = o.string("route");
        HttpMethod method = o.choice("method", HttpMethod.class, Enum::name);
        CallStrategy call = o.choice("call", CallStrategy.class, DescriptorReader::lower);
        if (call == CallStrategy.AUTO) {
            throw Fail.structure(o.at("call"), "expected one of [pcml, raw]: the call strategy is resolved in a descriptor");
        }
        fields.clear();
        Obj programObject = o.object("program");
        String pcml = programObject.string("pcml");
        ProgramDef program = program(programObject);
        List<FieldBinding> bindings = new ArrayList<>();
        List<Object> items = o.array("bindings");
        for (int i = 0; i < items.size(); i++) {
            bindings.add(binding(Obj.of(items.get(i), o.at("bindings") + "[" + i + "]"), null));
        }
        Optional<ErrorMapping> error = Optional.ofNullable(o.nullableObject("error")).map(DescriptorReader::error);
        Optional<CacheSettings> cache = Optional.ofNullable(o.nullableObject("cache")).map(c -> {
            int ttl = c.integer("ttlSeconds");
            c.end();
            return guard(c.at("ttlSeconds"), () -> new CacheSettings(ttl));
        });
        o.end();
        return guard(o.path(), () -> new ApiOperation(id, version, summary, description, tags, program, pcml, overlay, route, method, call,
                bindings, error, cache, HERE));
    }

    // ------------------------------------------------------------------ the call model

    private ProgramDef program(Obj o) {
        String name = o.string("name");
        Optional<String> path = Optional.ofNullable(o.nullableString("path"));
        Optional<String> entryPoint = Optional.ofNullable(o.nullableString("entryPoint"));
        Optional<String> returnValue = Optional.ofNullable(o.nullableString("returnValue"));
        Map<String, String> attributes = o.stringMap("attributes");
        List<FieldDef> parameters = new ArrayList<>();
        List<Object> items = o.array("parameters");
        for (int i = 0; i < items.size(); i++) {
            parameters.add(field(Obj.of(items.get(i), o.at("parameters") + "[" + i + "]"), ""));
        }
        o.end();
        return guard(o.path(), () -> new ProgramDef(name, path, entryPoint, returnValue, parameters, attributes, HERE));
    }

    /** A field of the call model; its members are registered under their dotted path, which bindings refer to. */
    private FieldDef field(Obj o, String parentPath) {
        String name = o.string("name");
        WireType wire = wire(o.object("wire"));
        Usage usage = o.choice("usage", Usage.class, u -> u == Usage.INPUT_OUTPUT ? "inputoutput" : lower(u));
        Count count = count(o.raw("count"), o.at("count"));
        Map<String, String> attributes = o.stringMap("attributes");
        List<Object> members = o.nullableArray("fields");
        if (wire instanceof WireType.Struct != (members != null)) {
            throw Fail.structure(o.at("fields"), "a struct has a list of fields; any other type has null");
        }
        String path = parentPath + name;
        FieldDef field = guard(o.path(), () -> new FieldDef(name, wire, usage, count, attributes, HERE));
        if (fields.putIfAbsent(path, field) != null) {
            throw Fail.structure(o.path(), "the field '" + path + "' is defined twice");
        }
        if (members != null) {
            for (int i = 0; i < members.size(); i++) {
                field(Obj.of(members.get(i), o.at("fields") + "[" + i + "]"), path + ".");
            }
        }
        o.end();
        return field;
    }

    private static Count count(Object raw, String where) {
        return switch (raw) {
            case null -> new Count.None();
            case String field -> guard(where, () -> new Count.Ref(field));
            case Number number -> {
                int value = Obj.toInt(number, where, "a whole number, a field name or null");
                yield guard(where, () -> new Count.Fixed(value));
            }
            default -> throw Fail.structure(where, "expected a whole number, a field name or null");
        };
    }

    private static Length length(Object raw, String where) {
        return switch (raw) {
            case String field -> guard(where, () -> new Length.Ref(field));
            case Number number -> {
                int value = Obj.toInt(number, where, "a whole number or a field name");
                yield guard(where, () -> new Length.Fixed(value));
            }
            case null, default -> throw Fail.structure(where, "expected a whole number or a field name");
        };
    }

    private static WireType wire(Obj o) {
        String kind = o.string("kind");
        WireType wire = switch (kind) {
            case "char" -> new WireType.Char(length(o.raw("length"), o.at("length")), Optional.ofNullable(o.nullableInteger("ccsid")));
            case "varchar" -> new WireType.VarChar(length(o.raw("length"), o.at("length")),
                    Optional.ofNullable(o.nullableInteger("varLength")), Optional.ofNullable(o.nullableInteger("ccsid")));
            case "int" -> new WireType.Int(o.integer("length"), Optional.ofNullable(o.nullableInteger("precision")));
            case "packed" -> new WireType.Packed(o.integer("length"), o.integer("precision"));
            case "zoned" -> new WireType.Zoned(o.integer("length"), o.integer("precision"));
            case "float" -> new WireType.Float(o.integer("length"));
            case "byte" -> new WireType.Byte(length(o.raw("length"), o.at("length")));
            case "struct" -> guard(o.at("name"), () -> new WireType.Struct(o.string("name")));
            case "date" -> new WireType.Date();
            case "time" -> new WireType.Time();
            case "timestamp" -> new WireType.Timestamp();
            default -> throw Fail.structure(o.at("kind"), "unknown wire type '" + kind + "'");
        };
        o.end();
        return wire;
    }

    // ------------------------------------------------------------------ bindings

    private FieldBinding binding(Obj o, String parentPath) {
        String path = o.string("field");
        FieldDef field = fields.get(path);
        if (field == null) {
            throw Fail.structure(o.at("field"), "the field '" + path + "' is not defined by the program");
        }
        if (parentPath != null && !path.startsWith(parentPath + ".")) {
            throw Fail.structure(o.at("field"), "the field '" + path + "' is not a member of '" + parentPath + "'");
        }
        Direction direction = o.choice("direction", Direction.class, DescriptorReader::lower);
        String name = o.string("name");
        Optional<Location> in = Optional.ofNullable(o.nullableChoice("in", Location.class, DescriptorReader::lower));
        Optional<SemanticType> type = Optional.ofNullable(o.nullableChoice("type", SemanticType.class, DescriptorReader::lower));
        CodecSettings codec = codec(o.object("codec"));
        boolean required = o.bool("required");
        boolean hidden = o.bool("hidden");
        Optional<String> description = Optional.ofNullable(o.nullableString("description"));
        Optional<String> example = Optional.ofNullable(o.nullableString("example"));
        Optional<Trim> trim = Optional.ofNullable(o.nullableObject("trim")).map(DescriptorReader::trim);
        Optional<String> inject = Optional.ofNullable(o.nullableObject("inject")).map(DescriptorReader::inject);
        List<FieldBinding> children = new ArrayList<>();
        List<Object> items = o.array("children");
        for (int i = 0; i < items.size(); i++) {
            children.add(binding(Obj.of(items.get(i), o.at("children") + "[" + i + "]"), path));
        }
        o.end();
        return guard(o.path(), () -> new FieldBinding(path, field, direction, inject, name, in, type, codec, required, description,
                example, hidden, trim, children, HERE));
    }

    private static CodecSettings codec(Obj o) {
        Pad pad = o.choice("pad", Pad.class, DescriptorReader::lower);
        String padChar = o.string("padChar");
        if (padChar.codePointCount(0, padChar.length()) != 1 || padChar.length() != 1) {
            throw Fail.structure(o.at("padChar"), "expected a single character");
        }
        String nullAs = o.string("nullAs");
        Optional<String> format = Optional.ofNullable(o.nullableString("format"));
        Optional<String> trueValue = Optional.ofNullable(o.nullableString("trueValue"));
        Optional<String> falseValue = Optional.ofNullable(o.nullableString("falseValue"));
        o.end();
        return new CodecSettings(pad, padChar.charAt(0), nullAs, format, trueValue, falseValue);
    }

    private static Trim trim(Obj o) {
        String kind = o.string("kind");
        Trim trim = switch (kind) {
            case "countField" -> {
                String field = o.string("field");
                yield guard(o.at("field"), () -> new Trim.CountField(field));
            }
            case "dropBlank" -> new Trim.DropBlank();
            default -> throw Fail.structure(o.at("kind"), "expected one of [countField, dropBlank]");
        };
        o.end();
        return trim;
    }

    private static String inject(Obj o) {
        String kind = o.string("kind");
        String value = o.string("value");
        if (!kind.equals("literal") && !kind.equals("expression")) {
            throw Fail.structure(o.at("kind"), "expected one of [literal, expression]");
        }
        if (DescriptorWriter.isExpression(value) != kind.equals("expression")) {
            throw Fail.structure(o.at("kind"), "the kind does not match the value: an expression starts with ${ and ends with }");
        }
        o.end();
        return value;
    }

    private static ErrorMapping error(Obj o) {
        Obj when = o.object("when");
        String field = when.string("field");
        String op = when.string("op");
        ErrorCondition condition = switch (op) {
            case "equals" -> new ErrorCondition.Equals(field, when.string("value"));
            case "notEquals" -> new ErrorCondition.NotEquals(field, when.string("value"));
            case "in" -> guard(when.at("values"), () -> new ErrorCondition.In(field, when.strings("values")));
            default -> throw Fail.structure(when.at("op"), "expected one of [equals, notEquals, in]");
        };
        when.end();
        Optional<String> codeField = Optional.ofNullable(o.nullableString("codeField"));
        Optional<String> messageField = Optional.ofNullable(o.nullableString("messageField"));
        int status = o.integer("status");
        Map<String, Integer> byCode = o.integerMap("statusByCode");
        o.end();
        return guard(o.path(), () -> new ErrorMapping(condition, codeField, messageField, status, byCode));
    }

    // ------------------------------------------------------------------ helpers

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    /** Builds a model object; a value its constructor refuses is a structure problem at {@code where}. */
    private static <T> T guard(String where, Supplier<T> build) {
        try {
            return build.get();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw Fail.structure(where, e.getMessage());
        }
    }
}
