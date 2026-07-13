import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


class TerminologyUiTest(unittest.TestCase):
    def test_workspace_has_complete_business_controls(self) -> None:
        html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")

        for element_id in (
            "metadataStructureTab",
            "terminologyTab",
            "metadataStructurePanel",
            "terminologyWorkspace",
            "terminologyConceptList",
            "terminologyAliasList",
            "terminologyHospitalMappings",
            "terminologyReviewQueue",
            "terminologyReleaseList",
            "terminologyTestInput",
        ):
            self.assertIn(f'id="{element_id}"', html)
        self.assertIn('/static/terminology.css', html)
        self.assertIn('/static/terminology.js', html)

    def test_workspace_script_supports_query_review_mapping_and_release(self) -> None:
        js = (ROOT / "web" / "terminology.js").read_text(encoding="utf-8")

        for marker in (
            "window.activateTerminologyWorkspace",
            "loadTerminologyConcepts",
            "runTerminologyTest",
            "createTerminologyAlias",
            "approveTerminologyAlias",
            "createTerminologyMapping",
            "approveTerminologyMapping",
            "publishTerminologyRelease",
            "restoreTerminologyRelease",
            'headers: {"Authorization": "Bearer " + adminToken',
        ):
            self.assertIn(marker, js)

    def test_workspace_has_desktop_mobile_and_keyboard_styles(self) -> None:
        css = (ROOT / "web" / "terminology.css").read_text(encoding="utf-8")

        self.assertIn("grid-template-columns: minmax(260px, 340px) minmax(0, 1fr)", css)
        self.assertIn(".term-safety", css)
        self.assertIn(":focus-visible", css)
        self.assertIn("@media (max-width: 760px)", css)
        self.assertIn("overflow-wrap: anywhere", css)


if __name__ == "__main__":
    unittest.main()
