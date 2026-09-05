#!/usr/bin/env python3
"""Controlled JMH setup failure: compare legacy exit-zero behavior with fail-on-error."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--dependency", type=Path, action="append", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    dependencies = [path.resolve() for path in args.dependency]
    cp = ":".join(map(str, dependencies))
    records = {"dependencies": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in dependencies}, "runs": []}
    with tempfile.TemporaryDirectory(prefix="b2-jmh-failure-") as temp:
        directory = Path(temp)
        source = directory / "probe/FailingSetup.java"
        source.parent.mkdir()
        source.write_text('''package probe;
import org.openjdk.jmh.annotations.*;
@State(Scope.Thread)
public class FailingSetup {
    @Setup(Level.Trial) public void setup() { throw new IllegalStateException("B2_CONTROLLED_SETUP_FAILURE"); }
    @Benchmark public int required() { return 42; }
}
''')
        compile_command = [str(args.java_home / "bin/javac"), "-cp", cp, "-processorpath", cp, "-d", str(directory), str(source)]
        subprocess.run(compile_command, check=True, capture_output=True, text=True)
        for label, fail_on_error in (("legacy", "false"), ("strict", "true")):
            result_file = args.output.resolve() / (label + ".json")
            result_file.unlink(missing_ok=True)
            command = [str(args.java_home / "bin/java"), "-cp", str(directory) + ":" + cp,
                       "org.openjdk.jmh.Main", "^probe.FailingSetup.required$", "-foe", fail_on_error,
                       "-wi", "0", "-i", "1", "-f", "1", "-r", "25ms", "-rf", "json", "-rff", str(result_file)]
            started = time.perf_counter()
            result = subprocess.run(command, capture_output=True, text=True, timeout=60)
            log = result.stdout + result.stderr
            (args.output / (label + ".log")).write_text(log)
            assert "B2_CONTROLLED_SETUP_FAILURE" in log, log
            assert (result.returncode == 0) == (label == "legacy"), log
            values = json.loads(result_file.read_text()) if result_file.exists() and result_file.stat().st_size else []
            assert values == [], values
            records["runs"].append({"label": label, "command": command, "exit": result.returncode,
                                    "wallSeconds": time.perf_counter() - started, "measuredResults": len(values)})
    (args.output / "receipt.json").write_text(json.dumps(records, indent=2) + "\n")
    print(json.dumps(records["runs"], indent=2))


if __name__ == "__main__":
    main()
