#!/usr/bin/env python3
"""Closed Contracts + rooted companion distribution layout and package checks.

Package/model/checker validation is independent of actual runtime acceptance.
"""
from pathlib import Path
import hashlib
import json
import re
import subprocess
import sys
import tempfile

from jcs import dumps as jcs_dumps
from package_hygiene import copy_regular_tree, release_inventory_files

COMPANION = Path('companions/rooted-checkpoint')
COMPANION_MANIFEST = Path('companions/rooted-checkpoint-manifest.json')
SUITE = Path('conformance/rooted-processing')
ROOTED_SPEC = 'rooted-checkpoint-processing-1.0-draft.md'
CONTRACTS_SPEC = 'blue-contracts-and-processor-specification-1.0.md'
LANGUAGE_SPEC = 'blue-language-specification-1.0.md'
SPECIFICATIONS = {
    'blue-bex-specification-2.0.md', CONTRACTS_SPEC,
    'blue-coordination-specification-1.0-candidate.md', LANGUAGE_SPEC, ROOTED_SPEC,
}
PROFILE = 'blue-rooted-checkpoint/1.0-draft.2'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def files(root):
    root = root.absolute()
    return {p.relative_to(root).as_posix(): sha(p) for p in release_inventory_files(root)}


def json_file(path):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('Duplicate JSON field: ' + key)
            result[key] = value
        return result
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=object_pairs)


def manifest_value(companion):
    """Bind actual regular source files, never a count-based allowance."""
    return {
        'schema': 'blue-contracts-rooted-companion/1',
        'root': COMPANION.as_posix(),
        'profile': PROFILE,
        'specificationSetSha256': sha(companion / 'manifests/specification-set.json'),
        'fixtureIndexSha256': sha(companion / SUITE / 'fixture-index.json'),
        'tariffTableSha256': sha(companion / SUITE / 'tariff-weights.json'),
        'files': files(companion),
    }


def write_companion_manifest(release_root):
    value = manifest_value(release_root / COMPANION)
    value['packageIdentity'] = 'sha256:' + hashlib.sha256(jcs_dumps(value)).hexdigest()
    target = release_root / COMPANION_MANIFEST
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def stage_rooted_companion(source_root, release_root):
    """Stage the complete maintained package, retaining its relative tool paths."""
    target = release_root / COMPANION
    copy_regular_tree(source_root, target)
    write_companion_manifest(release_root)


def validate_release_layout(root, specification, require):
    """Preserve standalone rules; validate an explicit complete combined profile."""
    if ROOTED_SPEC not in specification.read_text(encoding='utf-8'):
        require(not any('bex' in p.name.lower() or 'blue-bex' in p.as_posix().lower()
                        for p in root.rglob('*') if p.is_file()),
                'BEX file included in Contracts package')
        names = sorted(p.name for p in (root / 'specifications').glob('*.md'))
        require(names == [CONTRACTS_SPEC], f'unexpected specification documents: {names}')
        require(not (root / COMPANION_MANIFEST).exists() and not (root / COMPANION).exists(),
                'Unreferenced rooted companion in standalone package')
        return None

    companion = root / COMPANION
    require(companion.is_dir() and (root / COMPANION_MANIFEST).is_file(),
            'Normative rooted companion requires its complete separately indexed package')
    declared = json_file(root / COMPANION_MANIFEST)
    actual = manifest_value(companion)
    identity = 'sha256:' + hashlib.sha256(jcs_dumps(actual)).hexdigest()
    require(declared == dict(actual, packageIdentity=identity),
            'Rooted companion complete path/content inventory or identity mismatch')
    require(files(root / SUITE) == files(companion / SUITE),
            'Appendix E rooted suite mirror differs from complete companion')
    for outer, inner in [
        (specification, companion / 'specifications' / CONTRACTS_SPEC),
        (root / 'reference' / LANGUAGE_SPEC, companion / 'specifications' / LANGUAGE_SPEC),
        (root / 'specifications' / ROOTED_SPEC, companion / 'specifications' / ROOTED_SPEC),
    ]:
        require(outer.read_bytes() == inner.read_bytes(), 'Canonical companion specification mirror mismatch: ' + outer.name)
    bound = re.findall(r'exact companion with SHA-256 `([0-9a-f]{64})`', specification.read_text())
    require(bound == [sha(companion / 'specifications' / ROOTED_SPEC)],
            'Contracts Appendix E does not authenticate the exact rooted companion')
    names = sorted(p.name for p in (root / 'specifications').glob('*.md'))
    require(names == sorted([CONTRACTS_SPEC, ROOTED_SPEC]), f'unexpected combined specification documents: {names}')
    require({p.name for p in (root / 'conformance').iterdir()} == {'contracts', 'rooted-processing'},
            'Unexpected conformance package root')
    require({p.name for p in (root / 'companions').iterdir()} == {'rooted-checkpoint', COMPANION_MANIFEST.name},
            'Unexpected companion package root')
    actual_bex = {p.relative_to(root).as_posix() for p in release_inventory_files(root)
                  if 'bex' in p.name.lower() or 'blue-bex' in p.relative_to(root).as_posix().lower()}
    expected_bex = {
        (COMPANION / 'specifications/blue-bex-specification-2.0.md').as_posix(),
        (COMPANION / SUITE / 'tariffs/bex-gas-1.0.yaml').as_posix(),
        (SUITE / 'tariffs/bex-gas-1.0.yaml').as_posix(),
    }
    require(actual_bex == expected_bex, 'BEX files escape the exact rooted profile bindings')
    spec_set = json_file(companion / 'manifests/specification-set.json')
    require(spec_set['schema'] == 'blue-rooted-specification-set/1.0-draft.2'
            and spec_set['profile'] == PROFILE and spec_set['releaseReadinessClaimed'] is False,
            'Rooted specification-set profile/readiness mismatch')
    rows = spec_set['files']
    require(len(rows) == len(SPECIFICATIONS)
            and {row['path'] for row in rows} == {'specifications/' + name for name in SPECIFICATIONS},
            'Rooted specification-set inventory mismatch')
    require({p.name for p in (companion / 'specifications').iterdir()} == SPECIFICATIONS,
            'Rooted specification directory differs from its exact specification set')
    for row in rows:
        path = companion / row['path']
        require(sha(path) == row['sha256'] and len(path.read_bytes()) == row['bytes'],
                'Rooted specification-set content mismatch: ' + row['path'])
    return validate_rooted_package(companion, require)


def validate_rooted_package(companion, require):
    import jsonschema
    suite = companion / SUITE
    index = json_file(suite / 'fixture-index.json')
    require(index['schema'] == 'blue-rooted-processing-index/1.0-draft.2', 'Rooted fixture-index version mismatch')
    rows = index['fixtures']
    paths = [row['path'] for row in rows]
    require(len(paths) == len(set(paths)) and len({row['id'] for row in rows}) == len(rows),
            'Duplicate rooted fixture path or identity')
    actual = {p.relative_to(suite).as_posix() for p in (suite / 'fixtures').rglob('*.json')}
    require(set(paths) == actual, 'Rooted fixture inventory differs from exact index')
    schema = json_file(suite / 'schemas/fixture.schema.json')
    jsonschema.Draft202012Validator.check_schema(schema)
    validator = jsonschema.Draft202012Validator(schema)
    runtime = 0
    for row in rows:
        relative = Path(row['path'])
        require(not relative.is_absolute() and '..' not in relative.parts, 'Unsafe rooted fixture path')
        fixture = json_file(suite / relative)
        validator.validate(fixture)
        require(fixture['id'] == row['id'] and fixture['kind'] == row['kind']
                and fixture['requirements'] == row['requirements']
                and fixture['verification']['scope'] == row['verificationScope'],
                'Rooted fixture/index semantic binding mismatch: ' + row['id'])
        if 'qualification' in row:
            require(row['qualification'] == fixture['input'].get('qualification'),
                    'Rooted fixture/index qualification mismatch: ' + row['id'])
        provenance = fixture.get('recipeProvenance')
        if provenance is not None:
            expected_path = 'provenance/recipe-inputs/' + row['id'].lower() + '.json'
            require(provenance['path'] == expected_path
                    and sha(suite / expected_path) == provenance['sha256'],
                    'Rooted literal recipe provenance does not authenticate its original fixture')
        if fixture['kind'] == 'runtime' and fixture['input'].get('qualification') == 'LITERAL_CRITICAL':
            expected_plan = 'plans/' + row['id'].lower() + '.json'
            require(fixture['input'].get('literalPlan') == expected_plan, 'Literal fixture does not name its exact plan')
            plan = json_file(suite / expected_plan)
            require(plan['schema'] == 'blue-rooted-literal-plan/1.0-draft.2' and plan['fixtureId'] == row['id']
                    and set(plan['variants']) == set(fixture['input']['variants']) and plan['variants'],
                    'Literal plan identity or complete variant inventory mismatch')
            host_matrix = fixture['expected'].get('contract', {}).get('scope') == 'PACKAGED_PROCESS_FAULT_MATRIX'
            if host_matrix:
                cuts = fixture['expected']['contract']['faultCuts']
                require(row['id'] == 'RCP-RUN-028' and plan['setup'] == []
                        and plan['outputContract'] == fixture['expected']['contract']
                        and plan['variants'] == {'baseline': [{'stepId': 'baseline-0001',
                            'op': 'actualPackagedJvmFaultMatrix', 'lanes': cuts['lanes'], 'cuts': cuts['cuts']}]},
                        'Packaged fault matrix differs from its closed fixture-owned plan')
            for variant_steps in plan['variants'].values():
                steps = plan['setup'] + variant_steps
                require(steps and variant_steps and len({s['stepId'] for s in steps}) == len(steps),
                        'Empty or duplicate literal plan steps')
                for step in steps:
                    require(isinstance(step['stepId'], str) and step['stepId'] and (host_matrix or step['op'] in plan['allowedPrimitives'])
                            and type(step.get('repeat', 1)) is int and step.get('repeat', 1) > 0,
                            'Invalid literal plan primitive or repeat')
        runtime += fixture['kind'] == 'runtime'
    # The maintained adapter derives every allowed counter from its three pinned source manifests.
    code = ('import sys,json;from pathlib import Path;sys.path.insert(0,sys.argv[1]);'
            'import run_production_adapter as p;print(json.dumps(p.verified_tariff_weights(Path(sys.argv[1]))))')
    result = subprocess.run([sys.executable, '-B', '-c', code, str(suite)], text=True, capture_output=True)
    require(result.returncode == 0, 'Rooted governing tariff validation failed: ' + result.stderr)
    weights = json.loads(result.stdout)
    require(weights and all(type(v) is int and v >= 0 for v in weights.values()), 'Invalid rooted tariff weights')
    plans = json_file(suite / 'plans/manifest.json')
    plan_paths = sorted(p.name for p in (suite / 'plans').glob('rcp-run-*.json'))
    plan_ids = [json_file(suite / 'plans' / p)['fixtureId'] for p in plan_paths]
    require(plans['count'] == len(plan_ids) and sorted(plans['fixtures']) == sorted(plan_ids)
            and len(plan_ids) == len(set(plan_ids)), 'Rooted literal plan inventory mismatch')
    require(set(plan_ids).issubset({r['id'] for r in rows if r['kind'] == 'runtime'}),
            'Literal plan references a non-runtime fixture')
    return {'packageFiles': len(files(companion)), 'fixtures': len(rows), 'runtimeFixtures': runtime,
            'literalPlans': len(plan_ids), 'tariffCounters': len(weights), 'implementationConformanceClaimed': False}


def validate_rooted_companion_checks(root, run_command, require):
    """Execute preserved checker/model gates, explicitly retaining their scope."""
    companion = root / COMPANION
    if not companion.exists():
        return None
    checks = []
    for name in ['test_harness.py', 'test_iteration2.py', 'test_checker_hardening.py', 'test_checkpoint_identity.py']:
        output = run_command([sys.executable, '-B', str(companion / 'tools' / name)])
        checks.append({'script': name, 'status': 'PASS', 'output': output})
    with tempfile.TemporaryDirectory(prefix='blue-rooted-model-') as temporary:
        result_path = Path(temporary) / 'abstract-model.json'
        run_command([sys.executable, '-B', str(companion / SUITE / 'model/run_fixture_models.py'), '--output', str(result_path)])
        model = json_file(result_path)
    require(model['status'] == 'PASS' and model['fail'] == 0
            and model['implementationConformanceClaimed'] is False and model['productionAdapterStatus'] == 'NOT_RUN',
            'Rooted abstract model failed or asserted unsupported production conformance')
    return {'status': 'ROOTED_PACKAGE_CHECKERS_VALID', 'checks': checks, 'model': model,
            'implementationConformanceClaimed': False}
