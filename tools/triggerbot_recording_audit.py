#!/usr/bin/env python3
"""Read saved Grim debug reports without running a client, server, or detector.

Usage: python tools/triggerbot_recording_audit.py [report.txt ...] [--verify-known]
With no paths, inspect build/paste-*.txt. JSON is written only to stdout.
Labels for four supplied recordings are external user annotations, not predictions.
The available report format cannot replay per-tick TriggerBot opportunities.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import statistics
import sys


KNOWN_RECORDINGS = {
    "JXFWbk26nY": {
        "label": "user-labeled negative",
        "mode": None,
        "expected": (9410, 0, 224, 32, 0),
    },
    "opSlMcFDhj": {
        "label": "user-labeled negative",
        "mode": None,
        "expected": (11514, 0, 0, 0, 0),
    },
    "OmL9iV1QdE": {
        "label": "user-labeled TriggerBot enabled for all 60 seconds",
        "mode": "Smart",
        "expected": (1195, 1097, 47, 0, 0),
    },
    "aNWxnnt1uR": {
        "label": "user-labeled TriggerBot; activation duration unspecified",
        "mode": "unspecified",
        "expected": (3479, 2586, 138, 32, 2),
    },
}


def fields(line: str) -> dict[str, str]:
    return dict(re.findall(r"(?:^|[\s,])([\w:-]+)=([^\s,]+)", line))


def integer_fields(line: str) -> dict[str, int]:
    return {key: int(value.rstrip(".")) for key, value in fields(line).items()
            if re.fullmatch(r"-?\d+\.?", value)}


def first_line(lines: list[str], prefix: str) -> str:
    return next((line for line in lines if line.startswith(prefix)), "")


def counter_map(line: str) -> dict[str, int] | None:
    match = re.search(r"\{([^}]*)\}", line)
    return integer_fields(match[1]) if match else None


def section(lines: list[str], heading: str) -> list[str]:
    start = next((i for i, line in enumerate(lines) if line.startswith(heading)), None)
    if start is None:
        return []
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("--- ")), len(lines))
    return lines[start + 1:end]


def histogram(rows: list[dict[str, str]], key: str) -> dict[str, int]:
    return dict(sorted(Counter(row.get(key, "not-recorded") for row in rows).items()))


def interval_summary(times: list[int]) -> dict:
    deltas = [right - left for left, right in zip(times, times[1:])]
    nonnegative = [value for value in deltas if value >= 0]
    result = {"intervals": len(deltas), "out_of_order_intervals": sum(value < 0 for value in deltas)}
    if nonnegative:
        median = statistics.median(nonnegative)
        result.update(min_ms=min(nonnegative), median_ms=median, max_ms=max(nonnegative),
                      mad_ms=statistics.median(abs(value - median) for value in nonnegative))
    return result


def audit(path: Path) -> dict:
    raw = path.read_bytes()
    lines = raw.decode("utf-8-sig").splitlines()
    if not lines or lines[0] != "=== GRIM DEEP DEBUG REPORT ===":
        raise ValueError(f"{path}: not a Grim deep debug report")
    recording_id = path.stem.removeprefix("paste-")
    known = KNOWN_RECORDINGS.get(recording_id, {})
    observation = section(lines, "--- TRIGGERBOT OBSERVATION ---")
    if not observation:
        raise ValueError(f"{path}: TriggerBot observation section is missing")
    summary = integer_fields(first_line(observation, "ticks="))
    events = section(lines, "--- MOVEMENT EVENTS ")
    attacks = [fields(line) for line in events if re.match(r"\[\d\d:\d\d:\d\d\] ATTACK ", line)]
    times = [int(row["timeMs"]) for row in attacks if "timeMs" in row]
    snapshots = [fields(line) for line in observation if line.startswith("tick=")]
    snapshot_counts = integer_fields(first_line(observation, "Pre-attack state snapshots:"))
    episodes = [fields(line) for line in observation if line.startswith("episode ")]
    traces = section(lines, "--- SIMULATION / NOSLOW MOVEMENT TRACES ---")
    movement_rows = [line for line in traces if re.match(r"timeMs=\d+ checked=", line)]
    movement_times = [int(fields(line)["timeMs"]) for line in movement_rows]
    missing = []
    reported_attacks = summary.get("attacks")
    if reported_attacks is None:
        missing.append("Observer attack count is not recorded.")
    elif len(attacks) != reported_attacks:
        missing.append("Movement-event attack rows do not cover the observer attack count; bounded sections may have different coverage.")
    if snapshot_counts.get("retained", 0) > len(snapshots):
        missing.append("Only the final subset of retained pre-attack snapshots is printed.")
    if not snapshots:
        missing.append("No pre-attack state snapshots are available.")
    if summary.get("observed") == 0:
        missing.append("No descriptive ticks were observed under that build's exclusions.")
    if not attacks:
        missing.append("No attack event rows are available; this is not a combat false-positive trial.")
    missing.extend([
        "No chronological per-tick target geometry and readiness history, including opportunities without attacks.",
        "No complete packet-order and target/world reconstruction for all observer ticks.",
        "lastActualMovementY is last observed movement, not current client vertical velocity; jump input does not establish airborne state.",
        "Simulation traces are bounded windows selected by unrelated checks, not continuous TriggerBot input.",
    ])
    detector_line = first_line(observation, "Detector eligible ticks=")
    detector_match = re.search(r"eligible ticks=(\d+)", detector_line)
    result = {
        "file": str(path),
        "recording_id": recording_id,
        "sha256": hashlib.sha256(raw).hexdigest(),
        "external_label": known.get("label", "not supplied"),
        "external_mode": known.get("mode"),
        "session": first_line(lines, "Session: ").removeprefix("Session: "),
        "build": first_line(lines, "Grim: ").removeprefix("Grim: "),
        "client": first_line(lines, "Client: ").split(",", 1)[0].removeprefix("Client: "),
        "observer": summary,
        "observer_excluded_by_reason": counter_map(first_line(observation, "excludedTicksByReason=")),
        "geometry_counts": counter_map(first_line(observation, "Descriptive geometry samples by result=")),
        "geometry_unknown_reasons": counter_map(first_line(observation, "Geometry UNKNOWN reasons=")),
        "detector_eligible_ticks": int(detector_match[1]) if detector_match else None,
        "detector_excluded_by_first_reason": counter_map(detector_line),
        "detector_signals": integer_fields(first_line(observation, "Detector signals since join:")) or None,
        "attack_events": {
            "rows": len(attacks),
            "rows_with_time_ms": len(times),
            "arrival_intervals_descriptive_only": interval_summary(times),
            "cooldown_min_histogram": histogram(attacks, "cooldownMin"),
            "jump_input_histogram": histogram(attacks, "jump"),
            "sprinting_histogram": histogram(attacks, "sprinting"),
        },
        "pre_attack_snapshots": {
            "retained": snapshot_counts.get("retained"),
            "shown_reported": snapshot_counts.get("shown"),
            "rows": len(snapshots),
            "on_ground_histogram": histogram(snapshots, "onGround"),
            "cooldown_min_histogram": histogram(snapshots, "cooldownMin"),
            "sprint_stop_age_ticks_histogram": histogram(snapshots, "sprintStopAgeTicks"),
            "attack_gap_ticks_histogram": histogram(snapshots, "attackGapTicks"),
        },
        "printed_episodes": {"rows": len(episodes), "outcomes": histogram(episodes, "outcome")},
        "simulation_trace_coverage": {
            "windows": sum(bool(re.fullmatch(r"Trace \d+:", line)) for line in traces),
            "rows": len(movement_rows),
            "unique_timestamps": len(set(movement_times)),
        },
        "detector_replay": {"supported": False, "missing_or_limited": missing},
    }
    return result


def verify_known(result: dict) -> None:
    known = KNOWN_RECORDINGS.get(result["recording_id"])
    if known is None:
        raise ValueError(f"No expected recording counts for {result['recording_id']}")
    counts = tuple(result["observer"].get(key) for key in ("ticks", "observed", "attacks")) + (
        result["pre_attack_snapshots"]["rows"], result["printed_episodes"]["rows"],
    )
    if counts != known["expected"]:
        raise ValueError(f"{result['recording_id']}: expected {known['expected']}, found {counts}")
    shown = result["pre_attack_snapshots"]["shown_reported"]
    if shown != result["pre_attack_snapshots"]["rows"]:
        raise ValueError(f"{result['recording_id']}: printed pre-attack count mismatch")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path)
    parser.add_argument("--verify-known", action="store_true", help="check the supplied reports' coverage counts; not a detector test")
    args = parser.parse_args()
    paths = args.paths or sorted((Path(__file__).resolve().parents[1] / "build").glob("paste-*.txt"))
    if not paths:
        parser.error("No reports supplied or found in build/paste-*.txt")
    try:
        results = [audit(path) for path in paths]
        if args.verify_known:
            for result in results:
                verify_known(result)
    except (OSError, UnicodeError, ValueError) as error:
        print(error, file=sys.stderr)
        return 1
    print(json.dumps({
        "purpose": "Read-only recording coverage audit, not detector replay or a cheating classifier.",
        "interpretation": "Labels were supplied externally. Cadence and sprint resets occur in manual play. These reports cannot establish recall or false-positive rates.",
        "known_coverage_counts_verified": args.verify_known,
        "recordings": results,
    }, indent=2, ensure_ascii=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
