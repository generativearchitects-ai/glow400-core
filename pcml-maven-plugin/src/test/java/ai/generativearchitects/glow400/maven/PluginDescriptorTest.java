package ai.generativearchitects.glow400.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** The plugin descriptor Maven reads, as generated from the annotations: the public surface of the plugin, goal by goal. */
class PluginDescriptorTest {

    private record Parameter(String defaultValue, String property, boolean required) {
    }

    private static final Document DESCRIPTOR = read();

    private static Document read() {
        try (InputStream in = PluginDescriptorTest.class.getResourceAsStream("/META-INF/maven/plugin.xml")) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(in);
        } catch (Exception e) {
            throw new AssertionError("The plugin descriptor cannot be read: run the build, which generates it", e);
        }
    }

    private static String text(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static Element mojo(String goal) {
        NodeList mojos = DESCRIPTOR.getElementsByTagName("mojo");
        for (int i = 0; i < mojos.getLength(); i++) {
            Element mojo = (Element) mojos.item(i);
            if (goal.equals(text(mojo, "goal"))) {
                return mojo;
            }
        }
        throw new AssertionError("No goal " + goal);
    }

    private static Map<String, Parameter> parameters(String goal) {
        Element mojo = mojo(goal);
        Map<String, Boolean> required = new LinkedHashMap<>();
        NodeList declared = ((Element) mojo.getElementsByTagName("parameters").item(0)).getElementsByTagName("parameter");
        for (int i = 0; i < declared.getLength(); i++) {
            Element p = (Element) declared.item(i);
            required.put(text(p, "name"), Boolean.parseBoolean(text(p, "required")));
        }
        Map<String, Parameter> result = new LinkedHashMap<>();
        Element configuration = (Element) mojo.getElementsByTagName("configuration").item(0);
        for (Node n = configuration.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && required.containsKey(e.getTagName())) {
                String property = e.getTextContent().isBlank() ? null : e.getTextContent();
                result.put(e.getTagName(), new Parameter(e.hasAttribute("default-value") ? e.getAttribute("default-value") : null, property, required.get(e.getTagName())));
            }
        }
        return result;
    }

    private static final Map<String, Parameter> COMMON = Map.of(
            "repository", new Parameter("${project.basedir}", "${pcml.repository}", false),
            "failOn", new Parameter("error", "${pcml.failOn}", false),
            "skip", new Parameter("false", "${pcml.skip}", false),
            "reportFile", new Parameter(null, "${pcml.reportFile}", false),
            "reportFormat", new Parameter("json", "${pcml.reportFormat}", false),
            "project", new Parameter("${project}", null, true));

    private static Map<String, Parameter> with(Map<String, Parameter> own) {
        Map<String, Parameter> all = new LinkedHashMap<>(COMMON);
        all.putAll(own);
        return all;
    }

    @Test
    void thePluginHasThePrefixPcmlAndDeclaresJava21AndMaven399() {
        Element plugin = DESCRIPTOR.getDocumentElement();
        assertThat(text(plugin, "goalPrefix")).isEqualTo("pcml");
        assertThat(text(plugin, "artifactId")).isEqualTo("pcml-maven-plugin");
        assertThat(text(plugin, "requiredJavaVersion")).isEqualTo("21");
        assertThat(text(plugin, "requiredMavenVersion")).isEqualTo("3.9.9");
        assertThat(DESCRIPTOR.getElementsByTagName("mojo").getLength()).isEqualTo(4);
    }

    @Test
    void everyGoalHasItsDefaultPhaseAndIsThreadSafe() {
        assertThat(text(mojo("validate"), "phase")).isEqualTo("validate");
        assertThat(text(mojo("generate"), "phase")).isEqualTo("generate-resources");
        assertThat(text(mojo("bundle"), "phase")).isEqualTo("package");
        assertThat(text(mojo("diff"), "phase")).isEqualTo("verify");
        for (String goal : new String[] {"validate", "generate", "bundle", "diff"}) {
            assertThat(text(mojo(goal), "threadSafe")).as(goal).isEqualTo("true");
            assertThat(text(mojo(goal), "requiresDependencyResolution")).as(goal).isNull();
        }
    }

    @Test
    void theParametersOfValidateAreTheCommonOnes() {
        assertThat(parameters("validate")).isEqualTo(with(Map.of()));
    }

    @Test
    void theParametersOfGenerateAddTheOutputDirectory() {
        assertThat(parameters("generate")).isEqualTo(with(Map.of("outputDirectory", new Parameter("${project.build.directory}/pcml", "${pcml.outputDirectory}", false))));
    }

    @Test
    void theParametersOfBundleAddTheBundleAndItsSource() {
        assertThat(parameters("bundle")).isEqualTo(with(Map.of(
                "bundleName", new Parameter("${project.artifactId}", "${pcml.bundleName}", false),
                "bundleVersion", new Parameter("${project.version}", "${pcml.bundleVersion}", false),
                "bundleFile", new Parameter("${project.build.directory}/${project.build.finalName}.rpgapi", "${pcml.bundleFile}", false),
                "attach", new Parameter("true", "${pcml.attach}", false),
                "sourceRepository", new Parameter(null, "${pcml.sourceRepository}", false),
                "sourceRevision", new Parameter(null, "${pcml.sourceRevision}", false))));
    }

    @Test
    void theParametersOfDiffAddTheRequiredAgainst() {
        assertThat(parameters("diff")).isEqualTo(with(Map.of("against", new Parameter(null, "${pcml.against}", true))));
    }
}
