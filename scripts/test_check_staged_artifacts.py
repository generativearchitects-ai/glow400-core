#!/usr/bin/env python3
"""Tests of check_staged_artifacts.py: a deployment is built from the POMs of this repository and tiny jars, then spoiled in
one way at a time. Needs Python 3 and nothing else; run with: python3 -m unittest discover -s scripts"""
import shutil
import tempfile
import unittest
import zipfile
from pathlib import Path

import check_staged_artifacts as checker

REPO = Path(__file__).resolve().parent.parent
VERSION = checker.project_version(REPO)
ARTIFACTS = checker.published_artifacts(REPO)
CLI_MANIFEST = "Manifest-Version: 1.0\nMain-Class: ai.generativearchitects.slay400.cli.Main\n"


def write_jar(path, with_license=True, with_notice=True, cli=False):
    entries = {"a/B.class": b"x"}
    if with_license:
        entries["META-INF/LICENSE"] = b"licence"
    if with_notice:
        entries["META-INF/NOTICE"] = b"notice"
    if cli:
        entries["META-INF/MANIFEST.MF"] = CLI_MANIFEST.encode()
        for entry in checker.CLI_CONTENT:
            entries[entry] = b"x"
    with zipfile.ZipFile(path, "w") as z:
        for name, data in entries.items():
            z.writestr(name, data)


class StagedArtifactsTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.staging = Path(self.tmp.name) / "staging"
        for artifact in ARTIFACTS:
            folder = self.folder(artifact)
            folder.mkdir(parents=True)
            source = REPO / "pom.xml" if artifact == checker.ROOT else REPO / artifact / "pom.xml"
            shutil.copy(source, folder / f"{artifact}-{VERSION}.pom")
            (folder / "maven-metadata-central-staging.xml").write_text("<metadata/>")
            if artifact not in (checker.ROOT, checker.BOM):
                for suffix in ("", "-sources", "-javadoc"):
                    write_jar(folder / f"{artifact}-{VERSION}{suffix}.jar", cli=(artifact == checker.CLI and suffix == ""))

    def folder(self, artifact, version=VERSION):
        return self.staging / checker.GROUP_PATH / artifact / version

    def problems(self, signed=False):
        return checker.check(self.staging, REPO, signed=signed)

    def edit_pom(self, artifact, old, new):
        path = self.folder(artifact) / f"{artifact}-{VERSION}.pom"
        text = path.read_text()
        self.assertIn(old, text)
        path.write_text(text.replace(old, new, 1))

    def test_a_complete_deployment_has_no_problems(self):
        self.assertEqual([], self.problems())

    def test_the_root_pom_and_every_module_are_checked_once(self):
        self.assertEqual(checker.ROOT, ARTIFACTS[0])
        self.assertIn("pcml-model", ARTIFACTS)
        self.assertIn(checker.BOM, ARTIFACTS)
        self.assertEqual(len(set(ARTIFACTS)), len(ARTIFACTS))

    def test_the_checksum_files_of_a_release_are_ignored(self):
        for artifact in ARTIFACTS:
            for f in list(self.folder(artifact).iterdir()):
                for suffix in checker.CHECKSUM_SUFFIXES:
                    (self.folder(artifact) / (f.name + suffix)).write_text("checksum")
        self.assertEqual([], self.problems())

    def test_a_missing_sources_jar_is_found(self):
        (self.folder("pcml-codec") / f"pcml-codec-{VERSION}-sources.jar").unlink()
        self.assertEqual([f"pcml-codec: pcml-codec-{VERSION}-sources.jar is missing"], self.problems())

    def test_a_missing_javadoc_jar_and_a_missing_jar_are_found(self):
        (self.folder("pcml-diff") / f"pcml-diff-{VERSION}-javadoc.jar").unlink()
        (self.folder("pcml-diff") / f"pcml-diff-{VERSION}.jar").unlink()
        self.assertEqual(
            [f"pcml-diff: pcml-diff-{VERSION}-javadoc.jar is missing", f"pcml-diff: pcml-diff-{VERSION}.jar is missing"],
            self.problems())

    def test_a_missing_artifact_and_an_unknown_one_are_found(self):
        shutil.rmtree(self.staging / checker.GROUP_PATH / "pcml-mock")
        (self.staging / checker.GROUP_PATH / "pcml-extra").mkdir()
        self.assertEqual(["pcml-mock: not staged", "pcml-extra: staged, but it is not the root pom or a module"], self.problems())

    def test_a_jar_without_licence_or_notice_is_found_in_every_kind_of_jar(self):
        write_jar(self.folder("pcml-model") / f"pcml-model-{VERSION}.jar", with_license=False)
        write_jar(self.folder("pcml-model") / f"pcml-model-{VERSION}-sources.jar", with_notice=False)
        write_jar(self.folder("pcml-model") / f"pcml-model-{VERSION}-javadoc.jar", with_license=False, with_notice=False)
        self.assertEqual(sorted([
            f"pcml-model: pcml-model-{VERSION}.jar has no META-INF/LICENSE",
            f"pcml-model: pcml-model-{VERSION}-sources.jar has no META-INF/NOTICE",
            f"pcml-model: pcml-model-{VERSION}-javadoc.jar has no META-INF/LICENSE",
            f"pcml-model: pcml-model-{VERSION}-javadoc.jar has no META-INF/NOTICE",
        ]), sorted(self.problems()))

    def test_a_file_that_is_not_a_jar_is_found(self):
        (self.folder("pcml-model") / f"pcml-model-{VERSION}.jar").write_text("not a zip")
        problems = self.problems()
        self.assertEqual(1, len(problems))
        self.assertIn("cannot be read as a jar", problems[0])

    def test_a_test_jar_is_refused(self):
        write_jar(self.folder("pcml-bundle") / f"pcml-bundle-{VERSION}-tests.jar")
        self.assertEqual([f"pcml-bundle: pcml-bundle-{VERSION}-tests.jar is not expected (a test jar, or a file nobody asked for)"],
                         self.problems())

    def test_the_command_line_jar_must_be_the_one_with_its_dependencies(self):
        write_jar(self.folder(checker.CLI) / f"{checker.CLI}-{VERSION}.jar")  # a plain jar: no Main-Class, nothing inside
        problems = self.problems()
        self.assertIn(f"pcml-cli: pcml-cli-{VERSION}.jar has no Main-Class in its manifest", problems)
        self.assertTrue(any("does not contain com/fasterxml/jackson/core/JsonFactory.class" in p for p in problems))

    def test_a_pom_without_what_central_requires_is_found(self):
        self.edit_pom(checker.ROOT, "<url>https://github.com/generativearchitects-ai/slay400-core</url>\n  <inceptionYear>",
                      "<inceptionYear>")
        self.assertEqual([f"{checker.ROOT}: the POM has no url"], self.problems())

    def test_a_module_takes_what_is_missing_in_its_pom_from_its_parent(self):
        # the modules have no <url> or <scm> of their own, and that is fine because the parent has them
        text = (self.folder("pcml-model") / f"pcml-model-{VERSION}.pom").read_text()
        self.assertNotIn("<scm>", text)
        self.assertEqual([], self.problems())

    def test_the_bom_must_list_exactly_the_modules(self):
        self.edit_pom(checker.BOM, "<artifactId>pcml-mock</artifactId>", "<artifactId>pcml-mocks</artifactId>")
        problems = self.problems()
        self.assertIn("pcml-bom does not list the module pcml-mock", problems)
        self.assertIn("pcml-bom lists pcml-mocks, which is not a published module", problems)

    def test_the_bom_must_use_the_project_version_and_the_plugin_type(self):
        self.edit_pom(checker.BOM, "<artifactId>pcml-model</artifactId>\n        <version>${project.version}</version>",
                      "<artifactId>pcml-model</artifactId>\n        <version>1.0</version>")
        self.edit_pom(checker.BOM, "<type>maven-plugin</type>", "<type>jar</type>")
        problems = self.problems()
        self.assertIn("pcml-bom gives pcml-model the version 1.0, expected ${project.version}", problems)
        self.assertIn("pcml-bom lists pcml-maven-plugin with type jar, expected maven-plugin", problems)

    def test_a_deployment_with_another_version_is_found(self):
        parent = self.folder("pcml-parser").parent
        (parent / VERSION).rename(parent / "9.9")
        self.assertEqual(["pcml-parser: no folder for the version " + VERSION], self.problems())

    def test_signatures_are_required_only_when_asked_for(self):
        self.assertEqual([], self.problems(signed=False))
        self.assertIn(f"pcml-model: pcml-model-{VERSION}.jar is not signed (no pcml-model-{VERSION}.jar.asc)", self.problems(signed=True))
        for artifact in ARTIFACTS:
            for f in list(self.folder(artifact).iterdir()):
                if not f.name.startswith("maven-metadata"):
                    (self.folder(artifact) / (f.name + ".asc")).write_text("signature")
        self.assertEqual([], self.problems(signed=True))
        (self.folder("pcml-model") / f"pcml-model-{VERSION}-tests.jar.asc").write_text("signature")
        self.assertEqual([f"pcml-model: pcml-model-{VERSION}-tests.jar.asc signs a file that is not published"],
                         self.problems(signed=True))


if __name__ == "__main__":
    unittest.main()
