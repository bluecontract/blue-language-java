"""Experimental full-build measurement, outside production PR. No publication."""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

import psutil


def inventory(root):
    cases = []
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for path in sorted(root.glob('**/build/test-results/**/TEST-*.xml')):
        if '.gradle' in path.parts:
            continue
        tree = ET.parse(path).getroot()
        for case in tree.iter('testcase'):
            outcome = 'passed'
            for key in ('failure', 'error', 'skipped'):
                if case.find(key) is not None:
                    outcome = key
            cases.append([str(path.relative_to(root)), case.get('classname'), case.get('name'), outcome])
            totals['tests'] += 1
            if outcome != 'passed':
                totals[dict(failure='failures', error='errors', skipped='skipped')[outcome]] += 1
    return {'totals': totals, 'cases': sorted(cases)}


def main():
    out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
    command = sys.argv[3:]
    started = time.monotonic()
    metadata = {'command': command, 'forks': os.getenv('BLUE_EXPERIMENT_FORKS'),
                'sha': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
                'affinity': psutil.Process().cpu_affinity(), 'logical_cpus': psutil.cpu_count(),
                'physical_cpus': psutil.cpu_count(logical=False),
                'memory_bytes': psutil.virtual_memory().total, 'started_epoch': time.time()}
    (out / 'metadata.json').write_text(json.dumps(metadata, indent=2))
    child = subprocess.Popen(command)
    tracked = {child.pid: psutil.Process(child.pid)}
    previous = {}; cpu_total = 0; peak_pss = peak_rss = peak_swap = max_workers = 0
    with (out / 'samples.jsonl').open('w') as stream:
        while child.poll() is None:
            for process in list(tracked.values()):
                try:
                    for descendant in process.children(recursive=True):
                        tracked.setdefault(descendant.pid, descendant)
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
            rss = pss = swap = workers = 0; active = []
            for process in list(tracked.values()):
                try:
                    with process.oneshot():
                        key = (process.pid, process.create_time())
                        usage = process.cpu_times(); cpu = usage.user + usage.system
                        cpu_total += max(0, cpu - previous.get(key, 0)); previous[key] = cpu
                        mem = process.memory_full_info(); rss += mem.rss; pss += mem.pss; swap += mem.swap
                        args = process.cmdline()
                        workers += int('Gradle Test Executor' in ' '.join(args))
                        active.append({'pid': process.pid, 'command': args, 'affinity': process.cpu_affinity()})
                except (psutil.NoSuchProcess, psutil.AccessDenied, ProcessLookupError):
                    pass
            peak_pss = max(peak_pss, pss); peak_rss = max(peak_rss, rss); peak_swap = max(peak_swap, swap)
            max_workers = max(max_workers, workers)
            stream.write(json.dumps({'elapsed': time.monotonic() - started, 'cpu_seconds': cpu_total,
                                     'pss': pss, 'rss': rss, 'swap': swap, 'workers': workers,
                                     'processes': active}) + '\n'); stream.flush()
            time.sleep(1)
    elapsed = time.monotonic() - started
    result = {'exit_code': child.returncode, 'elapsed_seconds': elapsed, 'sampled_cpu_seconds': cpu_total,
              'sampled_average_cores': cpu_total / elapsed, 'peak_tree_pss_bytes': peak_pss,
              'peak_tree_rss_bytes': peak_rss, 'peak_tree_swap_bytes': peak_swap,
              'max_observed_test_jvms': max_workers,
              'limits': 'One-second sampled lower bounds; RSS double-counts shared pages. Hosted logical CPUs, not dedicated physical cores.'}
    (out / 'result.json').write_text(json.dumps(result, indent=2))
    (out / 'inventory.json').write_text(json.dumps(inventory(Path.cwd()), indent=2))
    print(json.dumps(result), flush=True)
    return child.returncode


if __name__ == '__main__':
    sys.exit(main())
