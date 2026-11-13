package ai.generativearchitects.glow400.emit.descriptor;

import ai.generativearchitects.glow400.pcml.api.ApiDefinition;
import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.ErrorCondition;
import ai.generativearchitects.glow400.pcml.api.ErrorMapping;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.api.Trim;
import ai.generativearchitects.glow400.pcml.model.Count;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.Length;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.Usage;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns an API model into the descriptor, version 1, as a tree for {@link JsonWriter}. The key order is the order in
 * which the keys are put here, which is the order of the description in {@code specs/core-design.md}.
 */
final class DescriptorWriter {

    /** The only schema version this code writes and reads. */
    static final int SCHEMA_VERSION = 1;

    private DescriptorWriter() {
    }

    static Map<String, Object> tree(ApiRepositoryModel model, String generatorVersion) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SCHEMA_VERSION);
        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("name", "glow400-core");
        generator.put("version", generatorVersion);
        root.put("generator", generator);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("inputoutput", lower(model.defaults().inputOutput()));
        defaults.put("largeArrayThreshold", model.defaults().largeArrayThreshold());
        root.put("defaults", defaults);
        List<Object> apis = new ArrayList<>();
        for (ApiDefinition api : model.apis()) {
            apis.add(api(api));
        }
        root.put("apis", apis);
        return root;
    }

    private static Map<String, Object> api(ApiDefinition api) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", api.name());
        out.put("title", api.title());
        out.put("version", api.version());
        out.put("overlay", api.overlayPath());
        List<Object> operations = new ArrayList<>();
        api.operations().forEach(operation -> operations.add(operation(operation)));
        out.put("operations", operations);
        return out;
    }

    private static Map<String, Object> operation(ApiOperation operation) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", operation.id());
        out.put("summary", operation.summary().orElse(null));
        out.put("description", operation.description().orElse(null));
        out.put("tags", List.copyOf(operation.tags()));
        out.put("route", operation.route());
        out.put("method", operation.method().name());
        out.put("call", lower(resolvedCall(operation)));
        out.put("program", program(operation));
        List<Object> bindings = new ArrayList<>();
        operation.bindings().forEach(binding -> bindings.add(binding(binding)));
        out.put("bindings", bindings);
        out.put("error", operation.errorMapping().map(DescriptorWriter::error).orElse(null));
        out.put("cache", operation.cache().map(cache -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ttlSeconds", cache.ttlSeconds());
            return map;
        }).orElse(null));
        return out;
    }

    /** {@code auto} is resolved here: raw when a program parameter is a struct or an array of structs. */
    static CallStrategy resolvedCall(ApiOperation operation) {
        if (operation.callStrategy() != CallStrategy.AUTO) {
            return operation.callStrategy();
        }
        boolean struct = operation.program().parameters().stream().anyMatch(p -> p.wire() instanceof WireType.Struct);
        return struct ? CallStrategy.RAW : CallStrategy.PCML;
    }

    private static Map<String, Object> program(ApiOperation operation) {
        ProgramDef program = operation.program();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", program.name());
        out.put("path", program.path().orElse(null));
        out.put("entryPoint", program.entryPoint().orElse(null));
        out.put("returnValue", program.returnValue().orElse(null));
        out.put("pcml", operation.pcmlPath());
        out.put("attributes", new LinkedHashMap<String, Object>(program.attributes()));
        List<Object> parameters = new ArrayList<>();
        for (FieldDef parameter : program.parameters()) {
            Optional<FieldBinding> binding = operation.bindings().stream()
                    .filter(b -> b.fieldPath().equals(parameter.name())).findFirst();
            parameters.add(field(parameter, binding.map(FieldBinding::children)));
        }
        out.put("parameters", parameters);
        return out;
    }

    /** A call-model field; a struct carries its members, taken from the bindings' children, under {@code fields}. */
    private static Map<String, Object> field(FieldDef field, Optional<List<FieldBinding>> members) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", field.name());
        out.put("wire", wire(field.wire()));
        out.put("usage", usage(field.usage()));
        out.put("count", count(field.count()));
        out.put("attributes", new LinkedHashMap<String, Object>(field.attributes()));
        if (field.wire() instanceof WireType.Struct) {
            List<Object> fields = new ArrayList<>();
            for (FieldBinding member : members.orElse(List.of())) {
                fields.add(field(member.field(), Optional.of(member.children())));
            }
            out.put("fields", fields);
        } else {
            out.put("fields", null);
        }
        return out;
    }

    private static String usage(Usage usage) {
        return usage == Usage.INPUT_OUTPUT ? "inputoutput" : lower(usage);
    }

    private static Object count(Count count) {
        return switch (count) {
            case Count.None none -> null;
            case Count.Fixed fixed -> fixed.value();
            case Count.Ref ref -> ref.field();
        };
    }

    private static Object length(Length length) {
        return switch (length) {
            case Length.Fixed fixed -> fixed.value();
            case Length.Ref ref -> ref.field();
        };
    }

    private static Map<String, Object> wire(WireType wire) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (wire) {
            case WireType.Char c -> {
                out.put("kind", "char");
                out.put("length", length(c.length()));
                out.put("ccsid", c.ccsid().orElse(null));
            }
            case WireType.VarChar v -> {
                out.put("kind", "varchar");
                out.put("length", length(v.length()));
                out.put("varLength", v.varLength().orElse(null));
                out.put("ccsid", v.ccsid().orElse(null));
            }
            case WireType.Int i -> {
                out.put("kind", "int");
                out.put("length", i.lengthBytes());
                out.put("precision", i.precision().orElse(null));
            }
            case WireType.Packed p -> {
                out.put("kind", "packed");
                out.put("length", p.length());
                out.put("precision", p.precision());
            }
            case WireType.Zoned z -> {
                out.put("kind", "zoned");
                out.put("length", z.length());
                out.put("precision", z.precision());
            }
            case WireType.Float f -> {
                out.put("kind", "float");
                out.put("length", f.lengthBytes());
            }
            case WireType.Byte b -> {
                out.put("kind", "byte");
                out.put("length", length(b.length()));
            }
            case WireType.Struct s -> {
                out.put("kind", "struct");
                out.put("name", s.structName());
            }
            case WireType.Date d -> out.put("kind", "date");
            case WireType.Time t -> out.put("kind", "time");
            case WireType.Timestamp t -> out.put("kind", "timestamp");
        }
        return out;
    }

    private static Map<String, Object> binding(FieldBinding binding) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("field", binding.fieldPath());
        out.put("direction", lower(binding.direction()));
        out.put("name", binding.exposedName());
        out.put("in", binding.location().map(DescriptorWriter::lower).orElse(null));
        out.put("type", binding.semanticType().map(DescriptorWriter::lower).orElse(null));
        out.put("codec", codec(binding.codec()));
        out.put("required", binding.required());
        out.put("hidden", binding.hidden());
        out.put("description", binding.description().orElse(null));
        out.put("example", binding.example().orElse(null));
        out.put("trim", binding.trim().map(DescriptorWriter::trim).orElse(null));
        out.put("inject", binding.injection().map(DescriptorWriter::inject).orElse(null));
        List<Object> children = new ArrayList<>();
        binding.children().forEach(child -> children.add(binding(child)));
        out.put("children", children);
        return out;
    }

    private static Map<String, Object> codec(CodecSettings codec) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pad", lower(codec.pad()));
        out.put("padChar", String.valueOf(codec.padChar()));
        out.put("nullAs", codec.nullAs());
        out.put("format", codec.format().orElse(null));
        out.put("trueValue", codec.trueValue().orElse(null));
        out.put("falseValue", codec.falseValue().orElse(null));
        return out;
    }

    private static Map<String, Object> trim(Trim trim) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (trim) {
            case Trim.CountField count -> {
                out.put("kind", "countField");
                out.put("field", count.path());
            }
            case Trim.DropBlank drop -> out.put("kind", "dropBlank");
        }
        return out;
    }

    /** An injection is an expression when it starts with {@code ${} and ends with {@code }}; the text is kept as written. */
    static boolean isExpression(String injection) {
        return injection.startsWith("${") && injection.endsWith("}");
    }

    private static Map<String, Object> inject(String injection) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", isExpression(injection) ? "expression" : "literal");
        out.put("value", injection);
        return out;
    }

    private static Map<String, Object> error(ErrorMapping error) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> when = new LinkedHashMap<>();
        when.put("field", error.when().field());
        switch (error.when()) {
            case ErrorCondition.Equals equals -> {
                when.put("op", "equals");
                when.put("value", equals.value());
            }
            case ErrorCondition.NotEquals notEquals -> {
                when.put("op", "notEquals");
                when.put("value", notEquals.value());
            }
            case ErrorCondition.In in -> {
                when.put("op", "in");
                when.put("values", List.copyOf(in.values()));
            }
        }
        out.put("when", when);
        out.put("codeField", error.codeField().orElse(null));
        out.put("messageField", error.messageField().orElse(null));
        out.put("status", error.httpStatus());
        out.put("statusByCode", new LinkedHashMap<String, Object>(error.statusByCode()));
        return out;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
