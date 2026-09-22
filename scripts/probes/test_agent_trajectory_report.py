import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("trajectory", Path(__file__).parents[1] / "agent-trajectory-report.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class TrajectoryTest(unittest.TestCase):
    def test_historical_same_path_cannot_prove_redundancy(self):
        data = {"modelRounds": [{"iteration": 1}, {"iteration": 2}], "toolCalls": [
            {"iteration": 1, "tool": "read_file", "path": "A"}, {"iteration": 2, "tool": "read_file", "path": "A"}]}
        self.assertEqual(["INSPECT", "INSPECT"], [r["activity"] for r in module.trajectory(data)["rounds"]])

    def test_native_state_marks_no_progress_and_keeps_failures(self):
        data = {"modelRounds": [{"iteration": 1}, {"iteration": 2}], "toolCalls": [
            {"iteration": 1, "tool": "task_evidence"}, {"iteration": 2, "tool": "run_validation", "error": True}],
            "executionTrajectory": [{"iteration": 1, "new_state_observed": False, "no_progress_rounds": 2}]}
        self.assertEqual(["REDUNDANT", "RECOVER"], [r["activity"] for r in module.trajectory(data)["rounds"]])

    def test_runtime_owned_control_uses_native_activity_without_handler_wrapper(self):
        data = {"modelRounds": [{"iteration": 1}], "toolCalls": [],
                "executionTrajectory": [{"iteration": 1, "round_activity": ["PLAN"],
                                          "new_state_observed": False, "no_progress_rounds": 1}]}
        self.assertEqual("PLAN", module.trajectory(data)["rounds"][0]["activity"])

    def test_no_edit_report_does_not_imply_a_first_mutation(self):
        data = {"model": "fake", "elapsedMs": 10, "modelRounds": [{"iteration": 1}], "toolCalls": [],
                "executionMetrics": {"firstMutationElapsedMs": -1}}
        self.assertIn("— / — (no edit observed)", module.report([("trial.json", data)]))

    def test_old_report_without_mutation_metadata_stays_unknown(self):
        data = {"model": "fake", "elapsedMs": 10, "modelRounds": [{"iteration": 1}],
                "toolCalls": [{"tool": "apply_edits"}]}
        self.assertIn("unknown / unknown", module.report([("old.json", data)]))

    def test_regex_mode_is_not_literal_confusion(self):
        data = {"toolCalls": [{"tool": "search_text", "containsPatternSyntax": True, "searchMode": "REGEX"}]}
        self.assertEqual(0, module.trajectory(data)["literalPatternQueries"])


if __name__ == "__main__":
    unittest.main()
