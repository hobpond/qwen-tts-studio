import tempfile
import unittest
from datetime import date
from pathlib import Path

from okf_bundle import OKFBundle, OKFDocument, OKFDocumentError, is_stale, normalize_verified, trust_tier


class DocumentTests(unittest.TestCase):
    def test_round_trip_preserves_metadata_and_body(self):
        source = "---\ntype: concept\ntags: [a, b]\n---\n\n# Body\n"
        document = OKFDocument.parse(source)
        self.assertEqual(document.frontmatter["tags"], ["a", "b"])
        reparsed = OKFDocument.parse(document.serialize())
        self.assertEqual(reparsed.frontmatter, document.frontmatter)
        self.assertEqual(reparsed.body.strip(), document.body.strip())

    def test_type_is_the_only_required_field(self):
        OKFDocument({"type": "Reference"}).validate()

    def test_missing_type_is_rejected(self):
        with self.assertRaises(OKFDocumentError):
            OKFDocument({"title": "Missing type"}).validate()

    def test_trust_and_staleness_helpers_match_spec(self):
        self.assertEqual(trust_tier({}), "unverified")
        self.assertEqual(trust_tier({"verified": {"by": "process:ci", "at": "2026-01-01T00:00:00Z"}}), "machine-confirmed")
        self.assertEqual(trust_tier({"verified": [{"by": "human:maintainer", "at": "2026-01-01T00:00:00Z"}]}), "human-reviewed")
        self.assertEqual(len(normalize_verified({"verified": {"by": "human:x"}})), 1)
        self.assertTrue(is_stale({"stale_after": "2026-09-23"}, date(2026, 9, 23)))
        self.assertFalse(is_stale({"stale_after": "2026-09-24"}, date(2026, 9, 23)))


class BundleTests(unittest.TestCase):
    def test_project_bundle_validates(self):
        root = Path(__file__).resolve().parents[3] / "docs" / "okf"
        self.assertEqual(OKFBundle(root).validate(), [])

    def test_unresolved_concept_link_is_reported(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "index.md").write_text("# Index\n", encoding="utf-8")
            (root / "concept.md").write_text("---\ntype: concept\n---\n[missing](missing.md)\n", encoding="utf-8")
            errors = OKFBundle(root).validate()
            self.assertTrue(any("unresolved link" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
