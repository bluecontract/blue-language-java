"""Independent envelope checks, separate from actual publication authentication."""
import importlib.util
import json
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / "conformance/rooted-processing"
SPEC = importlib.util.spec_from_file_location("checkpoint_constructors", ROOT / "identity/checkpoint_constructors.py")
CONSTRUCTORS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CONSTRUCTORS)


class CheckpointIdentityTest(unittest.TestCase):
    def test_exact_vectors_and_closed_type_negatives(self):
        vectors = json.loads((ROOT / "identity/checkpoint-reference-vectors.json").read_text())["vectors"]
        self.assertEqual(2, len(vectors))
        for vector in vectors:
            constructor, value = vector["constructor"], vector["value"]
            self.assertEqual(vector["expected"], CONSTRUCTORS.identity(constructor, value))
            for field in value:
                missing = dict(value)
                del missing[field]
                with self.subTest(constructor=constructor, field=field, mutation="missing"):
                    with self.assertRaises(ValueError):
                        CONSTRUCTORS.identity(constructor, missing)
                wrong_type = dict(value, **{field: True})
                with self.subTest(constructor=constructor, field=field, mutation="boolean"):
                    with self.assertRaises(ValueError):
                        CONSTRUCTORS.identity(constructor, wrong_type)
            with self.assertRaises(ValueError):
                CONSTRUCTORS.identity(constructor, dict(value, verified=True))
            self.assertNotEqual(vector["expected"], CONSTRUCTORS.identity(
                constructor, dict(value, afterBlueId=value["beforeBlueId"])))

    def test_epoch_is_the_existing_contracts_integer_not_an_rcp_decimal_string(self):
        value = json.loads((ROOT / "identity/checkpoint-reference-vectors.json").read_text())["vectors"][1]["value"]
        for invalid in ("0", -1, 0.0, 9007199254740992, None, False):
            with self.subTest(epoch=invalid), self.assertRaises(ValueError):
                CONSTRUCTORS.identity("ROOTED_CHECKPOINT_REPRESENTATION_POSITION", dict(value, epoch=invalid))



MANAGED_SPEC = importlib.util.spec_from_file_location("managed_successor_constructors", ROOT / "identity/managed_successor_constructors.py")
MANAGED = importlib.util.module_from_spec(MANAGED_SPEC)
MANAGED_SPEC.loader.exec_module(MANAGED)


class ManagedSuccessorIdentityTest(unittest.TestCase):
    def vectors(self):
        return json.loads((ROOT / "identity/managed-successor-vectors.json").read_text())["vectors"]

    def test_all_six_domains_preserve_closed_legacy_and_successor_vectors(self):
        vectors = self.vectors()
        self.assertEqual(12, len(vectors))
        self.assertEqual(set(MANAGED.DOMAINS), {v["domain"] for v in vectors})
        for v in vectors:
            domain, value = v["domain"], v["value"]
            self.assertEqual(v["expected"], MANAGED.identity(domain, value))
            for field in value:
                missing = dict(value); del missing[field]
                with self.subTest(domain=domain, field=field), self.assertRaises(ValueError):
                    MANAGED.identity(domain, missing)
                with self.subTest(domain=domain, boolean=field), self.assertRaises(ValueError):
                    MANAGED.identity(domain, dict(value, **{field: True}))
            with self.assertRaises(ValueError):
                MANAGED.identity(domain, dict(value, verified=True))
            for wrong_domain in set(MANAGED.DOMAINS) - {domain}:
                with self.assertRaises(ValueError):
                    MANAGED.identity(wrong_domain, value)

    def test_successor_anchor_cursor_rejects_consumption_and_forged_goal(self):
        import copy
        v = next(v for v in self.vectors() if v["domain"] == MANAGED.SUCCESSOR_RECEIPT)
        for field, value in (("anchorReceiptIdentity", "sha256:" + "0" * 64),
                             ("positionIdentity", "sha256:" + "0" * 64),
                             ("targetPositionIdentity", v["value"]["sourceReceiptIdentity"]),
                             ("nextRevisionReceiptIdentity", "sha256:" + "0" * 64)):
            changed = copy.deepcopy(v["value"]); changed["resultingRepresentationCursor"][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                MANAGED.identity(v["domain"], changed)
        for cursor in (None, {}, dict(v["value"]["resultingRepresentationCursor"], verified=True)):
            with self.assertRaises(ValueError):
                MANAGED.identity(v["domain"], dict(v["value"], resultingRepresentationCursor=cursor))

    def test_work_integer_pointer_identity_and_unicode_negatives(self):
        v = next(v for v in self.vectors() if v["domain"] == MANAGED.SUCCESSOR_WORK)
        for field, value in (("sourceEpoch", -1), ("sourceEpoch", "0"), ("sourceEpoch", 0.0),
                             ("sourceEpoch", 9007199254740992), ("activationGeneration", 0),
                             ("targetPath", ""), ("targetPath", "/~2bad"),
                             ("targetPath", "/bad\x00"), ("sourceDocumentId", "bad\x00"),
                             ("sourceDocumentId", " \u2000\t"),
                             ("sourceDocumentId", "e\u0301"), ("consumerDocumentId", "\ud800"),
                             ("successorRepresentationCauseIdentity", "unreviewed"),
                             ("expectedConsumerCommittedBlueId", "1" * 32)):
            with self.subTest(field=field, value=repr(value)), self.assertRaises(ValueError):
                MANAGED.identity(v["domain"], dict(v["value"], **{field: value}))
        changed = dict(v["value"], successorRepresentationCauseIdentity="sha256:" + "0" * 64)
        self.assertNotEqual(v["expected"], MANAGED.identity(v["domain"], changed))
        # Java isBlank deliberately accepts these nonbreaking/NEL characters.
        for valid in ("\u00a0", "\u0085", "\u2007", "\u202f", "source\U0001f600"):
            with self.subTest(valid=repr(valid)):
                self.assertNotEqual(v["expected"], MANAGED.identity(
                    v["domain"], dict(v["value"], sourceDocumentId=valid)))


import sys
sys.path.insert(0, str(ROOT))
from check_successor_carriers import verify_carrier, check_call_carriers, step, did, digest
from identity import revision_successor_constructors as REVISION


class SuccessorCarrierOracleTest(unittest.TestCase):
    def setUp(self):
        import copy
        self.copy = copy.deepcopy
        self.require = lambda ok, code: None if ok else (_ for _ in ()).throw(ValueError(code))
        self.equal = lambda a,b: a == b
        h = lambda n: "sha256:" + str(n) * 64
        before = "5gFKLVTQ52SMFMEKeTxcLyEU9ki39F5tcqRLGuvEQGTj"
        after = "E4545nCNu6n5dYuLHpbjYzFxDv3WRFvXVcxVAYo2MqrJ"
        self.anchor = {"receiptIdentity": h(3), "epoch": 0, "afterBlueId": after}
        proof_receipt = {"transitionReceiptIdentity": h(9), "originalCauseIdentity": h(8),
                         "beforeBlueId": after, "afterBlueId": before, "emittedRootEvents": []}
        tr = {"documentId": {"value": "S"}, "epoch": 0, "anchorReceiptIdentity": h(3),
              "predecessorPositionIdentity": h(3),
              "afterDocument": {}, "transitionReceipt": proof_receipt,
              "originalInput": {"invocationIdentity": h(7), "snapshot": {"closureIdentity": h(6),
                  "managedDocuments": [{"documentId": {"value": "S"}, "epoch": 0, "blueId": after}]}},
              "originalResult": {"outputClosureIdentity": h(5), "platformCommitCompanion": {"companionIdentity": h(4)},
                  "resultingDocuments": [{"documentId": {"value": "S"}, "epoch": 0,
                      "beforeBlueId": after, "afterBlueId": before, "document": {}}]},
              "rootedCheckpointReferenceProofIdentity": None}
        tr["positionIdentity"] = digest("blue-managed-representation-position/1", {
            "documentId": "S", "epoch": 0, "anchorReceiptIdentity": h(3), "predecessorPositionIdentity": h(3),
            "beforeBlueId": after, "afterBlueId": before, "transitionReceiptIdentity": h(9),
            "originalInvocationIdentity": h(7), "inputClosureIdentity": h(6), "outputClosureIdentity": h(5), "commitCompanionIdentity": h(4)})
        future = {"kind": "MANAGED_REPRESENTATION", "targetOccurrenceIdentity": h(4), "childDocumentId": {"value": "S"},
                  "fromEpoch": 0, "toEpoch": 0, "beforeBlueId": after, "afterBlueId": before, "afterDocument": {},
                  "sourceTransitionReceipt": proof_receipt, "sourceRevisionReceiptIdentity": h(9),
                  "originalSourceCauseIdentity": h(8), "afterCyclicProof": None, "transition": tr,
                  "targetPositionIdentity": h(7), "nextRevisionReceiptIdentity": None, "terminalPositionReached": False}
        future["causeIdentity"] = digest("blue-managed-representation-step/1", {
            "targetOccurrenceIdentity": h(4), "representationPositionIdentity": tr["positionIdentity"],
            "targetPositionIdentity": h(7), "nextRevisionReceiptIdentity": None})
        cause = {"kind": "MANAGED_REVISION", "targetOccurrenceIdentity": h(4), "childDocumentId": {"value": "S"},
                 "fromEpoch": -1, "toEpoch": 0, "beforeBlueId": before, "afterBlueId": after, "afterDocument": {},
                 "sourceTransitionReceipt": {}, "sourceRevisionReceiptIdentity": h(2),
                 "originalSourceCauseIdentity": h(1), "afterCyclicProof": None, "successorRepresentationCause": future}
        value = {k: did(cause[k]) if k == "childDocumentId" else cause[k] for k in REVISION.FIELDS}
        value["successorRepresentationCauseIdentity"] = future["causeIdentity"]
        cause["causeIdentity"] = REVISION.identity(REVISION.SUCCESSOR, value)
        self.cause = cause
        work = next(v["value"] for v in json.loads((ROOT / "identity/managed-successor-vectors.json").read_text())["vectors"]
                    if v["domain"] == MANAGED.WORK)
        work = dict(work, sourceDocumentId={"value": "S"}, consumerDocumentId={"value": "P"},
                    representationStep=None, successorRepresentationStep=step(future))
        value = {k: did(work[k]) if k.endswith("DocumentId") else work[k] for k in MANAGED.WORK_FIELDS}
        value["successorRepresentationCauseIdentity"] = future["causeIdentity"]
        work["workIdentity"] = MANAGED.identity(MANAGED.SUCCESSOR_WORK, value); self.work = work
        self.cursor = step(future)["before"]
        self.terminal = {"causeType": "ManagedRevisionCause", "invocationIdentity": h(6),
                         "commitCompanion": {"companionIdentity": h(8), "outputClosureIdentity": h(7)},
                         "occurrenceBindings": [{"occurrenceIdentity": h(4), "pendingRepresentationCursor": dict(self.cursor, identityValue=self.cursor),
                                                 "active": False, "pendingHistoricalEpoch": 0}]}
        receipt = dict(next(v["value"] for v in json.loads((ROOT / "identity/managed-successor-vectors.json").read_text())["vectors"]
                         if v["domain"] == MANAGED.RECEIPT), workIdentity=work["workIdentity"],
                       representationCauseIdentity=None, successorRepresentationCauseIdentity=future["causeIdentity"],
                       resultingRepresentationCursor=self.cursor)
        rv = {k: receipt[k] for k in MANAGED.RECEIPT_FIELDS}
        rv.update(successorRepresentationCauseIdentity=future["causeIdentity"], resultingRepresentationCursor=self.cursor)
        receipt["applicationReceiptIdentity"] = MANAGED.identity(MANAGED.SUCCESSOR_RECEIPT, rv)
        receipt["consumerDocumentId"] = {"value": "P"}; self.receipt = receipt

    def check(self, work=None, cause=None, terminal=None, receipt=None, api=False):
        return verify_carrier(work or self.work, cause or self.cause, terminal or self.terminal,
                              self.receipt if receipt is None else receipt, self.anchor, self.require, self.equal, api)

    def test_exact_local_owned_and_api_projection(self):
        self.assertEqual(self.cause["successorRepresentationCause"], self.check())
        api = {k:v for k,v in self.work.items() if k not in ("representationStep", "successorRepresentationStep")}
        api.update(representationCause=None, successorRepresentationCause=self.cause["successorRepresentationCause"],
                   isRepresentationApplication=False, expectedNextSourceEpoch=0)
        self.check(work=api, api=True)
        verify_carrier(self.work, self.cause, self.terminal, None, self.anchor, self.require, self.equal)

    def test_new_profile_missing_or_extra_work_fields_reject(self):
        for field in self.work:
            w = self.copy(self.work); del w[field]
            with self.subTest(field=field), self.assertRaises((ValueError,KeyError)):
                self.check(work=w)
        with self.assertRaises(ValueError): self.check(work=dict(self.work, verified=True))
        for field in ("successorRepresentationCause",):
            c = self.copy(self.cause); del c[field]
            with self.assertRaises(ValueError): self.check(cause=c)

    def test_future_cannot_count_as_executed_or_advance_anchor(self):
        w = self.copy(self.work); w["representationStep"] = w["successorRepresentationStep"]
        with self.assertRaises(ValueError): self.check(work=w)
        for field,value in (("active",True),("pendingHistoricalEpoch",1),
                            ("pendingRepresentationCursor",None)):
            t = self.copy(self.terminal); t["occurrenceBindings"][0][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError): self.check(terminal=t)
        r = self.copy(self.receipt); r["resultingSourceCursor"] = 0
        with self.assertRaises(ValueError): self.check(receipt=r)
        r = self.copy(self.receipt); r["resultingRepresentationCursor"]["positionIdentity"] = self.cause["successorRepresentationCause"]["transition"]["positionIdentity"]
        with self.assertRaises(ValueError): self.check(receipt=r)

    def test_core_cursor_requires_its_exact_identity_projection(self):
        for field in self.cursor:
            t = self.copy(self.terminal)
            t["occurrenceBindings"][0]["pendingRepresentationCursor"]["identityValue"][field] = "forged"
            with self.subTest(field=field), self.assertRaises(ValueError): self.check(terminal=t)
        t = self.copy(self.terminal)
        del t["occurrenceBindings"][0]["pendingRepresentationCursor"]["identityValue"]
        with self.assertRaises(ValueError): self.check(terminal=t)

    def test_future_endpoints_require_unique_original_source_rows(self):
        for inventory in ("before", "after"):
            for mutation in ("missing", "duplicate", "wrong-endpoint", "wrong-bytes"):
                c = self.copy(self.cause); tr = c["successorRepresentationCause"]["transition"]
                rows = (tr["originalInput"]["snapshot"]["managedDocuments"] if inventory == "before"
                        else tr["originalResult"]["resultingDocuments"])
                if mutation == "missing": rows.clear()
                elif mutation == "duplicate": rows.append(self.copy(rows[0]))
                elif mutation == "wrong-endpoint": rows[0]["blueId" if inventory == "before" else "afterBlueId"] = "forged"
                elif inventory == "after": rows[0]["document"] = {"value": "forged"}
                else: rows[0]["documentId"] = {"value": "another-source"}
                with self.subTest(inventory=inventory, mutation=mutation), self.assertRaises(ValueError): self.check(cause=c)

    def test_forged_future_transition_and_original_companion_reject(self):
        for field,value in (("anchorReceiptIdentity","sha256:"+"0"*64),
                            ("predecessorPositionIdentity","sha256:"+"0"*64),
                            ("positionIdentity","sha256:"+"0"*64)):
            c=self.copy(self.cause);c["successorRepresentationCause"]["transition"][field]=value
            with self.subTest(field=field), self.assertRaises(ValueError):self.check(cause=c)
        c=self.copy(self.cause);c["successorRepresentationCause"]["transition"]["originalResult"]["platformCommitCompanion"]["companionIdentity"]="sha256:"+"0"*64
        with self.assertRaises(ValueError):self.check(cause=c)
        for field in self.receipt:
            r=self.copy(self.receipt);del r[field]
            with self.subTest(receiptField=field), self.assertRaises((ValueError,KeyError)):self.check(receipt=r)

    def test_future_original_headers_and_pre_call_membership_are_required(self):
        """Synthetic envelope joins only; full graph/gas validation remains separate."""
        future = self.cause["successorRepresentationCause"]
        tr = future["transition"]
        inp, result = tr["originalInput"], tr["originalResult"]
        inp["snapshot"]["managedDocuments"] = [{"documentId": {"value": "S"},
            "epoch": 0, "blueId": future["beforeBlueId"]}]
        companion = result["platformCommitCompanion"]
        companion.update(invocationIdentity=inp["invocationIdentity"],
            inputClosureIdentity=inp["snapshot"]["closureIdentity"],
            outputClosureIdentity=result["outputClosureIdentity"])
        result.update(status="SUCCESS", invocationIdentity=inp["invocationIdentity"],
            inputClosureIdentity=inp["snapshot"]["closureIdentity"], gasTrace={"charges": []},
            managedTransitionReceipts=[tr["transitionReceipt"]], rootedProjection={},
            resultingDocuments=[{"documentId": {"value": "S"}, "epoch": 0,
                "beforeBlueId": future["beforeBlueId"], "afterBlueId": future["afterBlueId"], "document": future["afterDocument"]}])
        original = {"publicationIdentity": "original", "status": "SUCCESS",
            "invocationIdentity": inp["invocationIdentity"], "input": inp,
            "commitCompanion": companion, "fullGasTrace": result["gasTrace"],
            "outputSnapshot": {"closureIdentity": result["outputClosureIdentity"]},
            "managedTransitionReceipts": result["managedTransitionReceipts"],
            "rootedProjection": {}, "resultingDocuments": result["resultingDocuments"]}
        self.terminal.update(publicationIdentity="consumer", input={"cause": self.cause})
        call = {"terminals": [self.terminal], "managedAttempts": [],
            "localRetainedApplications": [{"work": self.work, "result": {"closureId": "consumer"}}],
            "before": {"S": {"receipts": [self.anchor]}},
            "progressBefore": {"representationPublications": {"original": original},
                "representationRows": {"S": [{"originalPublicationIdentity": "original", "epoch": 0,
                    "transitionReceiptIdentity": tr["transitionReceipt"]["transitionReceiptIdentity"]}]}}}
        def check(value):
            check_call_carriers(value, {"S": "S", "P": "P"}, self.require, self.equal)
        check(call)
        for field, replacement in (("status", "GAS_LIMIT_EXCEEDED"),
                ("invocationIdentity", "sha256:" + "0" * 64),
                ("inputClosureIdentity", "sha256:" + "0" * 64),
                ("outputClosureIdentity", "sha256:" + "0" * 64),
                ("gasTrace", {"charges": ["invented"]})):
            changed = self.copy(call)
            changed["terminals"][0]["input"]["cause"]["successorRepresentationCause"]["transition"]["originalResult"][field] = replacement
            with self.subTest(field=field), self.assertRaises(ValueError):
                check(changed)
        for field, empty in (("representationPublications", {}), ("representationRows", {"S": []})):
            changed = self.copy(call); changed["progressBefore"][field] = empty
            with self.subTest(field=field), self.assertRaises(ValueError):
                check(changed)

if __name__ == "__main__":
    unittest.main()
