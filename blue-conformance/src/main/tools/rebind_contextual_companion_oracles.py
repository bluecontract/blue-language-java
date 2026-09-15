#!/usr/bin/env python3
"""Rebind the C-CLO-23 test constants from the independent cyclic-set oracle.

The canonical input and direct algorithm remain authoritative. Changing the
CheckpointEntry dependency may change both the master and sorted member slots.
No runtime/JUnit actual output is an input to this tool.
"""
import argparse
from pathlib import Path
import re
import sys

sys.dont_write_bytecode = True
import yaml
from blue_identity import cyclic_set_oracle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[4]
    oracle_path = root / ("blue-conformance/src/main/resources/blue-contracts-closure-1.0/"
                          "oracles/c-clo-23-05-a9-to-a10.yaml")
    stage = yaml.safe_load(oracle_path.read_text())["stages"][-1]
    documents = stage["sourceDocumentsWithThisReferences"]
    oracle = cyclic_set_oracle(documents)
    assert oracle.master_blue_id == stage["masterBlueId"]
    assert list(oracle.member_ids_in_source_order()) == stage["memberBlueIdsInSourceOrder"]
    slots = {document["documentId"]: member.sorted_index
             for document, member in zip(documents, oracle.members)}
    path = root / ("blue-conformance/src/test/java/blue/language/conformance/contracts/closure/"
                   "Cclo23HistoricalEpochMatrixTest.java")
    old = path.read_text()
    updated, count = re.subn(r'(FINAL_MASTER\s*=\s*")[^"]+(";)',
                             lambda m: m[1] + oracle.master_blue_id + m[2], old)
    assert count == 1
    for name, document_id in (("A", "history-a"), ("B", "history-b")):
        updated, count = re.subn(r'(FINAL_' + name + r'_MEMBER_INDEX = )\d+(;)',
                                lambda m: m[1] + str(slots[document_id]) + m[2], updated)
        assert count == 1
    if args.write:
        path.write_text(updated)
    elif updated != old:
        raise SystemExit("Stale independent C-CLO-23 bindings")
    path = path.with_name("ManagedRevisionCyclicClosureFixtureTest.java")
    old = path.read_text()
    updated, count = re.subn(r'(MASTER\s*=\s*")[^"]+(";)',
                            lambda m: m[1] + oracle.master_blue_id + m[2], old)
    assert count == 1
    updated, count = re.subn(r'(A_MEMBER_INDEX = )\d+(;)',
                            lambda m: m[1] + str(slots["history-a"]) + m[2], updated)
    assert count == 1
    if args.write:
        path.write_text(updated)
    elif updated != old:
        raise SystemExit("Stale independent managed revision bindings")
    path = root / "blue-conformance/src/main/tools/test_generate_identity_impact_inventory.py"
    old = path.read_text()
    updated, count = re.subn(
        r'(self\.assertEqual\(\s*")[^"]+(",\s*cyclic_master\["newExactIdentity"\])',
        lambda match: match[1] + oracle.master_blue_id + match[2], old)
    assert count == 1
    if args.write:
        path.write_text(updated)
    elif updated != old:
        raise SystemExit("Stale independent identity inventory assertion")
    print("CONTEXTUAL_COMPANION_ORACLES_OK: " + oracle.master_blue_id + " " + str(slots))


if __name__ == "__main__":
    main()
