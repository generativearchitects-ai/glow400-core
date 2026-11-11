package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.SemanticType;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.util.Locale;
import java.util.Optional;

/** Defaults derived from RPG names and PCML wire types (merge rules 4 and 5). */
final class NameRules {

    private NameRules() {
    }

    /**
     * The exposed name for an RPG name. If the name contains a lower-case letter it is kept and its first character
     * is lower-cased. Otherwise it is lower-cased and every {@code _x} becomes {@code X}: {@code ORDER_NUMBER} gives
     * {@code orderNumber} and {@code P0ORDN} gives {@code p0ordn}. Leading, trailing and repeated underscores are dropped.
     */
    static String exposedName(String rpgName) {
        boolean hasLower = rpgName.chars().anyMatch(Character::isLowerCase);
        if (hasLower) {
            return Character.toLowerCase(rpgName.charAt(0)) + rpgName.substring(1);
        }
        String lower = rpgName.toLowerCase(Locale.ROOT);
        StringBuilder name = new StringBuilder();
        boolean upperNext = false;
        for (char c : lower.toCharArray()) {
            if (c == '_') {
                upperNext = name.length() > 0;
            } else {
                name.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            }
        }
        return name.length() == 0 ? lower : name.toString();
    }

    /** The semantic type a wire type maps to by default; empty for a struct. */
    static Optional<SemanticType> semanticType(WireType wire) {
        return switch (wire) {
            case WireType.Char c -> Optional.of(SemanticType.STRING);
            case WireType.VarChar v -> Optional.of(SemanticType.STRING);
            case WireType.Byte b -> Optional.of(SemanticType.BINARY);
            case WireType.Int i -> Optional.of(SemanticType.INTEGER);
            case WireType.Packed p -> Optional.of(SemanticType.DECIMAL);
            case WireType.Zoned z -> Optional.of(SemanticType.DECIMAL);
            case WireType.Float f -> Optional.of(SemanticType.DECIMAL);
            case WireType.Date d -> Optional.of(SemanticType.DATE);
            case WireType.Time t -> Optional.of(SemanticType.TIME);
            case WireType.Timestamp t -> Optional.of(SemanticType.DATETIME);
            case WireType.Struct s -> Optional.empty();
        };
    }
}
