#!/usr/bin/env python3
"""Run the opt-in API 30 evaluator, preserving evidence and restoring emulator settings."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics
import subprocess
import time


def summarize(records):
    fixtures = {r["id"]: r for r in records if r["kind"] == "fixture"}
    rows = [r for r in records if r["kind"] == "evaluation"]
    calls = [r for r in rows if r["generation_ms"] is not None]
    durations = [r["generation_ms"] for r in calls]
    return {
        "fixtures": len(fixtures), "decisions": len(rows), "model_decisions": len(calls),
        "native_calls": sum(sum(candidate["raw"] is not None for candidate in row["candidate_evaluations"])
                            if "candidate_evaluations" in row else 1 for row in calls),
        "rules_correct": sum(r["rule_correct"] for r in rows),
        "model_correct": sum(r["model_correct"] for r in calls),
        "raw_model_correct": sum(r["raw_model_correct"] for r in calls) if all("raw_model_correct" in r for r in calls) else None,
        "model_selected_correct": sum(r["model_outcome"] == "SELECTED" and r["model_correct"] for r in calls),
        "model_canonical_abstentions_correct": sum(r["model_outcome"] == "ABSTAINED" and r["model_correct"] for r in calls),
        "model_wrong_selected": sum(r["model_outcome"] == "SELECTED" and not r["model_correct"] for r in calls),
        "malformed_responses": sum(r["model_outcome"] == "INVALID_RESPONSE" for r in calls),
        "rejected_targets": sum(r["model_outcome"] == "REJECTED_TARGET" for r in calls),
        "runtime_failures": sum(r["model_outcome"] == "FAILED" for r in calls),
        "fallback_attempts": sum(r["fallback_attempted"] for r in rows),
        "fallback_selections": sum(r["source"] == "RULE" for r in rows),
        "combined_correct": sum(r["combined_correct"] for r in rows),
        "combined_wrong_selected": sum(r["combined_index"] is not None and not r["combined_correct"] for r in rows),
        "generation_ms": {"median": statistics.median(durations), "min": min(durations), "max": max(durations)} if durations else None,
        "sampled_pss_kib_max": max((r["pss_kib"] for r in rows), default=None),
        "per_fixture": [{"id": name, "expected_index": fixture["expected_index"],
                         "runs": [r for r in rows if r["fixture"] == name]} for name, fixture in fixtures.items()],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--repetitions", type=int, choices=range(1, 11), default=3)
    parser.add_argument("--protocol", choices=("legacy", "candidate"), default="legacy")
    parser.add_argument("--fixture-set", choices=("navigation", "availability"), default="navigation")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("Use an empty output directory so previous evidence is preserved.")
    adb = ["adb", "-s", args.serial]

    def run(command, filename=None, check=True):
        with (output / "commands.jsonl").open("a") as log:
            log.write(json.dumps(command) + "\n")
        if filename:
            with (output / filename).open("w") as log:
                result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=1800)
            text = (output / filename).read_text()
        else:
            result = subprocess.run(command, cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
            text = result.stdout
        if check and result.returncode:
            raise RuntimeError(f"Command failed ({result.returncode}): {command}\n{text[-2000:]}")
        return text.strip()

    def shell(*command, **kwargs):
        return run(adb + ["shell", *command], **kwargs)

    assert shell("getprop", "ro.build.version.sdk") == "30", "Use the working API 30 emulator"
    assert shell("getprop", "ro.kernel.qemu") == "1", "This runner changes emulator connectivity settings"
    avd = run(adb + ["emu", "avd", "name"])
    assert avd.splitlines()[0] == "Screenly_M3_API30", "Use Screenly_M3_API30"
    settings = [("global", "airplane_mode_on"), ("global", "wifi_on"), ("global", "mobile_data"),
                ("secure", "enabled_accessibility_services"), ("secure", "accessibility_enabled")]
    original = {key: shell("settings", "get", namespace, key) for namespace, key in settings}
    assert all(original[k] in ("0", "1") for k in ("airplane_mode_on", "wifi_on", "mobile_data"))
    (output / "original-settings.json").write_text(json.dumps(original, indent=2) + "\n")
    apks = [root / "app/build/outputs/apk/debug/app-debug.apk",
            root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"]
    metadata = {"base_commit": run(["git", "rev-parse", "HEAD"]), "avd": avd, "serial": args.serial,
                "source_sha256": {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
                                  for folder in ("app/src/main/java/com/screenly/app/ai", "app/src/sharedTest", "app/src/androidTest/java/com/screenly/app/ai")
                                  for p in (root / folder).rglob("*.kt")},
                "apks": {p.name: {"bytes": p.stat().st_size, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in apks}}
    (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
    try:
        for apk in apks:
            run(adb + ["install", "-r", str(apk)])
        # Isolate native evaluation and avoid service restart side effects during instrumentation.
        shell("settings", "put", "secure", "enabled_accessibility_services", "''")
        shell("settings", "put", "secure", "accessibility_enabled", "0")
        shell("cmd", "connectivity", "airplane-mode", "enable")
        shell("svc", "wifi", "disable")
        shell("svc", "data", "disable")
        for _ in range(30):
            if "Active default network: none" in shell("dumpsys", "connectivity"):
                break
            time.sleep(1)
        else:
            raise RuntimeError("Emulator did not become offline")
        shell("dumpsys", "connectivity", filename="offline-before.txt")
        shell("am", "force-stop", "com.screenly.app")
        print("Running real offline model evaluation...", flush=True)
        instrumentation = shell("am", "instrument", "-w", "-r", "-e", "class",
                                "com.screenly.app.ai.navigation.NavigationEvaluationTest",
                                "-e", "navigationEvaluation", "true", "-e", "repetitions", str(args.repetitions),
                                "-e", "protocol", args.protocol, "-e", "fixtureSet", args.fixture_set,
                                "com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner", filename="instrumentation.txt")
        raw = shell("run-as", "com.screenly.app", "cat", "cache/navigation-evaluation.jsonl")
        (output / "raw.jsonl").write_text(raw + "\n")
        records = [json.loads(line) for line in raw.splitlines()]
        summary = summarize(records)
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        shell("dumpsys", "connectivity", filename="offline-after.txt")
        assert "OK (1 test)" in instrumentation, "Instrumentation failed; inspect retained evidence"
        assert summary["decisions"] == summary["fixtures"] * args.repetitions
        print(json.dumps({k: v for k, v in summary.items() if k != "per_fixture"}, indent=2))
    finally:
        shell("cmd", "connectivity", "airplane-mode", "enable" if original["airplane_mode_on"] == "1" else "disable")
        shell("svc", "wifi", "enable" if original["wifi_on"] == "1" else "disable")
        shell("svc", "data", "enable" if original["mobile_data"] == "1" else "disable")
        for namespace, key in settings[3:]:
            value = original[key]
            if value == "null":
                shell("settings", "delete", namespace, key)
            else:
                # adb shell rejoins arguments; quote remote values explicitly.
                import shlex
                shell("settings", "put", namespace, key, shlex.quote(value))
        restored = {key: shell("settings", "get", namespace, key) for namespace, key in settings}
        (output / "restored-settings.json").write_text(json.dumps(restored, indent=2) + "\n")
        shell("dumpsys", "accessibility", filename="restored-accessibility.txt")
        assert restored == original, "Original emulator settings were not restored"


if __name__ == "__main__":
    main()
