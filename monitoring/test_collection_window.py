import json
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
from unittest.mock import Mock
from zoneinfo import ZoneInfo

import collection_window as window


class CollectionWindowTest(unittest.TestCase):
    def test_boundaries_and_trial_dates(self):
        seoul = ZoneInfo("Asia/Seoul")
        self.assertFalse(window.active(datetime(2026, 10, 27, 7, 29, 59, tzinfo=seoul)))
        self.assertTrue(window.active(datetime(2026, 10, 27, 7, 30, tzinfo=seoul)))
        self.assertTrue(window.active(datetime(2026, 10, 30, 20, 29, 59, tzinfo=seoul)))
        self.assertFalse(window.active(datetime(2026, 10, 30, 20, 30, tzinfo=seoul)))
        self.assertFalse(window.active(datetime(2026, 10, 31, 10, tzinfo=seoul)))

    def test_report_counts_sources_and_parse_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            data = Path(directory)
            for kind in ("raw", "bars", "gaps"):
                (data / "market" / kind).mkdir(parents=True)
            date = "2026-10-27"
            (data / "market/raw" / f"{date}.jsonl").write_text(
                json.dumps({"text": "0|H0STCNT0|001|x"}) + "\n" +
                json.dumps({"text": "0|H0NXCNT0|001|x"}) + "\n")
            (data / "market/bars" / f"{date}.jsonl").write_text(
                json.dumps({"venue": "KRX", "quality": "READY"}) + "\n" + "bad json\n")
            (data / "market/gaps" / f"{date}.jsonl").write_text(
                json.dumps({"reason": "DISCONNECTED"}) + "\n")
            (data / f"market-day-{date}.log").write_text("WebSocket connection failed\n")
            with patch.object(window, "DATA", data):
                window.daily_report(date, {"market": 1}, datetime.now(window.SEOUL))
            report = json.loads((data / f"market-day-{date}.report.json").read_text())
            self.assertEqual(report["frames"], {"KRX": 1, "NXT": 1})
            self.assertEqual(report["bars"], {"KRX:READY": 1})
            self.assertEqual(report["gaps"], {"DISCONNECTED": 1})
            self.assertEqual(report["jsonParseErrors"], 1)
            self.assertTrue(report["automaticStop"])

    def test_orphan_cleanup_ignores_pid_without_expected_jar(self):
        with tempfile.TemporaryDirectory() as directory:
            runtime = Path(directory)
            (runtime / "status.json").write_text(json.dumps({"processes": {"market": 123}}))
            with patch.object(window, "RUNTIME", runtime), \
                 patch.object(window.subprocess, "run", return_value=Mock(returncode=0, stdout="unrelated process")), \
                 patch.object(window.os, "killpg") as kill:
                window.cleanup_orphans()
            kill.assert_not_called()

    def test_daily_report_cannot_overwrite_existing_report(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            window.write_once_json(path, {"error": "first"})
            with self.assertRaises(FileExistsError):
                window.write_once_json(path, {"error": "second"})
            self.assertEqual(json.loads(path.read_text()), {"error": "first"})


if __name__ == "__main__":
    unittest.main()
