"""Independent exact SDK/API carrier binding; future evidence is never applied work.

Use with the existing full publication/graph/gas checks, never instead of them.
New-profile observations require every nullable carrier accessor explicitly.
"""
import hashlib
import json
from identity import managed_successor_constructors as M
from identity import revision_successor_constructors as R
from identity import checkpoint_constructors as C

SDK_WORK = set(M.WORK_FIELDS) | {"workIdentity", "representationStep", "successorRepresentationStep"}
API_WORK = set(M.WORK_FIELDS) | {"workIdentity", "representationCause", "successorRepresentationCause",
                                    "isRepresentationApplication", "expectedNextSourceEpoch"}
RECEIPT = set(M.RECEIPT_FIELDS) | {"applicationReceiptIdentity", "representationCauseIdentity",
                                    "successorRepresentationCauseIdentity", "resultingRepresentationCursor"}
BASE_CAUSE = set(R.FIELDS) | {"afterDocument", "sourceTransitionReceipt", "afterCyclicProof", "kind", "causeIdentity"}


def did(value):
    return value["value"] if isinstance(value, dict) else value


def digest(domain, value):
    return "sha256:" + hashlib.sha256(json.dumps({"domain": domain, "value": value},
        sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode()).hexdigest()


def position(cause, before):
    tr = cause["transition"]
    return {"anchorReceiptIdentity": tr["anchorReceiptIdentity"],
            "positionIdentity": tr["predecessorPositionIdentity" if before else "positionIdentity"],
            "targetPositionIdentity": cause["targetPositionIdentity"],
            "nextRevisionReceiptIdentity": cause["nextRevisionReceiptIdentity"]}


def step(cause):
    if cause is None:
        return None
    return {"causeIdentity": cause["causeIdentity"], "beforeBlueId": cause["beforeBlueId"],
            "afterBlueId": cause["afterBlueId"], "before": position(cause, True),
            "after": position(cause, False), "terminal": cause["terminalPositionReached"]}


def verify_carrier(work, cause, terminal, application, source_receipt, require, exact_equal, api=False):
    require(set(work) == (API_WORK if api else SDK_WORK), "CARRIER_WORK_INVENTORY")
    numbered = terminal["causeType"] == "ManagedRevisionCause"
    require(terminal["causeType"] in ("ManagedRevisionCause", "ManagedRepresentationCause"), "CARRIER_CAUSE_FAMILY")
    require(set(cause) == BASE_CAUSE | ({"successorRepresentationCause"} if numbered else
            {"transition", "targetPositionIdentity", "nextRevisionReceiptIdentity", "terminalPositionReached"}), "CARRIER_CAUSE_INVENTORY")
    require(cause["kind"] == ("MANAGED_REVISION" if numbered else "MANAGED_REPRESENTATION"), "CARRIER_CAUSE_KIND")
    future = cause["successorRepresentationCause"] if numbered else None
    applied = None if numbered else cause
    require(did(cause["childDocumentId"]) == did(work["sourceDocumentId"])
            and cause["targetOccurrenceIdentity"] == work["targetOccurrenceIdentity"]
            and source_receipt["receiptIdentity"] == work["sourceReceiptIdentity"]
            and source_receipt["epoch"] == work["sourceEpoch"], "CARRIER_SOURCE_ANCHOR")
    require(cause["toEpoch"] == work["sourceEpoch"]
            and cause["fromEpoch"] == work["sourceEpoch"] - (1 if numbered else 0), "CARRIER_EPOCH_POSITION")
    if api:
        require(exact_equal(work["representationCause"], applied)
                and exact_equal(work["successorRepresentationCause"], future)
                and work["isRepresentationApplication"] is (not numbered)
                and work["expectedNextSourceEpoch"] == work["sourceEpoch"] + (0 if numbered else 1), "CARRIER_API_PROJECTION")
    else:
        require(exact_equal(work["representationStep"], step(applied))
                and exact_equal(work["successorRepresentationStep"], step(future)), "CARRIER_SDK_PROJECTION")
    value = {k: did(work[k]) if k.endswith("DocumentId") else work[k] for k in M.WORK_FIELDS}
    domain = M.WORK if numbered else M.REP_WORK
    if applied is not None:
        value["representationCauseIdentity"] = cause["causeIdentity"]
    if future is not None:
        require(numbered and set(future) == BASE_CAUSE | {"transition", "targetPositionIdentity",
                "nextRevisionReceiptIdentity", "terminalPositionReached"}, "CARRIER_FUTURE_INVENTORY")
        tr = future["transition"]
        source_transition = tr["transitionReceipt"]
        require(future["terminalPositionReached"] is (future["targetPositionIdentity"] == tr["positionIdentity"]
                and future["nextRevisionReceiptIdentity"] is None)
                and future["sourceRevisionReceiptIdentity"] == source_transition["transitionReceiptIdentity"]
                and future["originalSourceCauseIdentity"] == source_transition["originalCauseIdentity"]
                and exact_equal(future["sourceTransitionReceipt"], source_transition)
                and future["beforeBlueId"] == tr["beforeBlueId"] == source_transition["beforeBlueId"]
                and future["afterBlueId"] == tr["afterBlueId"] == source_transition["afterBlueId"]
                and exact_equal(future["afterDocument"], tr["afterDocument"])
                and source_transition["emittedRootEvents"] == [], "CARRIER_FUTURE_TRANSITION")
        operands = {"documentId": did(tr["documentId"]), "epoch": tr["epoch"],
                "anchorReceiptIdentity": tr["anchorReceiptIdentity"], "predecessorPositionIdentity": tr["predecessorPositionIdentity"],
                "beforeBlueId": tr["beforeBlueId"], "afterBlueId": tr["afterBlueId"],
                "transitionReceiptIdentity": source_transition["transitionReceiptIdentity"],
                "originalInvocationIdentity": tr["originalInput"]["invocationIdentity"],
                "inputClosureIdentity": tr["originalInput"]["snapshot"]["closureIdentity"],
                "outputClosureIdentity": tr["originalResult"]["outputClosureIdentity"],
                "commitCompanionIdentity": tr["originalResult"]["platformCommitCompanion"]["companionIdentity"]}
        proof = tr["rootedCheckpointReferenceProofIdentity"]
        if proof is None:
            position_id = digest("blue-managed-representation-position/1", operands)
        else:
            operands["checkpointReferenceProofIdentity"] = proof
            position_id = C.identity("ROOTED_CHECKPOINT_REPRESENTATION_POSITION", operands)
        require(position_id == tr["positionIdentity"], "CARRIER_FUTURE_POSITION_IDENTITY")
        require(future["targetOccurrenceIdentity"] == cause["targetOccurrenceIdentity"]
                and did(future["childDocumentId"]) == did(cause["childDocumentId"])
                and future["fromEpoch"] == future["toEpoch"] == cause["toEpoch"]
                and future["beforeBlueId"] == cause["afterBlueId"] == source_receipt["afterBlueId"]
                and tr["anchorReceiptIdentity"] == tr["predecessorPositionIdentity"] == work["sourceReceiptIdentity"]
                and future["nextRevisionReceiptIdentity"] is None
                and future["targetPositionIdentity"] != work["sourceReceiptIdentity"], "CARRIER_FUTURE_EXACT_START")
        require(future["causeIdentity"] == digest("blue-managed-representation-step/1", {
            "targetOccurrenceIdentity": future["targetOccurrenceIdentity"],
            "representationPositionIdentity": tr["positionIdentity"],
            "targetPositionIdentity": future["targetPositionIdentity"],
            "nextRevisionReceiptIdentity": None}), "CARRIER_FUTURE_CAUSE_IDENTITY")
        value["successorRepresentationCauseIdentity"] = future["causeIdentity"]; domain = M.SUCCESSOR_WORK
    require(M.identity(domain, value) == work["workIdentity"], "CARRIER_WORK_IDENTITY")
    if numbered:
        numbered_value = {k: did(cause[k]) if k == "childDocumentId" else cause[k] for k in R.FIELDS}
        if future is not None: numbered_value["successorRepresentationCauseIdentity"] = future["causeIdentity"]
        require(R.identity(R.SUCCESSOR if future is not None else R.LEGACY, numbered_value)
                == cause["causeIdentity"], "CARRIER_NUMBERED_CAUSE_IDENTITY")
    targets = [r for r in terminal["occurrenceBindings"] if r["occurrenceIdentity"] == work["targetOccurrenceIdentity"]]
    require(len(targets) == 1, "CARRIER_RESULT_OCCURRENCE")
    target = targets[0]
    expected_cursor = position(future, True) if future is not None else (
        None if numbered or cause["terminalPositionReached"] else position(cause, False))
    require(exact_equal(target["pendingRepresentationCursor"], expected_cursor), "CARRIER_RESULT_POSITION")
    if future is not None:
        require(target["active"] is False and target["pendingHistoricalEpoch"] == work["sourceEpoch"], "CARRIER_FUTURE_EXECUTED_EARLY")
    if application is not None:
        require(set(application) == RECEIPT, "CARRIER_RECEIPT_INVENTORY")
        require(application["workIdentity"] == work["workIdentity"] and application["planIdentity"] == work["planIdentity"]
                and application["sourceReceiptIdentity"] == work["sourceReceiptIdentity"]
                and application["resultingSourceCursor"] == work["sourceEpoch"] + 1
                and application["contractsInvocationIdentity"] == terminal["invocationIdentity"]
                and application["contractsResultIdentity"] == terminal["commitCompanion"]["outputClosureIdentity"]
                and application["commitCompanionIdentity"] == terminal["commitCompanion"]["companionIdentity"]
                and did(application["consumerDocumentId"]) == did(work["consumerDocumentId"]), "CARRIER_RECEIPT_BINDING")
        require(application["representationCauseIdentity"] == (None if numbered else cause["causeIdentity"])
                and application["successorRepresentationCauseIdentity"] == (None if future is None else future["causeIdentity"])
                and exact_equal(application["resultingRepresentationCursor"], expected_cursor), "CARRIER_RECEIPT_POSITION")
        rv = {k: did(application[k]) if k.endswith("DocumentId") else application[k] for k in M.RECEIPT_FIELDS}
        rd = M.RECEIPT
        if applied is not None:
            rv.update(representationCauseIdentity=cause["causeIdentity"], resultingRepresentationCursor=expected_cursor); rd = M.REP_RECEIPT
        if future is not None:
            rv.update(successorRepresentationCauseIdentity=future["causeIdentity"], resultingRepresentationCursor=expected_cursor); rd = M.SUCCESSOR_RECEIPT
        require(M.identity(rd, rv) == application["applicationReceiptIdentity"], "CARRIER_RECEIPT_IDENTITY")
    return future


def check_call_carriers(call, ids, require, exact_equal):
    aliases = {v:k for k,v in ids.items()}
    for terminal in call["terminals"]:
        if terminal["causeType"] not in ("ManagedRevisionCause", "ManagedRepresentationCause"):
            continue
        cause = terminal["input"]["cause"]
        local = [a for a in call["localRetainedApplications"] if a["result"]["closureId"] == terminal["publicationIdentity"]]
        owned = [a for a in call["managedAttempts"] if a["receipt"] is not None
                 and a["receipt"]["contractsInvocationIdentity"] == terminal["invocationIdentity"]]
        require(len(local) + len(owned) == 1, "CARRIER_ACTUAL_APPLICATION_INVENTORY")
        work = local[0]["work"] if local else owned[0]["work"]
        source = aliases[did(work["sourceDocumentId"])]
        anchors = [r for r in call["before"][source]["receipts"] if r["receiptIdentity"] == work["sourceReceiptIdentity"]]
        require(len(anchors) == 1, "CARRIER_IMMUTABLE_ANCHOR_INVENTORY")
        future = verify_carrier(work, cause, terminal, None if local else owned[0]["receipt"], anchors[0], require, exact_equal)
        if "selection" in call and call["selection"]["kind"] == "MANAGED_EPOCH_APPLICATION":
            selected = call["selection"]["managedEpochApplicationWork"]
            require(selected["workIdentity"] == work["workIdentity"], "CARRIER_SELECTED_IDENTITY")
            verify_carrier(selected, cause, terminal, None, anchors[0], require, exact_equal, api=True)
        if owned:
            application = owned[0]["receipt"]
            consumer = aliases[did(work["consumerDocumentId"]) ]
            retained = [r for r in call["after"][consumer]["receipts"]
                        if r["receiptIdentity"] == application["consumerRevisionReceiptIdentity"]]
            require(len(retained) == 1 and retained[0]["epoch"] == application["consumerRevisionEpoch"]
                    and retained[0]["afterBlueId"] == application["consumerCommittedBlueId"], "CARRIER_CONSUMER_RETAINED_RECEIPT")
        if future is not None:
            # The caller's existing full position checker must authenticate
            # this original publication too. A future hash alone is not proof.
            require("progressBefore" in call, "CARRIER_MISSING_ORIGINAL_PUBLICATIONS")
            tr = future["transition"]
            originals = call["progressBefore"]["representationPublications"]
            matches = [p for p in originals.values() if p["input"]["invocationIdentity"] == tr["originalInput"]["invocationIdentity"]
                       and p["commitCompanion"]["companionIdentity"] == tr["originalResult"]["platformCommitCompanion"]["companionIdentity"]]
            require(len(matches) == 1, "CARRIER_UNAUTHENTICATED_FUTURE")
            original = matches[0]
            r = tr["originalResult"]; companion = original["commitCompanion"]
            require(original["status"] == r["status"] == "SUCCESS"
                    and original["invocationIdentity"] == original["input"]["invocationIdentity"]
                    == r["invocationIdentity"] == companion["invocationIdentity"]
                    and r["inputClosureIdentity"] == companion["inputClosureIdentity"]
                    == original["input"]["snapshot"]["closureIdentity"]
                    and r["outputClosureIdentity"] == companion["outputClosureIdentity"]
                    == original["outputSnapshot"]["closureIdentity"]
                    and exact_equal(r["gasTrace"], original["fullGasTrace"]), "CARRIER_FUTURE_COMMITTED_HEADERS")
            before_source = [d for d in original["input"]["snapshot"]["managedDocuments"] if did(d["documentId"]) == ids[source]]
            after_source = [d for d in original["resultingDocuments"] if did(d["documentId"]) == ids[source]]
            require(len(before_source) == len(after_source) == 1
                    and before_source[0]["epoch"] == after_source[0]["epoch"] == work["sourceEpoch"]
                    and before_source[0]["blueId"] == future["beforeBlueId"]
                    and after_source[0]["afterBlueId"] == future["afterBlueId"]
                    and exact_equal(after_source[0]["document"], future["afterDocument"])
                    and tr["transitionReceipt"] in original["managedTransitionReceipts"], "CARRIER_FUTURE_SOURCE_PUBLICATION")
            require(exact_equal(tr["originalInput"], original["input"])
                    and all(exact_equal(tr["originalResult"][a], original[b]) for a,b in (
                        ("platformCommitCompanion", "commitCompanion"), ("managedTransitionReceipts", "managedTransitionReceipts"),
                        ("resultingDocuments", "resultingDocuments"), ("rootedProjection", "rootedProjection"))), "CARRIER_FUTURE_ORIGINAL_BYTES")
            rows = [r for r in call["progressBefore"]["representationRows"][source]
                    if r["originalPublicationIdentity"] == original["publicationIdentity"]
                    and r["transitionReceiptIdentity"] == tr["transitionReceipt"]["transitionReceiptIdentity"]]
            require(len(rows) == 1 and rows[0]["epoch"] == work["sourceEpoch"], "CARRIER_FUTURE_HISTORY_MEMBERSHIP")
