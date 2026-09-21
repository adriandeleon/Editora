import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("report", Path(__file__).parents[1] / "agent-evaluation-report.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class EvaluationReportTest(unittest.TestCase):
    def test_failures_and_incomplete_trials_survive_aggregation(self):
        with tempfile.TemporaryDirectory() as scratch:
            files = []
            for i, (state, success, elapsed) in enumerate([
                ("COMPLETED", "PASS", 1000), ("NEEDS_INPUT", "FAIL", 3000), ("FAILED", "FAIL", None)
            ]):
                row = dict(kind="AUTONOMOUS_CODING_EVALUATION", provider="fake", model="model",
                           scenario=f"case-{i}", completionState=state, taskSuccess=success,
                           oraclePassed=success == "PASS", profileFingerprint="test-profile",
                           failureCategories=[] if success == "PASS" else ["OUTPUT_LIMIT"])
                if elapsed is not None:
                    row.update(agentElapsedMs=elapsed, iterations=2, calls=3)
                p = Path(scratch) / f"trial-{i}.json"
                p.write_text(json.dumps(row))
                files.append(p)
            text = report.summarize(files)
            self.assertIn("| 3 | 1 | 1 | 1 | 1 | 2 | 2.0 / 3.0 / 2.0 |", text)
            for p in files:
                self.assertIn(p.name, text)
            self.assertIn("NEEDS_INPUT", text)
            self.assertIn("FAILED", text)

    def test_acceptance_failure_is_not_hidden_by_green_oracle(self):
        with tempfile.TemporaryDirectory() as scratch:
            p=Path(scratch)/"missing-test.json"
            p.write_text(json.dumps(dict(kind="AUTONOMOUS_CODING_EVALUATION",provider="fake",model="m",scenario="missing-test",completionState="NEEDS_INPUT",taskSuccess="FAIL",oraclePassed=True,acceptance=dict(requirements=[dict(state="SATISFIED"),dict(state="EVIDENCE_PENDING")],unsupportedStructuredClaims=2,unstructuredCandidatesHandled=3,checks=3,reconciliations=4,verificationNanos=1500000,javaDeclarationNanos=0),regressionQuality=dict(state="UNVERIFIED"))))
            text=report.summarize([p])
            self.assertIn("| 1 / 2 | 2 | 3 | 3 / 4 | 1.500 / N/A | 0.000 | UNVERIFIED |",text)
            self.assertIn("FAIL / NEEDS_INPUT",text)

    def test_interrupted_attempt_has_no_invented_final_outcome(self):
        with tempfile.TemporaryDirectory() as scratch:
            p=Path(scratch)/"interrupted.json"
            p.write_text(json.dumps(dict(kind="INTERRUPTED_EVALUATION_BATCH",label="old-build",interruptedScenario="diff",interruptedTrial=2,reason="Stopped after confirmed runtime defect.")))
            text=report.summarize([p])
            self.assertIn("diff, trial 2",text)
            self.assertIn("no final oracle/task-success measurement",text)
            self.assertNotIn("PASS / COMPLETED",text)


if __name__ == "__main__":
    unittest.main()
