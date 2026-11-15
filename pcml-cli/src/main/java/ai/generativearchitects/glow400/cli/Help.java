package ai.generativearchitects.glow400.cli;

import ai.generativearchitects.glow400.emit.descriptor.Tool;

/** The help texts of the tool and of each command. */
final class Help {

    private static final String COMMON = """
            Options for every command:
              --repo <dir>              Repository root, the folder with gateway.yaml and apis/ (default: the working directory)
              --format text|json|sarif  Report format (default: text)
              --report <file>           Write the report to this file instead of standard output
              --fail-on error|warning   Lowest severity that makes the command fail (default: error)
              --stacktrace              Print the stack trace of an unexpected failure
              --help                    Show this help
            """;

    private static final String EXIT_CODES = """
            Exit codes:
              0  completed, nothing at or above --fail-on
              1  diagnostics at or above --fail-on
              2  usage error
              3  unexpected failure
            """;

    private Help() {
    }

    static String general() {
        return "Usage: " + Tool.NAME + " <command> [options]\n"
                + "       " + Tool.NAME + " --version\n"
                + "\n"
                + "Commands:\n"
                + "  validate   Load the repository, validate it and report\n"
                + "  generate   Run the emitters named in gateway.yaml and write their files\n"
                + "  bundle     Pack the descriptor, the OpenAPI documents and the sources into one bundle file\n"
                + "  diff       Compare the repository with a released bundle\n"
                + "\n"
                + COMMON + "\n" + EXIT_CODES
                + "\n"
                + "The report goes to standard output or to --report; everything else goes to standard error.\n"
                + "Run '" + Tool.NAME + " <command> --help' for the options of a command.\n";
    }

    static String validate() {
        return "Usage: " + Tool.NAME + " validate [options]\n"
                + "\n"
                + "Loads the repository and validates it. Writes nothing but the report.\n"
                + "\n" + COMMON + "\n" + EXIT_CODES;
    }

    static String generate() {
        return "Usage: " + Tool.NAME + " generate --out <dir> [options]\n"
                + "\n"
                + "Runs the emitters named by 'emitters' in gateway.yaml (default: descriptor and openapi) and writes their files\n"
                + "under <dir>. Nothing is written if there is an error. Existing files are overwritten; nothing is deleted.\n"
                + "\n"
                + "Options:\n"
                + "  --out <dir>               Output folder; not the repository root and not inside apis/\n"
                + "\n" + COMMON + "\n" + EXIT_CODES;
    }

    static String bundle() {
        return "Usage: " + Tool.NAME + " bundle --name <name> --version <version> --out <file> [options]\n"
                + "\n"
                + "Runs the descriptor and OpenAPI emitters and packs their files with the PCML and overlay files into one bundle.\n"
                + "The file is written to a temporary file and moved into place, so a failed run leaves no partial bundle.\n"
                + "\n"
                + "Options:\n"
                + "  --name <name>                  Bundle name: lower-case letters, digits, '.', '_' and '-'\n"
                + "  --version <version>            Bundle version label: no white space, at most 64 characters\n"
                + "  --out <file>                   Bundle file to write\n"
                + "  --source-repository <text>     Where the repository comes from (optional, written to the manifest)\n"
                + "  --source-revision <text>       The revision of the repository (optional, written to the manifest)\n"
                + "\n" + COMMON + "\n" + EXIT_CODES;
    }

    static String diff() {
        return "Usage: " + Tool.NAME + " diff --against <bundle file> [options]\n"
                + "\n"
                + "Compares the repository with a released bundle and reports what changed for the consumers of each API, and whether\n"
                + "the version was raised enough. Writes nothing but the report.\n"
                + "\n"
                + "Options:\n"
                + "  --against <bundle file>   The released bundle\n"
                + "\n" + COMMON + "\n" + EXIT_CODES;
    }

    static String of(String command) {
        return switch (command) {
            case "validate" -> validate();
            case "generate" -> generate();
            case "bundle" -> bundle();
            default -> diff();
        };
    }
}
