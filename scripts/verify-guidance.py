#!/usr/bin/env python3
"""Run real Settings guidance on a dedicated emulator, preserving provisioning and settings."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--mode", choices=("model", "rule"), default="model")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        parser.error("Use an empty output directory.")
    root = Path(__file__).resolve().parents[1]
    adb = ["adb", "-s", args.serial]

    def run(command):
        with (output / "commands.jsonl").open("a") as log:
            log.write(json.dumps(command) + "\n")
        result = subprocess.run(command, text=True, capture_output=True, timeout=600)
        result.check_returncode()
        return result.stdout.strip()

    def shell(*command):
        return run(adb + ["shell", shlex.join(command)])

    assert shell("getprop", "ro.kernel.qemu") == "1", "Use a dedicated emulator."
    settings = [("global", "airplane_mode_on"), ("global", "wifi_on"), ("global", "mobile_data"),
                ("secure", "enabled_accessibility_services"), ("secure", "accessibility_enabled")]
    original = {key: shell("settings", "get", namespace, key) for namespace, key in settings}
    assert all(original[key] in ("0", "1") for key in ("airplane_mode_on", "wifi_on", "mobile_data"))
    (output / "original-settings.json").write_text(json.dumps(original, indent=2) + "\n")
    model = "no_backup/models/gemma3-1b-it-int4.litertlm"
    backup = model + ".guidance-verification-backup"
    moved = False
    results = []
    metadata = {"base_commit": run(["git", "-C", str(root), "rev-parse", "HEAD"]),
                "serial": args.serial, "api": shell("getprop", "ro.build.version.sdk"), "mode": args.mode,
                "source_sha256": {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                                  for path in (root / "app/src").rglob("*.kt")},
                "apk_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in (
                    root / "app/build/outputs/apk/debug/app-debug.apk",
                    root / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")}}
    try:
        metadata["model_sha256"] = shell("run-as", "com.screenly.app", "sha256sum", model).split()[0]
        assert metadata["model_sha256"] == "1325ae366d31950f137c9c357b9fa89448b176d76998180c08ceaca78bba98be"
        (output / "environment.json").write_text(json.dumps(metadata, indent=2) + "\n")
        shell("settings", "delete", "secure", "enabled_accessibility_services")
        shell("settings", "put", "secure", "accessibility_enabled", "0")
        shell("am", "force-stop", "com.screenly.app")
        if args.mode == "rule":
            shell("run-as", "com.screenly.app", "test", "!", "-e", backup)
            shell("run-as", "com.screenly.app", "mv", model, backup)
            moved = True
        shell("cmd", "connectivity", "airplane-mode", "enable")
        shell("svc", "wifi", "disable")
        shell("svc", "data", "disable")
        for _ in range(30):
            if "Active default network: none" in shell("dumpsys", "connectivity"):
                break
            time.sleep(1)
        else:
            raise AssertionError("Emulator is not offline.")
        for workflow in ("font", "wifi", "dark"):
            shell("settings", "delete", "secure", "enabled_accessibility_services")
            shell("settings", "put", "secure", "accessibility_enabled", "0")
            shell("am", "force-stop", "com.screenly.app")
            started = int(shell("date", "+%s"))
            text = shell("am", "instrument", "-w", "-r", "-e", "class", "com.screenly.app.GuidanceUiTest",
                         "-e", "guidanceUi", "true", "-e", "workflow", workflow,
                         "-e", "highlightTimeoutMs", "90000", "-e", "requireRuleOnly",
                         "true" if args.mode == "rule" else "false",
                         "com.screenly.app.test/androidx.test.runner.AndroidJUnitRunner")
            (output / f"{workflow}.txt").write_text(text + "\n")
            diagnostics = shell("logcat", "-d", "-v", "epoch", "-s", "ScreenlyGuidance:D", "*:S")
            process_ids = set(re.findall(r"guidance_pid=(\d+)", text))
            lines = [line for line in diagnostics.splitlines()
                     if "decision session=" in line and float(line.split()[0]) >= started and line.split()[1] in process_ids]
            (output / f"{workflow}-decisions.txt").write_text("\n".join(lines) + "\n")
            steps = [line.split("guidance_ui=", 1)[1] for line in text.splitlines() if "guidance_ui=" in line]
            result = {"workflow": workflow, "passed": "OK (1 test)" in text, "steps": steps, "decisions": lines}
            results.append(result)
            (output / "summary.json").write_text(json.dumps(results, indent=2) + "\n")
            print(json.dumps(result), flush=True)
            assert result["passed"], f"{workflow} failed; inspect {output / (workflow + '.txt')}"
            if args.mode == "model":
                # Provisioned execution may still select RULE. Require parsed model work,
                # and keep actual accepted MODEL selections visible in the step records.
                assert any(f"outcome={outcome}" in line for line in lines
                           for outcome in ("SELECTED", "REJECTED_TARGET", "ABSTAINED")), "No real model decision recorded."
            assert "Active default network: none" in shell("dumpsys", "connectivity")
    finally:
        shell("settings", "delete", "secure", "enabled_accessibility_services")
        shell("settings", "put", "secure", "accessibility_enabled", "0")
        shell("am", "force-stop", "com.screenly.app")
        if moved:
            shell("run-as", "com.screenly.app", "mv", backup, model)
        shell("cmd", "connectivity", "airplane-mode", "enable" if original["airplane_mode_on"] == "1" else "disable")
        shell("svc", "wifi", "enable" if original["wifi_on"] == "1" else "disable")
        shell("svc", "data", "enable" if original["mobile_data"] == "1" else "disable")
        for namespace, key in settings[3:]:
            if original[key] == "null":
                shell("settings", "delete", namespace, key)
            else:
                shell("settings", "put", namespace, key, original[key])
        restored = {key: shell("settings", "get", namespace, key) for namespace, key in settings}
        (output / "restored-settings.json").write_text(json.dumps(restored, indent=2) + "\n")
        assert original == restored, "Settings restoration failed."


if __name__ == "__main__":
    main()
