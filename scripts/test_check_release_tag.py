#!/usr/bin/env python3
"""Tests of check_release_tag.py, on a small repository made for each test and on this repository. Needs Python 3 and nothing
else; run with: python3 -m unittest discover -s scripts"""
import tempfile
import unittest
from pathlib import Path

import check_release_tag as checker

REPO = Path(__file__).resolve().parent.parent
POM = '<project xmlns="http://maven.apache.org/POM/4.0.0">{body}</project>'


def write(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(POM.format(body=body))


class ReleaseTagTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.make("0.1.0")

    def make(self, version, module_version=None, bom_version=None):
        write(self.repo / "pom.xml", f"<version>{version}</version><modules><module>pcml-a</module><module>pcml-bom</module></modules>")
        write(self.repo / "pcml-a/pom.xml", f"<parent><version>{module_version or version}</version></parent>")
        write(self.repo / "pcml-bom/pom.xml", f"<version>{bom_version or version}</version>")

    def test_a_tag_that_is_the_version_of_every_module_can_be_released(self):
        self.assertEqual([], checker.check("v0.1.0", self.repo))

    def test_a_snapshot_cannot_be_released_even_with_a_matching_tag(self):
        self.make("0.1.0-SNAPSHOT")
        self.assertEqual(["the project version 0.1.0-SNAPSHOT is a snapshot: set the release version before tagging"],
                         checker.check("v0.1.0-SNAPSHOT", self.repo))

    def test_a_tag_that_is_not_the_version_is_refused(self):
        self.assertEqual(["the tag v0.2.0 is not v0.1.0, the version in pom.xml"], checker.check("v0.2.0", self.repo))
        self.assertEqual(["the tag 0.1.0 is not v0.1.0, the version in pom.xml"], checker.check("0.1.0", self.repo))

    def test_a_module_with_another_version_is_refused(self):
        self.make("0.1.0", module_version="0.0.9")
        self.assertEqual(["pcml-a has the version 0.0.9, the project has 0.1.0"], checker.check("v0.1.0", self.repo))

    def test_the_bom_with_another_version_is_refused(self):
        self.make("0.1.0", bom_version="0.1.1")
        self.assertEqual(["pcml-bom has the version 0.1.1, the project has 0.1.0"], checker.check("v0.1.0", self.repo))

    def test_every_problem_is_reported_not_only_the_first(self):
        self.make("0.1.0-SNAPSHOT", module_version="0.0.9", bom_version="0.0.8")
        self.assertEqual(4, len(checker.check("v1", self.repo)))

    def test_a_root_pom_without_a_version_is_refused(self):
        write(self.repo / "pom.xml", "<modules/>")
        self.assertEqual(["the root pom.xml has no version"], checker.check("v0.1.0", self.repo))

    def test_the_command_line_exit_code_follows_the_problems(self):
        self.assertEqual(0, checker.main(["--repo", str(self.repo), "v0.1.0"]))
        self.assertEqual(1, checker.main(["--repo", str(self.repo), "v9"]))

    def test_every_module_of_this_repository_has_the_version_of_the_root_pom(self):
        version = checker._text(checker.ET.parse(REPO / "pom.xml").getroot(), "version")
        problems = checker.check("v" + version, REPO)
        self.assertEqual([], [p for p in problems if " has the version " in p])


if __name__ == "__main__":
    unittest.main()
