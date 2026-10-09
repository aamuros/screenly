"""Summarize recorded benchmark JSONL without repairing or substituting model outputs."""
import collections
import json
import pathlib
import statistics

root = pathlib.Path(__file__).resolve().parent
models = ("gemma", "qwen3-nothink-int4", "qwen25-instruct-int8")
summary = {}
prompts = {}
for model in models:
    rows = [json.loads(line) for line in (root / f"{model}-benchmark.jsonl").read_text().splitlines()]
    counts = collections.Counter(row["fixture"] for row in rows)
    assert len(rows) == 25 and all(count == 5 for count in counts.values())
    called = [row for row in rows if row["model_called"]]
    assert len(called) == 20
    for row in called:
        fixture = row["fixture"]
        if fixture in prompts:
            assert prompts[fixture] == row["prompt"], "Prompt protocol differs between calls/models"
        prompts[fixture] = row["prompt"]
    def distribution(values):
        return {"median": statistics.median(values), "min": min(values), "max": max(values)}
    def correct(row):
        return row["model_outcome"] in ("SELECTED", "ABSTAINED") and row["accepted_index"] == row["expected_index"]
    result = {
        "generations": len(called),
        "correct_decisions": sum(correct(row) for row in called),
        "correct_selections": sum(correct(row) and row["expected_index"] is not None for row in called),
        "correct_abstentions": sum(correct(row) and row["expected_index"] is None for row in called),
        "wrong_target_selections": sum(row["model_outcome"] == "SELECTED" and not correct(row) for row in called),
        "incorrect_abstentions": sum(row["model_outcome"] == "ABSTAINED" and not correct(row) for row in called),
        "invalid_responses": sum(row["model_outcome"] == "INVALID" for row in called),
        "runtime_failures": sum(row["model_outcome"] == "FAILED" for row in called),
        "input_rejections": sum(row["model_outcome"] == "INPUT_REJECTED" for row in rows),
        "generation_ms": distribution([row["generation_ms"] for row in called if "generation_ms" in row]),
        "initialize_ms": distribution([row["initialize_ms"] for row in called if "initialize_ms" in row]),
        "load_and_generation_ms": distribution([row["load_and_generation_ms"] for row in called]),
        "post_generation_pss_kib": distribution([row["memory_after_generation"]["pss_kib"] for row in called if "memory_after_generation" in row]),
        "post_generation_rss_kib": distribution([row["memory_after_generation"]["rss_kib"] for row in called if "memory_after_generation" in row]),
        "process_lifetime_rss_high_water_kib": max(row["memory_after_generation"]["process_rss_hwm_kib"] for row in called if "memory_after_generation" in row),
        "rule_correct_decisions": sum(row["rule_index"] == row["expected_index"] for row in called),
        "fixtures": {},
    }
    for fixture in counts:
        selected = [row for row in rows if row["fixture"] == fixture]
        result["fixtures"][fixture] = {
            "correct": sum(correct(row) for row in selected if row["model_called"]),
            "outcomes": dict(collections.Counter(row["model_outcome"] for row in selected)),
            "raw_responses": [row["raw_response"] for row in selected],
        }
    summary[model] = result
(root / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
print(json.dumps(summary, indent=2))
