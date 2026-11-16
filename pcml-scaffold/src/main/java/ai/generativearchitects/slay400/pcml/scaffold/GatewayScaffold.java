package ai.generativearchitects.slay400.pcml.scaffold;

import ai.generativearchitects.slay400.pcml.api.GatewayDefaults;
import java.util.Locale;

/**
 * Writes {@code gateway.yaml} with the defaults stated explicitly. They are the built-in defaults, except that
 * {@code defaults.inputoutput} is {@code fail}: a repository made by {@code init} does not validate until every
 * {@code inputoutput} parameter has a direction.
 */
public final class GatewayScaffold {

    private GatewayScaffold() {
    }

    /** The text of the file. */
    public static String create() {
        GatewayDefaults standard = GatewayDefaults.standard();
        StringBuilder out = new StringBuilder();
        out.append("defaults:\n");
        out.append("  char:\n");
        out.append("    pad: ").append(standard.charCodec().pad().name().toLowerCase(Locale.ROOT)).append('\n');
        out.append("    padChar: ").append(YamlText.scalar(String.valueOf(standard.charCodec().padChar()))).append('\n');
        out.append("    nullAs: ").append(YamlText.scalar(standard.charCodec().nullAs())).append('\n');
        out.append("  # fail: a parameter that is inputoutput needs a direction. Run annotate to give one.\n");
        out.append("  inputoutput: fail\n");
        out.append("  largeArrayThreshold: ").append(standard.largeArrayThreshold()).append('\n');
        out.append("emitters:\n");
        for (String emitter : standard.emitters()) {
            out.append("  - ").append(YamlText.scalar(emitter)).append('\n');
        }
        return out.toString();
    }
}
