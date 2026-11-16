package ai.generativearchitects.slay400.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.generativearchitects.slay400.maven.Doubles.RecordingHelper;
import ai.generativearchitects.slay400.maven.Doubles.RecordingLog;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.maven.plugin.MojoFailureException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The goals as plain objects: skip, failOn, the log, the report file, what is written and what is attached. */
class MojoTest {

    private static final String OVL006 = "apis/ORDSTATUS/api.yaml:1:1: warning OVL006:";

    private static <T extends PcmlMojo> T prepare(T mojo, Path dir, Path repo, RecordingLog log) {
        mojo.project = Doubles.project(dir);
        mojo.repository = repo.toFile();
        mojo.setLog(log);
        return mojo;
    }

    private static ValidateMojo validate(Path dir, Path repo, RecordingLog log) {
        return prepare(new ValidateMojo(), dir, repo, log);
    }

    // ---- the defaults of the fields; the defaults Maven applies are in the plugin descriptor (PluginDescriptorTest)

    @Test
    void theFieldsStartAtTheDefaultsOfTheBrief() {
        ValidateMojo mojo = new ValidateMojo();
        assertThat(mojo.failOn).isEqualTo("error");
        assertThat(mojo.reportFormat).isEqualTo("json");
        assertThat(mojo.skip).isFalse();
        assertThat(mojo.reportFile).isNull();
        assertThat(new BundleMojo(new RecordingHelper()).attach).isTrue();
        assertThat(mojo.goal()).isEqualTo("validate");
        assertThat(new GenerateMojo().goal()).isEqualTo("generate");
        assertThat(new BundleMojo(null).goal()).isEqualTo("bundle");
        assertThat(new DiffMojo().goal()).isEqualTo("diff");
    }

    // ---- skip

    @Test
    void skipDoesNothingAndSaysSoAtInfoLevel(@TempDir Path dir) throws Exception {
        RecordingLog log = new RecordingLog();
        ValidateMojo mojo = validate(dir, dir.resolve("does-not-exist"), log);
        mojo.skip = true;
        mojo.execute();
        assertThat(log.info).containsExactly("pcml:validate skipped: pcml.skip is set");
        assertThat(log.warn).isEmpty();
        assertThat(log.error).isEmpty();
        GenerateMojo generate = prepare(new GenerateMojo(), dir, dir, log);
        generate.skip = true;
        generate.outputDirectory = dir.resolve("out").toFile();
        generate.execute();
        assertThat(log.info).contains("pcml:generate skipped: pcml.skip is set");
        assertThat(Files.exists(dir.resolve("out"))).isFalse();
    }

    // ---- the severities and the log levels

    @Test
    void aValidRepositoryLogsItsWarningsAtWarnLevelAndTheSummaryAtInfoAndSucceeds(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        RecordingLog log = new RecordingLog();
        validate(dir, repo, log).execute();
        assertThat(log.warn).hasSize(1);
        assertThat(log.warn.get(0)).startsWith("apis/ORDSTATUS/api.yaml:").contains("warning OVL006: Operation ordstatus has no error mapping");
        assertThat(log.info).containsExactly("pcml:validate: 0 errors, 1 warning, 0 notes");
        assertThat(log.error).isEmpty();
    }

    @Test
    void anInvalidRepositoryLogsEveryErrorAtErrorLevelInTheFormOfTheTextReportAndFailsWithTheCount(@TempDir Path dir) {
        Path repo = Support.repo("invalid/ovl012", dir.resolve("repo"));
        RecordingLog log = new RecordingLog();
        assertThatThrownBy(() -> validate(dir, repo, log).execute()).isInstanceOf(MojoFailureException.class)
                .hasMessageMatching("pcml:validate failed: [0-9]+ errors?, [0-9]+ warnings?, 0 notes");
        assertThat(log.error).isNotEmpty().allMatch(l -> l.matches("apis/[A-Za-z0-9/._-]+:[0-9]+:[0-9]+: error OVL[0-9]+: .*"));
        assertThat(log.info).isEmpty();
    }

    @Test
    void aNoteIsLoggedAtInfoLevelAndNeverFailsTheBuild(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Path released = dir.resolve("released.rpgapi");
        bundle(dir, repo, released, new RecordingLog(), new RecordingHelper());
        Path api = repo.resolve("apis/ORDSTATUS/api.yaml");
        Support.write(api, Support.read(api).replace("P0STAT: { name: status }", "P0STAT: { name: state }").replace("version: 1.0.0", "version: 2.0.0"));
        RecordingLog log = new RecordingLog();
        DiffMojo mojo = prepare(new DiffMojo(), dir, repo, log);
        mojo.against = released.toFile();
        mojo.execute();
        assertThat(log.info).anyMatch(l -> l.contains(": note API003: API ordstatus: "));
        assertThat(log.warn).anyMatch(l -> l.contains("warning OVL006"));
        assertThat(log.error).isEmpty();
    }

    @Test
    void failOnWarningFailsTheBuildForAWarningAndErrorDoesNot(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        ValidateMojo strict = validate(dir, repo, new RecordingLog());
        strict.failOn = "warning";
        assertThatThrownBy(strict::execute).isInstanceOf(MojoFailureException.class).hasMessage("pcml:validate failed: 0 errors, 1 warning, 0 notes");
        validate(dir, repo, new RecordingLog()).execute();
    }

    @Test
    void anUnknownFailOnOrReportFormatIsAFailureOfTheBuildWithTheValuesAllowed(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        ValidateMojo failOn = validate(dir, repo, new RecordingLog());
        failOn.failOn = "note";
        assertThatThrownBy(failOn::execute).isInstanceOf(MojoFailureException.class).hasMessage("The value of failOn must be error or warning");
        ValidateMojo format = validate(dir, repo, new RecordingLog());
        format.reportFormat = "text";
        assertThatThrownBy(format::execute).isInstanceOf(MojoFailureException.class).hasMessage("The value of reportFormat must be json or sarif");
    }

    @Test
    void namesFromTheRepositoryAreEscapedInTheLog(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(repo.resolve("apis/ORDSTATUS/api.yaml"), Support.read(repo.resolve("apis/ORDSTATUS/api.yaml")).replace("P0STAT: { name: status }", "P0STAT: { name: status }\n  \"P0\\eX\": {}"));
        RecordingLog log = new RecordingLog();
        assertThatThrownBy(() -> validate(dir, repo, log).execute()).isInstanceOf(MojoFailureException.class);
        assertThat(String.join("\n", log.error)).contains("P0\\u001bX").doesNotContain("\u001b");
    }

    // ---- the report file

    @Test
    void theReportFileIsWrittenAsJsonOrSarifAndItsFolderIsMade(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        ValidateMojo json = validate(dir, repo, new RecordingLog());
        json.reportFile = dir.resolve("target/reports/pcml.json").toFile();
        json.execute();
        String text = Support.read(dir.resolve("target/reports/pcml.json"));
        assertThat(text).startsWith("{").contains("\"command\": \"validate\"").contains("OVL006");
        ValidateMojo sarif = validate(dir, repo, new RecordingLog());
        sarif.reportFile = dir.resolve("target/pcml.sarif").toFile();
        sarif.reportFormat = "sarif";
        sarif.execute();
        assertThat(Support.read(dir.resolve("target/pcml.sarif"))).contains("\"version\": \"2.1.0\"").contains("OVL006");
    }

    @Test
    void noReportFileIsWrittenUnlessAskedFor(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        validate(dir, repo, new RecordingLog()).execute();
        assertThat(Support.names(dir)).noneMatch(n -> n.endsWith(".json") || n.endsWith(".sarif"));
    }

    @Test
    void aReportFileThatCannotBeWrittenIsCli003AndFailsTheBuild(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Support.write(dir.resolve("blocker"), "a file");
        RecordingLog log = new RecordingLog();
        ValidateMojo mojo = validate(dir, repo, log);
        mojo.reportFile = dir.resolve("blocker/report.json").toFile();
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class).hasMessage("pcml:validate failed: 1 error, 1 warning, 0 notes");
        assertThat(log.error).singleElement().satisfies(l -> assertThat(l).startsWith("blocker/report.json:1:1: error CLI003: The report file blocker/report.json could not be written: "));
    }

    @Test
    void theReportFileHoldsTheDiagnosticsOfAFailedGoalToo(@TempDir Path dir) {
        Path repo = Support.repo("invalid/ovl012", dir.resolve("repo"));
        ValidateMojo mojo = validate(dir, repo, new RecordingLog());
        mojo.reportFile = dir.resolve("report.json").toFile();
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
        assertThat(Support.read(dir.resolve("report.json"))).contains("OVL012");
    }

    // ---- generate

    @Test
    void generateWritesTheEmittedFilesToTheOutputDirectoryOnly(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("kitchen-sink", dir.resolve("repo"));
        RecordingLog log = new RecordingLog();
        GenerateMojo mojo = prepare(new GenerateMojo(), dir, repo, log);
        mojo.outputDirectory = dir.resolve("target/pcml").toFile();
        mojo.execute();
        assertThat(Support.names(dir.resolve("target/pcml"))).contains("descriptor.json", "openapi/catalog.yaml");
        assertThat(Support.names(dir)).noneMatch(n -> !n.startsWith("repo") && !n.equals("") && !n.startsWith("target"));
        assertThat(Support.names(repo)).doesNotContain("descriptor.json");
    }

    @Test
    void generateFailsOnAnInvalidRepositoryAndWritesNothing(@TempDir Path dir) {
        Path repo = Support.repo("invalid/ovl012", dir.resolve("repo"));
        GenerateMojo mojo = prepare(new GenerateMojo(), dir, repo, new RecordingLog());
        mojo.outputDirectory = dir.resolve("target/pcml").toFile();
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class).hasMessageStartingWith("pcml:generate failed: ");
        assertThat(Files.exists(dir.resolve("target"))).isFalse();
    }

    @Test
    void generateRefusesTheRepositoryRootAsItsOutputDirectoryWithCli002(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        RecordingLog log = new RecordingLog();
        GenerateMojo mojo = prepare(new GenerateMojo(), dir, repo, log);
        mojo.outputDirectory = repo.toFile();
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
        assertThat(log.error).anyMatch(l -> l.equals("repo:1:1: error CLI002: The output folder repo is the repository root"));
    }

    // ---- bundle

    private static BundleMojo bundle(Path dir, Path repo, Path file, RecordingLog log, RecordingHelper helper) throws Exception {
        BundleMojo mojo = prepare(new BundleMojo(helper), dir, repo, log);
        mojo.bundleName = "orders";
        mojo.bundleVersion = "1.0";
        mojo.bundleFile = file.toFile();
        mojo.execute();
        return mojo;
    }

    @Test
    void bundleWritesTheFileAndAttachesItWithTheTypeRpgapi(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        RecordingHelper helper = new RecordingHelper();
        RecordingLog log = new RecordingLog();
        BundleMojo mojo = bundle(dir, repo, dir.resolve("target/orders-1.0.rpgapi"), log, helper);
        assertThat(Support.bytes(dir.resolve("target/orders-1.0.rpgapi"))).startsWith((byte) 'P', (byte) 'K');
        assertThat(helper.attached).hasSize(1);
        Doubles.Attached attached = helper.attached.get(0);
        assertThat(attached.type()).isEqualTo("rpgapi");
        assertThat(attached.classifier()).isNull();
        assertThat(attached.file()).isEqualTo(dir.resolve("target/orders-1.0.rpgapi").toFile());
        assertThat(attached.project()).isSameAs(mojo.project);
        assertThat(log.info).contains("pcml:bundle: attached orders-1.0.rpgapi with type rpgapi");
    }

    @Test
    void attachFalseWritesTheFileAndAttachesNothing(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        RecordingHelper helper = new RecordingHelper();
        BundleMojo mojo = prepare(new BundleMojo(helper), dir, repo, new RecordingLog());
        mojo.bundleName = "orders";
        mojo.bundleVersion = "1.0";
        mojo.bundleFile = dir.resolve("target/b.rpgapi").toFile();
        mojo.attach = false;
        mojo.execute();
        assertThat(dir.resolve("target/b.rpgapi")).isRegularFile();
        assertThat(helper.attached).isEmpty();
    }

    @Test
    void aFailedBundleAttachesNothing(@TempDir Path dir) {
        Path repo = Support.repo("invalid/ovl012", dir.resolve("repo"));
        RecordingHelper helper = new RecordingHelper();
        assertThatThrownBy(() -> bundle(dir, repo, dir.resolve("target/b.rpgapi"), new RecordingLog(), helper)).isInstanceOf(MojoFailureException.class);
        assertThat(helper.attached).isEmpty();
        assertThat(Files.exists(dir.resolve("target"))).isFalse();
    }

    @Test
    void aBundleThatWasWrittenButFailsTheBuildIsNotAttached(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        RecordingHelper helper = new RecordingHelper();
        BundleMojo mojo = prepare(new BundleMojo(helper), dir, repo, new RecordingLog());
        mojo.bundleName = "orders";
        mojo.bundleVersion = "1.0";
        mojo.bundleFile = dir.resolve("target/b.rpgapi").toFile();
        mojo.failOn = "warning";
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
        assertThat(helper.attached).isEmpty();
    }

    @Test
    void anInvalidBundleNameIsBdl006AndFailsTheBuild(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        BundleMojo mojo = prepare(new BundleMojo(new RecordingHelper()), dir, repo, new RecordingLog());
        mojo.bundleName = "Orders";
        mojo.bundleVersion = "1";
        mojo.bundleFile = dir.resolve("target/b.rpgapi").toFile();
        RecordingLog log = (RecordingLog) mojo.getLog();
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
        assertThat(log.error).anyMatch(l -> l.contains("error BDL006: The bundle name must match"));
    }

    @Test
    void theSourceOptionsAreRecordedInTheManifest(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        BundleMojo mojo = prepare(new BundleMojo(new RecordingHelper()), dir, repo, new RecordingLog());
        mojo.bundleName = "orders";
        mojo.bundleVersion = "1";
        mojo.bundleFile = dir.resolve("b.rpgapi").toFile();
        mojo.sourceRepository = "https://example.test/orders";
        mojo.sourceRevision = "abc123";
        mojo.execute();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(dir.resolve("b.rpgapi").toFile())) {
            String manifest = new String(zip.getInputStream(zip.getEntry("manifest.json")).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(manifest).contains("https://example.test/orders").contains("abc123");
        }
    }

    @Test
    void theMockFilesTravelWithTheBundleOfAMockedRepository(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("mocked", dir.resolve("repo"));
        bundle(dir, repo, dir.resolve("b.rpgapi"), new RecordingLog(), new RecordingHelper());
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(dir.resolve("b.rpgapi").toFile())) {
            assertThat(zip.stream().map(java.util.zip.ZipEntry::getName)).contains("mock/orders.json");
        }
    }

    // ---- diff

    @Test
    void diffNeedsTheAgainstParameter(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        DiffMojo mojo = prepare(new DiffMojo(), dir, repo, new RecordingLog());
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class).hasMessage("The parameter 'against' is required by pcml:diff");
    }

    @Test
    void diffFailsOnABreakingChangeWithoutAMajorIncreaseAndPassesWithIt(@TempDir Path dir) throws Exception {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        Path released = dir.resolve("released.rpgapi");
        bundle(dir, repo, released, new RecordingLog(), new RecordingHelper());
        Path api = repo.resolve("apis/ORDSTATUS/api.yaml");
        Support.write(api, Support.read(api).replace("P0STAT: { name: status }", "P0STAT: { name: state }"));
        RecordingLog log = new RecordingLog();
        DiffMojo breaking = prepare(new DiffMojo(), dir, repo, log);
        breaking.against = released.toFile();
        assertThatThrownBy(breaking::execute).isInstanceOf(MojoFailureException.class);
        assertThat(log.error).anyMatch(l -> l.contains("error API002: API ordstatus has breaking changes but its version goes from 1.0.0 to 1.0.0; the major version must increase."));
        Support.write(api, Support.read(api).replace("version: 1.0.0", "version: 2.0.0"));
        DiffMojo raised = prepare(new DiffMojo(), dir, repo, new RecordingLog());
        raised.against = released.toFile();
        raised.execute();
    }

    @Test
    void aReleasedBundleThatCannotBeReadIsReportedWithItsCode(@TempDir Path dir) {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        RecordingLog log = new RecordingLog();
        DiffMojo mojo = prepare(new DiffMojo(), dir, repo, log);
        mojo.against = dir.resolve("nothing.rpgapi").toFile();
        assertThatThrownBy(mojo::execute).isInstanceOf(MojoFailureException.class);
        assertThat(log.error).anyMatch(l -> l.contains("error BDL001: The file cannot be read"));
    }

    // ---- unexpected failures and state

    @Test
    void anUnexpectedFailureIsAMojoExecutionExceptionWithoutAPathInItsMessage(@TempDir Path dir) throws IOException {
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        GenerateMojo mojo = new GenerateMojo() {
            @Override
            ai.generativearchitects.slay400.tasks.TaskResult run(ai.generativearchitects.slay400.tasks.Tasks tasks) throws IOException {
                throw new java.nio.file.AccessDeniedException("/secret/path");
            }
        };
        mojo.project = Doubles.project(dir);
        mojo.repository = repo.toFile();
        mojo.setLog(new RecordingLog());
        assertThatThrownBy(mojo::execute).isInstanceOf(org.apache.maven.plugin.MojoExecutionException.class)
                .hasMessage("pcml:generate failed: access denied").hasCauseInstanceOf(java.nio.file.AccessDeniedException.class);
    }

    @Test
    void theGoalsKeepNoStaticState() {
        for (Class<?> type : List.of(PcmlMojo.class, ValidateMojo.class, GenerateMojo.class, BundleMojo.class, DiffMojo.class)) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!field.isSynthetic() && java.lang.reflect.Modifier.isStatic(field.getModifiers())) { // synthetic: added by coverage tools, not by us
                    assertThat(java.lang.reflect.Modifier.isFinal(field.getModifiers())).as(type.getSimpleName() + "." + field.getName()).isTrue();
                    assertThat(field.getType().isPrimitive() || field.getType() == String.class).as(type.getSimpleName() + "." + field.getName()).isTrue();
                }
            }
        }
    }

    @Test
    void aRelativeRepositoryAndOutputAreTheOnesTheCallerGave(@TempDir Path dir) throws Exception {
        // Maven resolves relative File parameters against the base directory before the goal runs; the goal takes them as they come
        Path repo = Support.repo("minimal", dir.resolve("repo"));
        GenerateMojo mojo = prepare(new GenerateMojo(), dir, repo, new RecordingLog());
        mojo.outputDirectory = new File(dir.toFile(), "target/pcml");
        mojo.execute();
        assertThat(dir.resolve("target/pcml/descriptor.json")).isRegularFile();
    }
}
