package ai.generativearchitects.glow400.diff;

import ai.generativearchitects.glow400.pcml.api.ApiOperation;
import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import ai.generativearchitects.glow400.pcml.api.FieldBinding;
import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What an operation has that a consumer cannot see: the program, the PCML, the call strategy, the cache, the error-mapping
 * fields (not the statuses, which are part of the surface), and how each field is wired and encoded. Each aspect is written as text without source positions, so that two
 * aspects are equal exactly when nothing but a position differs.
 */
final class InternalAspects {

    private InternalAspects() {
    }

    /** The aspects by name, in a fixed order. */
    static Map<String, String> of(ApiOperation operation) {
        Map<String, String> aspects = new LinkedHashMap<>();
        ProgramDef program = operation.program();
        aspects.put("program", program.name() + "|" + program.path() + "|" + program.entryPoint() + "|" + program.returnValue() + "|"
                + program.attributes() + "|" + operation.pcmlPath());
        StringBuilder parameters = new StringBuilder();
        program.parameters().forEach(parameter -> parameters.append(field(parameter)).append('\n'));
        aspects.put("parameters", parameters.toString());
        aspects.put("call strategy", resolved(operation).name());
        aspects.put("cache", String.valueOf(operation.cache()));
        aspects.put("error mapping", operation.errorMapping().map(m -> m.when() + "|" + m.codeField() + "|" + m.messageField()).orElse("none"));
        StringBuilder bindings = new StringBuilder();
        operation.bindings().forEach(binding -> binding(binding, bindings));
        aspects.put("field settings", bindings.toString());
        return aspects;
    }

    /** {@code auto} resolved as a descriptor resolves it: raw when a parameter is a struct, otherwise pcml. */
    static CallStrategy resolved(ApiOperation operation) {
        if (operation.callStrategy() != CallStrategy.AUTO) {
            return operation.callStrategy();
        }
        boolean struct = operation.program().parameters().stream().anyMatch(p -> p.wire() instanceof WireType.Struct);
        return struct ? CallStrategy.RAW : CallStrategy.PCML;
    }

    private static String field(FieldDef field) {
        return field.name() + "|" + field.wire() + "|" + field.usage() + "|" + field.count() + "|" + field.attributes();
    }

    private static void binding(FieldBinding binding, StringBuilder out) {
        out.append(binding.fieldPath()).append('|').append(field(binding.field())).append('|').append(binding.direction()).append('|')
                .append(binding.injection()).append('|').append(binding.codec()).append('|').append(binding.trim()).append('|')
                .append(binding.hidden()).append('\n');
        binding.children().forEach(child -> binding(child, out));
    }
}
