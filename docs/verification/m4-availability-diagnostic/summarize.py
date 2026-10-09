"""Score both frozen protocols, preserving invalid responses and failures as failures."""
import collections
import hashlib
import json
import pathlib
import statistics

root = pathlib.Path(__file__).resolve().parent
freeze = json.loads((root / 'freeze.json').read_text())
assert hashlib.sha256((root / 'frozen-fixtures.jsonl').read_bytes()).hexdigest() == freeze['fixture_manifest_sha256']
fixtures = {row['fixture']: row for row in map(json.loads, (root / 'frozen-fixtures.jsonl').read_text().splitlines())}
rows = list(map(json.loads, (root / 'raw.jsonl').read_text().splitlines()))
assert len(rows) == 160
counts = collections.Counter((row['fixture'], row['arm']) for row in rows)
assert len(counts) == 32 and all(count == 5 for count in counts.values())
for row in rows:
    fixture = fixtures[row['fixture']]
    assert row['expected_index'] == fixture['expected_index']
    assert row['expected_available'] == fixture['expected_available']
    for stage in row['stages']:
        expected_prompt = fixture['availability_prompt'] if stage['stage'] == 'availability' else fixture['baseline_prompt']
        assert stage['prompt'] == expected_prompt
    if row['available'] is False:
        assert len(row['stages']) == 1 and row['outcome'] == 'ABSTAINED'
    if row['available'] is True:
        assert len(row['stages']) == 2

def correct(row):
    return row['outcome'] in ('SELECTED', 'ABSTAINED') and row['selected_index'] == row['expected_index']

def stats(group):
    positives = [row for row in group if row['expected_available']]
    negatives = [row for row in group if not row['expected_available']]
    result = {
        'evaluations': len(group),
        'positive_cases': len(positives), 'negative_cases': len(negatives),
        'correct_decisions': sum(correct(row) for row in group),
        'correct_selections': sum(correct(row) for row in positives),
        'correct_abstentions': sum(correct(row) for row in negatives),
        'wrong_targets': sum(row['outcome'] == 'SELECTED' and not correct(row) for row in group),
        'incorrect_abstentions': sum(row['outcome'] == 'ABSTAINED' and not correct(row) for row in group),
        'invalid': sum(row['outcome'] == 'INVALID' for row in group),
        'runtime_failures': sum(row['outcome'] == 'FAILED' for row in group),
        'rule_correct': sum(row['rule_index'] == row['expected_index'] for row in group),
        'generation_calls': sum(len(row['stages']) for row in group),
        'generation_sum_ms_median': statistics.median(sum(stage['generation_ms'] for stage in row['stages']) for row in group),
        'load_and_decision_ms_median': statistics.median(row['load_and_decision_ms'] for row in group),
    }
    if group[0]['arm'] == 'availability_first':
        result['availability'] = {
            'correct': sum(row['available'] is not None and row['available'] == row['expected_available'] for row in group),
            'true_yes': sum(row['available'] is True for row in positives),
            'true_no': sum(row['available'] is False for row in negatives),
            'false_yes': sum(row['available'] is True for row in negatives),
            'false_no': sum(row['available'] is False for row in positives),
            'unparsed_or_failed': sum(row['available'] is None for row in group),
            'raw_counts': dict(collections.Counter(row['stages'][0].get('raw_response', '<failure>') if row['stages'] else '<initialization failure>' for row in group)),
        }
    return result

summary = {}
for arm in ('baseline', 'availability_first'):
    group = [row for row in rows if row['arm'] == arm]
    summary[arm] = {name: stats(group if name == 'all' else [row for row in group if row['group'] == name]) for name in ('all', 'original', 'new_variation')}
    summary[arm]['fixtures'] = {}
    for fixture in fixtures:
        selected = [row for row in group if row['fixture'] == fixture]
        summary[arm]['fixtures'][fixture] = {
            'correct': sum(correct(row) for row in selected),
            'outcomes': dict(collections.Counter(row['outcome'] for row in selected)),
            'availability_answers': [row['available'] for row in selected],
            'raw_stages': [[stage.get('raw_response') for stage in row['stages']] for row in selected],
        }
(root / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps({arm: {name: value for name, value in result.items() if name != 'fixtures'} for arm, result in summary.items()}, indent=2))
