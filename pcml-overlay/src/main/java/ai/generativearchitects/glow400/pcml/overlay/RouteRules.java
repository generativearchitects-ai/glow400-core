package ai.generativearchitects.glow400.pcml.overlay;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Placeholders of route templates such as {@code /orders/{orderNumber}/status}. */
final class RouteRules {

    private RouteRules() {
    }

    /** Why a route is not well-formed, or empty if it is. */
    static Optional<String> problem(String route) {
        if (!route.startsWith("/")) {
            return Optional.of("must start with '/'");
        }
        int open = -1;
        for (int i = 0; i < route.length(); i++) {
            char c = route.charAt(i);
            if (c == '{') {
                if (open >= 0) {
                    return Optional.of("has a '{' inside a placeholder");
                }
                open = i;
            } else if (c == '}') {
                if (open < 0) {
                    return Optional.of("has a '}' without '{'");
                }
                if (i == open + 1) {
                    return Optional.of("has an empty placeholder");
                }
                open = -1;
            } else if (c == '/' && open >= 0) {
                return Optional.of("has a '/' inside a placeholder");
            }
        }
        return open >= 0 ? Optional.of("has a '{' without '}'") : Optional.empty();
    }

    /** The placeholder names of a well-formed route, in order of appearance. */
    static List<String> placeholders(String route) {
        List<String> names = new ArrayList<>();
        int from = 0;
        while (true) {
            int open = route.indexOf('{', from);
            if (open < 0) {
                return names;
            }
            int close = route.indexOf('}', open);
            names.add(route.substring(open + 1, close));
            from = close + 1;
        }
    }

    /** The route with every placeholder name replaced by {@code {}}, to compare routes that differ only in names. */
    static String shape(String route) {
        return route.replaceAll("\\{[^}]*}", "{}");
    }
}
