package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.CodecSettings;
import ai.generativearchitects.glow400.pcml.api.GatewayDefaults;
import ai.generativearchitects.glow400.pcml.api.InputOutputPolicy;
import ai.generativearchitects.glow400.pcml.api.Pad;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.snakeyaml.engine.v2.nodes.Node;

/** Reads {@code gateway.yaml}: every key is optional, and absent keys take the standard defaults. */
final class GatewayParser {

    private final YamlReader yaml;

    GatewayParser(YamlReader yaml) {
        this.yaml = yaml;
    }

    /** The defaults, or empty (with diagnostics) if the file has errors. */
    Optional<GatewayDefaults> parse(String text) {
        int errorsBefore = yaml.errorCount();
        YamlReader.Composition composition = yaml.compose(text);
        if (!composition.valid()) {
            return Optional.empty();
        }
        GatewayDefaults standard = GatewayDefaults.standard();
        if (composition.root().isEmpty() || isNull(composition.root().get())) {
            return Optional.of(standard);
        }
        Optional<List<YamlReader.Entry>> top = yaml.mapping(composition.root().get(), "gateway.yaml");
        if (top.isEmpty()) {
            return Optional.empty();
        }
        yaml.knownKeys(top.get(), Set.of("defaults", "emitters"), "gateway.yaml");

        CodecSettings charCodec = standard.charCodec();
        InputOutputPolicy policy = standard.inputOutput();
        List<String> emitters = standard.emitters();

        Optional<YamlReader.Entry> defaults = yaml.find(top.get(), "defaults");
        if (defaults.isPresent()) {
            Optional<List<YamlReader.Entry>> entries = yaml.mapping(defaults.get().value(), "'defaults'");
            if (entries.isPresent()) {
                yaml.knownKeys(entries.get(), Set.of("char", "inputoutput"), "'defaults'");
                Optional<YamlReader.Entry> charEntry = yaml.find(entries.get(), "char");
                if (charEntry.isPresent()) {
                    charCodec = charDefaults(charEntry.get(), charCodec);
                }
                Optional<YamlReader.Entry> io = yaml.find(entries.get(), "inputoutput");
                if (io.isPresent()) {
                    policy = yaml.choice(io.get(), Vocabulary.INPUT_OUTPUT).map(Located::value).orElse(policy);
                }
            }
        }

        Optional<YamlReader.Entry> emitterEntry = yaml.find(top.get(), "emitters");
        if (emitterEntry.isPresent()) {
            Optional<List<Node>> items = yaml.sequence(emitterEntry.get().value(), "'emitters'");
            if (items.isPresent()) {
                List<String> names = new ArrayList<>();
                for (Node item : items.get()) {
                    yaml.scalarText(item, "an emitter name").ifPresent(name -> names.add(name.value()));
                }
                emitters = names;
            }
        }
        if (yaml.errorCount() > errorsBefore) {
            return Optional.empty();
        }
        return Optional.of(new GatewayDefaults(charCodec, policy, emitters));
    }

    private CodecSettings charDefaults(YamlReader.Entry entry, CodecSettings base) {
        Optional<List<YamlReader.Entry>> entries = yaml.mapping(entry.value(), "'char'");
        if (entries.isEmpty()) {
            return base;
        }
        yaml.knownKeys(entries.get(), Set.of("pad", "padChar", "nullAs"), "'char'");
        Pad pad = base.pad();
        char padChar = base.padChar();
        String nullAs = base.nullAs();
        for (YamlReader.Entry e : entries.get()) {
            switch (e.key()) {
                case "pad" -> pad = yaml.choice(e, Vocabulary.PAD).map(Located::value).orElse(pad);
                case "padChar" -> padChar = padChar(e).orElse(padChar);
                case "nullAs" -> nullAs = yaml.string(e).map(Located::value).orElse(nullAs);
                default -> { }
            }
        }
        return new CodecSettings(pad, padChar, nullAs, base.format());
    }

    /** A pad character: a string of exactly one character. */
    Optional<Character> padChar(YamlReader.Entry entry) {
        Optional<Located<String>> text = yaml.string(entry);
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.get().value().length() != 1) {
            yaml.error("OVL012", "'" + entry.key() + "' must be exactly one character", text.get().at());
            return Optional.empty();
        }
        return Optional.of(text.get().value().charAt(0));
    }

    static boolean isNull(Node node) {
        return node instanceof org.snakeyaml.engine.v2.nodes.ScalarNode scalar
                && scalar.getTag().equals(org.snakeyaml.engine.v2.nodes.Tag.NULL);
    }
}
