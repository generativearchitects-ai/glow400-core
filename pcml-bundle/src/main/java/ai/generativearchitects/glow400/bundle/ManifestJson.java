package ai.generativearchitects.glow400.bundle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** The manifest as JSON: the tree to write and the strict reading of an untrusted one. */
final class ManifestJson {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");

    private ManifestJson() {
    }

    /** A problem with the manifest: {@code BDL002} for a version this code does not read, {@code BDL003} for anything else. */
    static final class Invalid extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        Invalid(String code, String message) {
            super(message, null, false, false);
            this.code = code;
        }

        String code() {
            return code;
        }
    }

    // ------------------------------------------------------------------ writing

    static Map<String, Object> tree(Manifest m) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("bundleSchemaVersion", m.bundleSchemaVersion());
        root.put("name", m.name());
        root.put("version", m.version());
        Map<String, Object> generator = new LinkedHashMap<>();
        generator.put("name", m.generatorName());
        generator.put("version", m.generatorVersion());
        root.put("generator", generator);
        root.put("descriptorSchemaVersion", m.descriptorSchemaVersion());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("repository", m.repository().orElse(null));
        source.put("revision", m.revision().orElse(null));
        root.put("source", source);
        List<Object> apis = new ArrayList<>();
        for (Manifest.ApiEntry api : m.apis()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", api.name());
            entry.put("title", api.title());
            entry.put("version", api.version());
            apis.add(entry);
        }
        root.put("apis", apis);
        List<Object> files = new ArrayList<>();
        for (Manifest.FileEntry file : m.files()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("path", file.path());
            entry.put("size", file.size());
            entry.put("sha256", file.sha256());
            files.add(entry);
        }
        root.put("files", files);
        root.put("contentDigest", m.contentDigest());
        return root;
    }

    // ------------------------------------------------------------------ reading

    /** The manifest of a parsed JSON document. */
    static Manifest read(Object tree) {
        Map<String, Object> root = object(tree, "manifest");
        // The versions are looked at before the rest, so that a manifest of another version is reported as such
        for (String key : List.of("bundleSchemaVersion", "descriptorSchemaVersion")) {
            Object version = root.get(key);
            if (version instanceof Number n && (n instanceof Integer || n instanceof Long) && n.longValue() != BundleFormat.SCHEMA_VERSION) {
                throw new Invalid("BDL002", "Unsupported " + key + " " + n + "; this reader supports version " + BundleFormat.SCHEMA_VERSION);
            }
        }
        Set<String> seen = new HashSet<>();
        int bundleVersion = integer(root, "bundleSchemaVersion", seen);
        String name = string(root, "name", seen);
        if (!BundleInfo.NAME.matcher(name).matches()) {
            throw new Invalid("BDL003", "manifest.name: must match [a-z0-9][a-z0-9._-]*");
        }
        String version = string(root, "version", seen);
        if (new BundleInfo(name, version, Optional.empty(), Optional.empty()).problems().size() > 0) {
            throw new Invalid("BDL003", "manifest.version: must be 1 to " + BundleInfo.MAX_VERSION_LENGTH + " characters without white space");
        }
        Map<String, Object> generator = object(take(root, "generator", seen), "manifest.generator");
        Set<String> inGenerator = new HashSet<>();
        String generatorName = string(generator, "name", inGenerator);
        String generatorVersion = string(generator, "version", inGenerator);
        done(generator, inGenerator, "manifest.generator");
        int descriptorVersion = integer(root, "descriptorSchemaVersion", seen);
        Map<String, Object> source = object(take(root, "source", seen), "manifest.source");
        Set<String> inSource = new HashSet<>();
        Optional<String> repository = nullableString(source, "repository", inSource);
        Optional<String> revision = nullableString(source, "revision", inSource);
        done(source, inSource, "manifest.source");
        List<Manifest.ApiEntry> apis = new ArrayList<>();
        for (Object item : array(root, "apis", seen)) {
            Map<String, Object> api = object(item, "manifest.apis[]");
            Set<String> inApi = new HashSet<>();
            apis.add(new Manifest.ApiEntry(string(api, "name", inApi), string(api, "title", inApi), string(api, "version", inApi)));
            done(api, inApi, "manifest.apis[]");
        }
        List<Manifest.FileEntry> files = new ArrayList<>();
        for (Object item : array(root, "files", seen)) {
            Map<String, Object> file = object(item, "manifest.files[]");
            Set<String> inFile = new HashSet<>();
            String path = string(file, "path", inFile);
            Object size = file.get("size");
            inFile.add("size");
            if (!(size instanceof Integer || size instanceof Long) || ((Number) size).longValue() < 0) {
                throw new Invalid("BDL003", "manifest.files[].size: expected a whole number that is not negative");
            }
            String sha = string(file, "sha256", inFile);
            if (!SHA256.matcher(sha).matches()) {
                throw new Invalid("BDL003", "manifest.files[].sha256: expected 64 lower-case hexadecimal digits");
            }
            done(file, inFile, "manifest.files[]");
            files.add(new Manifest.FileEntry(path, ((Number) size).longValue(), sha));
        }
        String digest = string(root, "contentDigest", seen);
        if (!DIGEST.matcher(digest).matches()) {
            throw new Invalid("BDL003", "manifest.contentDigest: expected sha256: and 64 lower-case hexadecimal digits");
        }
        done(root, seen, "manifest");
        return new Manifest(bundleVersion, name, version, generatorName, generatorVersion, descriptorVersion, repository, revision, apis, files, digest);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object node, String where) {
        if (!(node instanceof Map<?, ?> map)) {
            throw new Invalid("BDL003", where + ": expected an object");
        }
        return (Map<String, Object>) map;
    }

    private static Object take(Map<String, Object> map, String key, Set<String> seen) {
        if (!map.containsKey(key)) {
            throw new Invalid("BDL003", "manifest: missing key '" + key + "'");
        }
        seen.add(key);
        return map.get(key);
    }

    private static String string(Map<String, Object> map, String key, Set<String> seen) {
        if (!(take(map, key, seen) instanceof String text)) {
            throw new Invalid("BDL003", "manifest: '" + key + "' must be a string");
        }
        return text;
    }

    private static Optional<String> nullableString(Map<String, Object> map, String key, Set<String> seen) {
        Object value = take(map, key, seen);
        if (value != null && !(value instanceof String)) {
            throw new Invalid("BDL003", "manifest: '" + key + "' must be a string or null");
        }
        return Optional.ofNullable((String) value);
    }

    private static int integer(Map<String, Object> map, String key, Set<String> seen) {
        if (!(take(map, key, seen) instanceof Integer number)) {
            throw new Invalid("BDL003", "manifest: '" + key + "' must be a whole number");
        }
        return number;
    }

    private static List<Object> array(Map<String, Object> map, String key, Set<String> seen) {
        if (!(take(map, key, seen) instanceof List<?> list)) {
            throw new Invalid("BDL003", "manifest: '" + key + "' must be an array");
        }
        return new ArrayList<>(list);
    }

    private static void done(Map<String, Object> map, Set<String> seen, String where) {
        for (String key : map.keySet()) {
            if (!seen.contains(key)) {
                throw new Invalid("BDL003", where + ": unexpected key '" + key + "'");
            }
        }
    }
}
