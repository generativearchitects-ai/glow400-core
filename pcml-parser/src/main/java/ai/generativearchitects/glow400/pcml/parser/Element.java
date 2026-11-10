package ai.generativearchitects.glow400.pcml.parser;

import ai.generativearchitects.glow400.pcml.model.SourceLocation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal XML element tree: name, attributes in document order, child elements, start position. */
final class Element {

    private final String name;
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final List<Element> children = new ArrayList<>();
    private final SourceLocation location;

    Element(String name, SourceLocation location) {
        this.name = name;
        this.location = location;
    }

    String name() {
        return name;
    }

    Map<String, String> attributes() {
        return attributes;
    }

    List<Element> children() {
        return children;
    }

    SourceLocation location() {
        return location;
    }
}
