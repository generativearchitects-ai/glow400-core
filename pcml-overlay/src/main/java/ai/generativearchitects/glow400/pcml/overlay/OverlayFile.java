package ai.generativearchitects.glow400.pcml.overlay;

import ai.generativearchitects.glow400.pcml.api.CallStrategy;
import java.util.List;
import java.util.Objects;

/**
 * An overlay file ({@code api.yaml}) as authored.
 *
 * <p>A file in the common form, with {@code route} and {@code method} at the top level, has one entry in
 * {@code operations} and empty {@code shared} settings. A file with an {@code operations} list keeps its
 * top-level {@code inject}, {@code parameters}, {@code error}, {@code cache}, {@code tags} and {@code program}
 * in {@code shared}.
 *
 * @param path       the file, relative to the repository root, with {@code /} separators
 * @param pcml       {@code pcml}: the PCML file, relative to the overlay file
 * @param version    {@code version}
 * @param title      {@code title}, when given
 * @param call       {@code call}; {@link CallStrategy#AUTO} when absent
 * @param shared     settings shared by every operation
 * @param operations the operations, in file order
 */
public record OverlayFile(
        String path,
        Located<String> pcml,
        Located<String> version,
        java.util.Optional<Located<String>> title,
        Located<CallStrategy> call,
        OperationSettings shared,
        List<OperationSettings> operations) {

    /** @throws NullPointerException if an argument is {@code null} */
    public OverlayFile {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(pcml, "pcml");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(call, "call");
        Objects.requireNonNull(shared, "shared");
        operations = List.copyOf(operations);
    }
}
