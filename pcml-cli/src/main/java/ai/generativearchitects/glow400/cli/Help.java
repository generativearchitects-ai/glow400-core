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
                + "  init       Write gateway.yaml and a first api.yaml for every PCML file that has none\n"
                + "  annotate   Ask which way each inputoutput parameter goes and write the answers into api.yaml\n"
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

    static String init() {
        return "Usage: " + Tool.NAME + " init [--dry-run] [options]\n"
                + "\n"
                + "Writes gateway.yaml, if there is none, and an api.yaml in every folder directly under apis/ that holds exactly one\n"
                + "PCML file and no api.yaml or api.yml. Nothing that exists is overwritten or deleted: what is there is listed as kept.\n"
                + "A folder with no PCML file, or more than one, is skipped (CLI004). The repository does not have to be valid.\n"
                + "Every file is written to a temporary file and moved into place.\n"
                + "\n"
                + "The overlay has one operation per program, POST /<program>, and one line per parameter. A parameter that is\n"
                + "inputoutput has no direction yet: run annotate, or add one by hand. gateway.yaml sets defaults.inputoutput to fail,\n"
                + "so the repository does not validate until every such parameter has one.\n"
                + "\n"
                + "Options:\n"
                + "  --dry-run                 Write nothing; say what would be created\n"
                + "\n" + COMMON + "\n" + EXIT_CODES;
    }

    static String annotate() {
        return "Usage: " + Tool.NAME + " annotate [--api <folder>] [--dry-run] [options]\n"
                + "\n"
                + "For every top-level parameter whose PCML usage is inputoutput and that has no direction in api.yaml, asks which way\n"
                + "it goes, and writes the answers into api.yaml. Only the parameter entries are changed: comments, blank lines, key\n"
                + "order, quoting, indentation and line endings are kept. An entry written in a way that cannot be edited safely is\n"
                + "left alone and reported (SCF001); a file whose edited text does not pass the check is not written (SCF002).\n"
                + "\n"
                + "The questions go to standard error and the answers are read from standard input, one line each:\n"
                + "  direction  request, response, both or internal; Enter skips the parameter\n"
                + "  type       one of the types the wire type can carry; Enter keeps the default (not asked for a struct or internal)\n"
                + "  name       the name in the API; Enter keeps the default (not asked for internal)\n"
                + "An answer may be in any case and shortened to the beginning of one choice. The end of the input ends the\n"
                + "questions; what was answered is kept.\n"
                + "\n"
                + "It stops before the first question, with the report, if the repository has an error other than OVL001. At the end\n"
                + "the repository is loaded and validated again, so the command succeeds only when the repository validates.\n"
                + "\n"
                + "Options:\n"
                + "  --api <folder>            Only the API in this folder under apis/\n"
                + "  --dry-run                 Write nothing\n"
                + "\n" + COMMON + "\n" + EXIT_CODES;
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
            case "init" -> init();
            case "annotate" -> annotate();
            case "validate" -> validate();
            case "generate" -> generate();
            case "bundle" -> bundle();
            default -> diff();
        };
    }
}
