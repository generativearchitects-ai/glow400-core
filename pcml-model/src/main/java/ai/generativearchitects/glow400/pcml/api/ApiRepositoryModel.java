package ai.generativearchitects.glow400.pcml.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The merged result of a repository: its {@code gateway.yaml} defaults and its APIs.
 *
 * <p>{@code operations} lists the operations of every API in API order, then file order. Built from operations alone,
 * the model groups them by overlay file into APIs named after the folder.
 *
 * @param defaults   repository-wide defaults
 * @param operations every operation of the repository
 * @param apis       the APIs, in folder order
 */
public record ApiRepositoryModel(GatewayDefaults defaults, List<ApiOperation> operations, List<ApiDefinition> apis) {

    /**
     * @throws NullPointerException     if an argument is {@code null}
     * @throws IllegalArgumentException if {@code operations} is not exactly the operations of {@code apis}
     */
    public ApiRepositoryModel {
        Objects.requireNonNull(defaults, "defaults");
        operations = List.copyOf(operations);
        apis = List.copyOf(apis);
        List<ApiOperation> grouped = new ArrayList<>();
        apis.forEach(api -> grouped.addAll(api.operations()));
        if (!grouped.equals(operations)) {
            throw new IllegalArgumentException("operations must be the operations of the APIs, in order");
        }
    }

    /**
     * A model whose APIs are derived from the operations: one API per overlay file, named after the folder that holds it,
     * titled with its name, with the version of its first operation.
     */
    public ApiRepositoryModel(GatewayDefaults defaults, List<ApiOperation> operations) {
        this(defaults, operations, group(operations));
    }

    private static List<ApiDefinition> group(List<ApiOperation> operations) {
        java.util.Map<String, List<ApiOperation>> byFile = new java.util.LinkedHashMap<>();
        for (ApiOperation operation : operations) {
            byFile.computeIfAbsent(operation.overlayPath(), key -> new ArrayList<>()).add(operation);
        }
        List<ApiDefinition> apis = new ArrayList<>();
        byFile.forEach((path, ops) -> {
            String name = apiName(path);
            apis.add(new ApiDefinition(name, name, ops.get(0).version(), path, ops));
        });
        return apis;
    }

    /** The API name for an overlay file: its folder name, lower-cased. */
    public static String apiName(String overlayPath) {
        String[] parts = overlayPath.split("/");
        String folder = parts.length >= 2 ? parts[parts.length - 2] : "api";
        return folder.toLowerCase(java.util.Locale.ROOT);
    }
}
