package ai.generativearchitects.glow400.emit.descriptor;

import ai.generativearchitects.glow400.pcml.api.ApiRepositoryModel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** A descriptor as a tree that a test can change at a place named by a path such as {@code apis/0/operations/0/route}. */
final class Doc {

    private Object root;

    Doc(ApiRepositoryModel model) {
        byte[] bytes = new DescriptorEmitter().emit(model).files().get(0).content();
        try {
            root = JsonTree.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Doc of(String repository) {
        return new Doc(Repositories.model(repository));
    }

    /** A path segment is a key, an index, or {@code key=value} to pick the element of a list that has that text in that key. */
    @SuppressWarnings("unchecked")
    private Object step(Object node, String segment) {
        if (node instanceof List<?> list) {
            if (segment.contains("=")) {
                String key = segment.substring(0, segment.indexOf('='));
                String value = segment.substring(segment.indexOf('=') + 1);
                return list.stream().filter(e -> value.equals(((Map<String, Object>) e).get(key))).findFirst()
                        .orElseThrow(() -> new AssertionError("no element with " + segment));
            }
            return list.get(Integer.parseInt(segment));
        }
        if (!((Map<String, Object>) node).containsKey(segment)) {
            throw new AssertionError("no key " + segment);
        }
        return ((Map<String, Object>) node).get(segment);
    }

    private Object parent(String path) {
        Object node = root;
        String[] segments = path.split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            node = step(node, segments[i]);
        }
        return node;
    }

    private static String last(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** The value at a path. */
    Object get(String path) {
        return step(parent(path), last(path));
    }

    @SuppressWarnings("unchecked")
    Doc set(String path, Object value) {
        Object parent = parent(path);
        if (parent instanceof List<?> list) {
            ((List<Object>) list).set(Integer.parseInt(last(path)), value);
        } else {
            if (!((Map<String, Object>) parent).containsKey(last(path))) {
                throw new AssertionError("no key " + last(path));
            }
            ((Map<String, Object>) parent).put(last(path), value);
        }
        return this;
    }

    /** Adds a key that the descriptor does not have. */
    @SuppressWarnings("unchecked")
    Doc add(String path, Object value) {
        ((Map<String, Object>) parent(path)).put(last(path), value);
        return this;
    }

    @SuppressWarnings("unchecked")
    Doc remove(String path) {
        Object parent = parent(path);
        if (parent instanceof List<?> list) {
            ((List<Object>) list).remove(Integer.parseInt(last(path)));
        } else {
            ((Map<String, Object>) parent).remove(last(path));
        }
        return this;
    }

    /** The document as text; a string {@code "@@1.5@@"} is written as the bare token {@code 1.5}, for numbers JsonWriter does not write. */
    byte[] bytes() {
        return JsonWriter.write(root).replaceAll("\"@@(.*?)@@\"", "$1").getBytes(StandardCharsets.UTF_8);
    }

    DescriptorResult read() {
        return DescriptorReader.read(new ByteArrayInputStream(bytes()));
    }
}
