package ai.generativearchitects.glow400.emit.descriptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * The tool that writes descriptors, bundles and reports: its provisional name, its version and where its source is. Every
 * file that names the tool takes it from here, so that renaming the product is a change in one place.
 */
public final class Tool {

    /** The provisional name of the tool. */
    public static final String NAME = "glow400-core";

    /** Where the source of the tool is. */
    public static final String REPOSITORY_URL = "https://github.com/generativearchitects-ai/glow400-core";

    private static final String VERSION = readVersion();

    private Tool() {
    }

    /** The version of the tool, from a resource filled in by the build. */
    public static String version() {
        return VERSION;
    }

    private static String readVersion() {
        Properties properties = new Properties();
        try (InputStream in = Tool.class.getResourceAsStream("generator.properties")) {
            if (in == null) {
                throw new IllegalStateException("generator.properties is missing from the class path");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return properties.getProperty("version");
    }
}
