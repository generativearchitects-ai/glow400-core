package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.pcml.diagnostics.Severity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The command and the options of one command line, parsed by hand. */
final class Options {

    static final List<String> COMMANDS = List.of("init", "annotate", "validate", "generate", "bundle", "diff");

    private static final Set<String> FLAGS = Set.of("--help", "--stacktrace");
    /** Flags of the commands that write files. */
    private static final Set<String> WRITING_FLAGS = Set.of("--dry-run");
    private static final Set<String> COMMON = Set.of("--repo", "--format", "--report", "--fail-on");

    private final String command;
    private final Map<String, String> values;
    private final Set<String> flags;

    private Options(String command, Map<String, String> values, Set<String> flags) {
        this.command = command;
        this.values = values;
        this.flags = flags;
    }

    /**
     * Parses the arguments after the command name.
     *
     * @throws UsageException for an unknown option, a value that is missing or invalid, a repeated option or a stray argument
     */
    static Options parse(String command, List<String> args) throws UsageException {
        Set<String> allowed = new java.util.HashSet<>(COMMON);
        switch (command) {
            case "generate" -> allowed.add("--out");
            case "bundle" -> allowed.addAll(Set.of("--name", "--version", "--out", "--source-repository", "--source-revision"));
            case "diff" -> allowed.add("--against");
            case "annotate" -> allowed.add("--api");
            default -> { }
        }
        Set<String> flagNames = new java.util.HashSet<>(FLAGS);
        if (command.equals("init") || command.equals("annotate")) {
            flagNames.addAll(WRITING_FLAGS);
        }
        Map<String, String> values = new HashMap<>();
        Set<String> flags = new java.util.HashSet<>();
        for (int i = 0; i < args.size(); i++) {
            String token = args.get(i);
            if (!token.startsWith("--")) {
                throw new UsageException("Unexpected argument '" + shown(token) + "'");
            }
            String[] parts = token.split("=", 2);
            String name = parts[0];
            boolean hasValue = parts.length == 2;
            if (flagNames.contains(name)) {
                if (hasValue) {
                    throw new UsageException("The option " + name + " takes no value");
                }
                flags.add(name);
                continue;
            }
            if (!allowed.contains(name)) {
                throw new UsageException("Unknown option '" + shown(name) + "' for " + command);
            }
            String value;
            if (hasValue) {
                value = parts[1];
            } else if (i + 1 < args.size() && !args.get(i + 1).startsWith("--")) {
                value = args.get(++i);
            } else {
                throw new UsageException("The option " + name + " needs a value");
            }
            if (values.put(name, value) != null) {
                throw new UsageException("The option " + name + " is given more than once");
            }
        }
        Options options = new Options(command, values, flags);
        options.check();
        return options;
    }

    private void check() throws UsageException {
        if (flags.contains("--help")) {
            return;
        }
        String format = values.getOrDefault("--format", "text");
        if (!List.of("text", "json", "sarif").contains(format)) {
            throw new UsageException("The value of --format must be text, json or sarif");
        }
        String failOn = values.getOrDefault("--fail-on", "error");
        if (!List.of("error", "warning").contains(failOn)) {
            throw new UsageException("The value of --fail-on must be error or warning");
        }
        for (String required : switch (command) {
            case "generate" -> List.of("--out");
            case "bundle" -> List.of("--name", "--version", "--out");
            case "diff" -> List.of("--against");
            default -> List.<String>of();
        }) {
            if (!values.containsKey(required)) {
                throw new UsageException("The command " + command + " needs the option " + required);
            }
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue().isEmpty() && !entry.getKey().startsWith("--source-")) {
                throw new UsageException("The value of " + entry.getKey() + " must not be empty");
            }
        }
    }

    boolean help() {
        return flags.contains("--help");
    }

    boolean flag(String name) {
        return flags.contains(name);
    }

    Optional<String> value(String name) {
        return Optional.ofNullable(values.get(name));
    }

    String required(String name) {
        return values.get(name);
    }

    String format() {
        return values.getOrDefault("--format", "text");
    }

    /** The lowest severity that makes a command fail. */
    Severity failOn() {
        return values.getOrDefault("--fail-on", "error").equals("warning") ? Severity.WARNING : Severity.ERROR;
    }

    /** A name from the command line for a message: escaped, and cut at 60 characters. */
    static String shown(String text) {
        return ai.generativearchitects.glow400.report.TextReport.escape(text.length() > 60 ? text.substring(0, 60) + "..." : text);
    }
}
