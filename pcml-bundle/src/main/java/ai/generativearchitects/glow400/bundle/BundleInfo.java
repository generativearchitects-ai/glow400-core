package ai.generativearchitects.glow400.bundle;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * What the caller says about a bundle: its name and version, and where the repository came from.
 *
 * <p>The version is a release label for the whole repository, independent of the semantic versions of the APIs in it.
 *
 * @param name       matches {@code [a-z0-9][a-z0-9._-]*}
 * @param version    free text without white space, at most {@value #MAX_VERSION_LENGTH} characters
 * @param repository where the repository is, if known; written as {@code null} when absent
 * @param revision   the revision of the repository, if known; written as {@code null} when absent
 */
public record BundleInfo(String name, String version, Optional<String> repository, Optional<String> revision) {

    /** The longest version label. */
    public static final int MAX_VERSION_LENGTH = 64;

    /** The shape of a bundle name. */
    public static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    /** @throws NullPointerException if an argument is {@code null}; the caller reports invalid values with {@link #problems} */
    public BundleInfo {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(revision, "revision");
    }

    /** What is wrong with the name and the version, as messages that never contain them; empty if both are valid. */
    public java.util.List<String> problems() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (!NAME.matcher(name).matches()) {
            problems.add("The bundle name must match [a-z0-9][a-z0-9._-]*");
        }
        if (version.isEmpty() || version.length() > MAX_VERSION_LENGTH || version.chars().anyMatch(Character::isWhitespace)) {
            problems.add("The bundle version must be 1 to " + MAX_VERSION_LENGTH + " characters without white space");
        }
        return problems;
    }
}
