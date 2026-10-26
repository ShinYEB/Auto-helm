import json
import tempfile
import unittest
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import incidents
import recovery


SEOUL = ZoneInfo("Asia/Seoul")
SESSIONS = [("KRX", "regular", "09:00:00", "15:30:00"),
            ("NXT", "main", "09:00:00", "15:20:00")]


class RecoveryTest(unittest.TestCase):
    def test_stale_heartbeat_requires_startup_grace(self):
        at = datetime(2026, 10, 27, 9, 0, tzinfo=SEOUL)
        health = {"pid": 101, "heartbeatAt": (at - timedelta(minutes=1)).isoformat()}
        self.assertEqual(recovery.heartbeat_issues("market", health, 101, at, at - timedelta(seconds=30)), [])
        self.assertEqual(recovery.heartbeat_issues("market", health, 101, at, at - timedelta(minutes=2))[0]["kind"],
                         "HEARTBEAT_STALE")

    def test_market_checks_session_and_subscription_without_restart_loop(self):
        at = datetime(2026, 10, 27, 9, 6, tzinfo=SEOUL)
        started = at - timedelta(minutes=6)
        health = {"pid": 101, "heartbeatAt": at.isoformat(),
                  "lastBarAt": at.isoformat(), "subscriptions": [
                      {"venue": "KRX", "subscriptionState": "SUBSCRIBED",
                       "lastReceivedAt": (at - timedelta(minutes=6)).isoformat()},
                      {"venue": "NXT", "subscriptionState": "SUBSCRIBED", "lastReceivedAt": None}]}
        problems = recovery.market_issues(health, 101, at, started, SESSIONS)
        self.assertTrue(any(p["kind"] == "TICK_STALE" and p["restart"] and p["detail"]["venue"] == "KRX"
                            for p in problems))
        self.assertFalse(any(p["detail"].get("venue") == "NXT" for p in problems))
        evening = at.replace(hour=20)
        self.assertEqual(recovery.market_issues({**health, "heartbeatAt": evening.isoformat()},
                                                101, evening, started, SESSIONS), [])

    def test_prediction_requires_new_bars_and_old_output(self):
        at = datetime(2026, 10, 27, 9, 10, tzinfo=SEOUL)
        health = {"firstBarAt": (at - timedelta(minutes=8)).isoformat(),
                  "lastBarAt": (at - timedelta(seconds=30)).isoformat()}
        stale = recovery.prediction_issues(health, at - timedelta(minutes=5), at, at - timedelta(minutes=10))
        self.assertEqual(stale[0]["kind"], "PREDICTION_STALE")
        self.assertTrue(stale[0]["restart"])
        self.assertEqual(recovery.prediction_issues(health, at - timedelta(minutes=1), at, at - timedelta(minutes=10)), [])

    def test_newly_added_symbol_gets_subscription_grace(self):
        at = datetime(2026, 10, 27, 11, 0, tzinfo=SEOUL)
        confirmed = at - timedelta(seconds=30)
        health = {"pid": 101, "heartbeatAt": at.isoformat(), "subscriptions": [
            {"venue": "KRX", "subscriptionState": "SUBSCRIBED", "lastReceivedAt": None,
             "updatedAt": confirmed.isoformat(), "subscriptionConfirmedAt": confirmed.isoformat()}]}
        self.assertEqual(recovery.market_issues(health, 101, at, at - timedelta(hours=3), SESSIONS), [])

    def test_incidents_are_append_only_and_restarts_survive_supervisor_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            at = datetime(2026, 10, 27, 9, 0, tzinfo=SEOUL)
            first = incidents.append_incident(directory, "market", "RESTART_REQUESTED", at=at,
                                              detail={"reason": "HEARTBEAT_STALE"})
            second = incidents.append_incident(directory, "market", "RESTART_REQUESTED",
                                               at=at + timedelta(minutes=1), detail={"reason": "TICK_STALE"})
            path = incidents.incident_path(directory, "2026-10-27")
            rows = [json.loads(line) for line in path.read_text().splitlines()]
            self.assertEqual([row["id"] for row in rows], [first, second])
            self.assertEqual(len(incidents.recent_restarts(directory, "market", at + timedelta(minutes=2))), 2)
            self.assertEqual(incidents.incident_counts(directory, "2026-10-27"),
                             {"market:RESTART_REQUESTED": 2})
            self.assertEqual(path.stat().st_mode & 0o777, 0o600)


if __name__ == "__main__":
    unittest.main()
