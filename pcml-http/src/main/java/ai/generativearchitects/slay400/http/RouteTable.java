package ai.generativearchitects.slay400.http;

import ai.generativearchitects.slay400.pcml.api.ApiDefinition;
import ai.generativearchitects.slay400.pcml.api.ApiOperation;
import ai.generativearchitects.slay400.pcml.api.ApiRepositoryModel;
import ai.generativearchitects.slay400.pcml.api.HttpMethod;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The routes of a repository, and the match of a request path and method with them. The path is what follows
 * {@code /{tenant}/{environment}}, still percent-encoded, as it came in the request.
 *
 * <p>Matching follows the route templates as the OpenAPI document publishes them:
 *
 * <ul>
 *   <li>The path is split at {@code /} and each segment is percent-decoded once. A segment that does not decode, that
 *       decodes to something containing {@code /} or a control character, or that is longer than
 *       {@link #MAX_SEGMENT_LENGTH} characters, matches nothing.</li>
 *   <li>A placeholder matches one or more characters of a segment, but not {@code .} or {@code ..} alone. A route can hold
 *       literal text in the same segment as a placeholder, as in {@code /files/{name}.json}.</li>
 *   <li>Nothing is normalised: {@code ..}, an empty segment, a trailing slash and repeated slashes match only a route that
 *       has them.</li>
 *   <li>At each position a literal segment wins over a segment with a placeholder, and a segment with more literal text wins
 *       over one with less; when the best choice leads nowhere, the next one is tried. The first complete match, in that
 *       order, is the route, whatever its methods. If it has no operation for the method, the result is
 *       {@link RouteMatch.MethodNotAllowed} with the methods it has.</li>
 * </ul>
 *
 * <p>Routes that differ only in the names of their placeholders and have the same method are reported when the repository is
 * loaded ({@code OVL014}); with a model that has two of them anyway, the first one wins. A table is immutable and can be
 * shared by threads.
 */
public final class RouteTable {

    /** The longest decoded path segment that can match, in characters. */
    public static final int MAX_SEGMENT_LENGTH = 4096;

    private static final RouteMatch NOT_FOUND = new RouteMatch.NotFound();

    private final Node root = new Node();

    private RouteTable() {
    }

    /**
     * The routes of every operation of a repository.
     *
     * @throws NullPointerException if {@code model} is {@code null}
     */
    public static RouteTable of(ApiRepositoryModel model) {
        Objects.requireNonNull(model, "model");
        RouteTable table = new RouteTable();
        for (ApiDefinition api : model.apis()) {
            for (ApiOperation operation : api.operations()) {
                table.add(api, operation);
            }
        }
        return table;
    }

    /**
     * The operation for a method and a path.
     *
     * @param method the HTTP method
     * @param path   what follows {@code /{tenant}/{environment}}, percent-encoded, starting with {@code /}
     * @return the match; never {@code null}, and no data makes this method throw
     * @throws NullPointerException if an argument is {@code null}
     */
    public RouteMatch match(HttpMethod method, String path) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        if (path.isEmpty() || path.charAt(0) != '/') {
            return NOT_FOUND;
        }
        String[] raw = path.substring(1).split("/", -1);
        List<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            Optional<String> decoded = decode(segment);
            if (decoded.isEmpty()) {
                return NOT_FOUND;
            }
            segments.add(decoded.get());
        }
        List<String> captures = new ArrayList<>();
        Node node = find(root, segments, 0, captures);
        if (node == null) {
            return NOT_FOUND;
        }
        Target target = node.targets.get(method);
        if (target == null) {
            return new RouteMatch.MethodNotAllowed(node.targets.keySet());
        }
        Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < target.names.size(); i++) {
            parameters.put(target.names.get(i), captures.get(i));
        }
        return new RouteMatch.Found(target.api, target.operation, parameters);
    }

    // ------------------------------------------------------------------ building

    private void add(ApiDefinition api, ApiOperation operation) {
        String route = operation.route();
        if (!route.startsWith("/")) {
            return; // not a route: the overlay refuses it (OVL012), and no path can match it
        }
        List<String> names = new ArrayList<>();
        Node node = root;
        for (String template : route.substring(1).split("/", -1)) {
            node = node.child(Template.of(template, names));
        }
        node.targets.putIfAbsent(operation.method(), new Target(api, operation, names));
    }

    private static Node find(Node node, List<String> segments, int index, List<String> captures) {
        if (index == segments.size()) {
            return node.targets.isEmpty() ? null : node;
        }
        String segment = segments.get(index);
        Node literal = node.literals.get(segment);
        if (literal != null) {
            Node found = find(literal, segments, index + 1, captures);
            if (found != null) {
                return found;
            }
        }
        for (PatternChild child : node.patterns) {
            int mark = captures.size();
            if (child.pattern.match(segment, captures)) {
                Node found = find(child.node, segments, index + 1, captures);
                if (found != null) {
                    return found;
                }
            }
            while (captures.size() > mark) {
                captures.remove(captures.size() - 1);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ percent decoding

    /** The segment decoded once, or empty if it does not decode or cannot be a segment of a path. */
    private static Optional<String> decode(String raw) {
        if (raw.length() > 6 * MAX_SEGMENT_LENGTH) { // the longest encoding of a character that takes two chars is six chars: %F0%9F%98%80
            return Optional.empty();
        }
        String text = raw;
        if (raw.indexOf('%') >= 0) {
            StringBuilder out = new StringBuilder();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int i = 0;
            while (i < raw.length()) {
                char c = raw.charAt(i);
                if (c != '%') {
                    if (!flush(bytes, out)) {
                        return Optional.empty();
                    }
                    out.append(c);
                    i++;
                    continue;
                }
                int high = i + 2 < raw.length() ? hex(raw.charAt(i + 1)) : -1;
                int low = i + 2 < raw.length() ? hex(raw.charAt(i + 2)) : -1;
                if (high < 0 || low < 0) {
                    return Optional.empty();
                }
                bytes.write(high * 16 + low);
                i += 3;
            }
            if (!flush(bytes, out)) {
                return Optional.empty();
            }
            text = out.toString();
        }
        if (text.length() > MAX_SEGMENT_LENGTH) {
            return Optional.empty();
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '/' || Character.isISOControl(c)) {
                return Optional.empty();
            }
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return Optional.empty();
            }
        }
        return Optional.of(text);
    }

    /** Appends the bytes collected so far, which must be UTF-8, as text. */
    private static boolean flush(ByteArrayOutputStream bytes, StringBuilder out) {
        if (bytes.size() == 0) {
            return true;
        }
        try {
            out.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())));
        } catch (CharacterCodingException e) {
            return false;
        }
        bytes.reset();
        return true;
    }

    /** The value of an ASCII hexadecimal digit, or -1. */
    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    // ------------------------------------------------------------------ the tree

    /** The operation of one route for one method, with the names of the placeholders of its own route. */
    private record Target(ApiDefinition api, ApiOperation operation, List<String> names) {
    }

    private record PatternChild(Template pattern, Node node) {
    }

    /** A position in the routes: what can follow, and the operations of a route that ends here. */
    private static final class Node {
        final Map<String, Node> literals = new LinkedHashMap<>();
        final List<PatternChild> patterns = new ArrayList<>();
        final Map<String, PatternChild> byShape = new LinkedHashMap<>();
        final Map<HttpMethod, Target> targets = new EnumMap<>(HttpMethod.class);

        /** The node after a segment of a route; routes that differ only in the names of placeholders share it. */
        Node child(Template template) {
            if (template.parts.isEmpty()) {
                return literals.computeIfAbsent(template.literal, key -> new Node());
            }
            PatternChild existing = byShape.get(template.shape);
            if (existing != null) {
                return existing.node;
            }
            PatternChild created = new PatternChild(template, new Node());
            byShape.put(template.shape, created);
            patterns.add(created);
            patterns.sort((a, b) -> Integer.compare(b.pattern.literalLength, a.pattern.literalLength)); // stable: more text first
            return created.node;
        }
    }

    /** A segment of a route: plain text, or text with placeholders. */
    private static final class Template {
        /** The plain text when there is no placeholder. */
        final String literal;
        /** The parts of a segment with placeholders: text and placeholders in turn; empty for plain text. */
        final List<Part> parts;
        /** The segment with every placeholder name replaced by {@code {}}. */
        final String shape;
        final int literalLength;

        private Template(String literal, List<Part> parts, String shape, int literalLength) {
            this.literal = literal;
            this.parts = parts;
            this.shape = shape;
            this.literalLength = literalLength;
        }

        /** Reads a segment of a route and adds the names of its placeholders, in order, to {@code names}. */
        static Template of(String segment, List<String> names) {
            List<Part> parts = new ArrayList<>();
            StringBuilder shape = new StringBuilder();
            int literalLength = 0;
            int from = 0;
            while (true) {
                int open = segment.indexOf('{', from);
                int close = open < 0 ? -1 : segment.indexOf('}', open);
                if (close < 0) {
                    break;
                }
                if (open > from) {
                    parts.add(new Part(false, segment.substring(from, open)));
                    shape.append(segment, from, open);
                    literalLength += open - from;
                }
                names.add(segment.substring(open + 1, close));
                parts.add(new Part(true, ""));
                shape.append("{}");
                from = close + 1;
            }
            if (parts.isEmpty()) {
                return new Template(segment, List.of(), segment, segment.length());
            }
            if (from < segment.length()) {
                parts.add(new Part(false, segment.substring(from)));
                shape.append(segment, from, segment.length());
                literalLength += segment.length() - from;
            }
            return new Template("", parts, shape.toString(), literalLength);
        }

        /** Whether the decoded segment has this form; the values of the placeholders are added to {@code captures}. */
        boolean match(String segment, List<String> captures) {
            return matchFrom(0, 0, segment, captures);
        }

        private boolean matchFrom(int part, int position, String segment, List<String> captures) {
            if (part == parts.size()) {
                return position == segment.length();
            }
            Part current = parts.get(part);
            if (!current.placeholder) {
                return segment.startsWith(current.text, position) && matchFrom(part + 1, position + current.text.length(), segment, captures);
            }
            boolean last = part == parts.size() - 1;
            for (int end = last ? segment.length() : position + 1; end <= segment.length(); end++) {
                if (end <= position || (!last && !followedBy(part + 1, segment, end))) {
                    continue;
                }
                String value = segment.substring(position, end);
                if (value.equals(".") || value.equals("..")) {
                    continue;
                }
                captures.add(value);
                if (matchFrom(part + 1, end, segment, captures)) {
                    return true;
                }
                captures.remove(captures.size() - 1);
            }
            return false;
        }

        /** Whether the part after a placeholder can start at {@code end}: a placeholder can end anywhere, text only where it is. */
        private boolean followedBy(int part, String segment, int end) {
            Part next = parts.get(part);
            return next.placeholder || segment.startsWith(next.text, end);
        }
    }

    private record Part(boolean placeholder, String text) {
    }
}
