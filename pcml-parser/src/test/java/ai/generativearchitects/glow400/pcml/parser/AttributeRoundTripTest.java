package ai.generativearchitects.glow400.pcml.parser;

import static org.assertj.core.api.Assertions.assertThat;

import ai.generativearchitects.glow400.pcml.model.FieldDef;
import ai.generativearchitects.glow400.pcml.model.PcmlDocument;
import ai.generativearchitects.glow400.pcml.model.ProgramDef;
import ai.generativearchitects.glow400.pcml.model.StructDef;
import ai.generativearchitects.glow400.pcml.model.WireType;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * Round-trip property: for every valid corpus file, every attribute present in the XML is found either
 * in a modelled property of the corresponding model element or in its attribute map, with the same value.
 */
class AttributeRoundTripTest {

    static List<String> files() {
        return Corpus.VALID;
    }

    @ParameterizedTest
    @MethodSource("files")
    void everyAttributeIsAccountedFor(String name) throws Exception {
        PcmlDocument doc = Corpus.parse(name + ".pcml").document().orElseThrow();
        Element root = xmlRoot(name);

        assertAccounted(root, Set.of("version"), doc.attributes(), "pcml");
        List<Element> programs = children(root, "program");
        assertThat(programs).hasSameSizeAs(doc.programs());
        for (int i = 0; i < programs.size(); i++) {
            ProgramDef program = doc.programs().get(i);
            assertAccounted(programs.get(i), Set.of("name", "path", "entrypoint", "returnvalue"), program.attributes(), program.name());
            assertMembers(programs.get(i), program.parameters(), doc);
        }
        List<Element> structs = children(root, "struct");
        for (Element struct : structs) {
            StructDef def = doc.structs().get(struct.getAttribute("name"));
            assertThat(def).as("struct %s", struct.getAttribute("name")).isNotNull();
            assertAccounted(struct, Set.of("name"), def.attributes(), def.name());
            assertMembers(struct, def.fields(), doc);
        }
    }

    private static void assertMembers(Element xml, List<FieldDef> fields, PcmlDocument doc) {
        List<Element> members = new ArrayList<>();
        for (Node n = xml.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (e.getTagName().equals("data") || e.getTagName().equals("struct"))) {
                members.add(e);
            }
        }
        assertThat(members).hasSameSizeAs(fields);
        for (int i = 0; i < members.size(); i++) {
            Element member = members.get(i);
            FieldDef field = fields.get(i);
            Set<String> modelled = new java.util.HashSet<>(Set.of("name", "usage", "count"));
            if (member.getTagName().equals("data")) {
                modelled.add("type");
                modelled.addAll(switch (field.wire()) {
                    case WireType.Char c -> Set.of("length", "ccsid");
                    case WireType.VarChar v -> Set.of("length", "varlength", "ccsid");
                    case WireType.Int i2 -> Set.of("length", "precision");
                    case WireType.Packed p -> Set.of("length", "precision");
                    case WireType.Zoned z -> Set.of("length", "precision");
                    case WireType.Float f -> Set.of("length");
                    case WireType.Byte b -> Set.of("length");
                    case WireType.Struct s -> Set.of("struct");
                    default -> Set.of();
                });
            } else {
                StructDef nested = doc.structs().get(((WireType.Struct) field.wire()).structName());
                assertMembers(member, nested.fields(), doc);
            }
            assertAccounted(member, modelled, field.attributes(), field.name());
        }
    }

    private static void assertAccounted(Element xml, Set<String> modelled, Map<String, String> attributeMap, String what) {
        NamedNodeMap attributes = xml.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            String key = attribute.getNodeName();
            if (modelled.contains(key) && !attributeMap.containsKey(key)) {
                continue;
            }
            assertThat(attributeMap).as("attribute '%s' of %s", key, what).containsEntry(key, attribute.getNodeValue());
        }
    }

    private static List<Element> children(Element parent, String tag) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(tag)) {
                result.add(e);
            }
        }
        return result;
    }

    private static Element xmlRoot(String name) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((p, s) -> new org.xml.sax.InputSource(new java.io.StringReader("")));
        try (var in = Files.newInputStream(Corpus.path(name + ".pcml"))) {
            return builder.parse(in).getDocumentElement();
        }
    }
}
