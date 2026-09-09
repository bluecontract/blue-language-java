"""Closed Contracts checkpoint envelopes; these hashes confer no publication authority.

Unlike the original RCP string-only envelopes, the unchanged Contracts position
record encodes its epoch as a nonnegative safe JSON integer. All other operands
are strings. The domain/value envelope uses the Contracts canonical convention.
"""
from __future__ import annotations

import hashlib
import json
import re

SHA = re.compile(r"sha256:[0-9a-f]{64}\Z")
BLUE = re.compile(r"[1-9A-HJ-NP-Za-km-z]{32,44}(?:#(?:0|[1-9][0-9]*))?\Z")
DOMAINS = {
    "ROOTED_CHECKPOINT_REFERENCE_PROOF": "blue-rooted-checkpoint-reference-proof/1.0-draft.2",
    "ROOTED_CHECKPOINT_REPRESENTATION_POSITION": "blue-rooted-checkpoint-representation-position/1.0-draft.2",
}
FIELDS = {
    "ROOTED_CHECKPOINT_REFERENCE_PROOF": (
        "documentId", "beforeBlueId", "afterBlueId", "checkpointInputClosureIdentity",
        "checkpointOutputClosureIdentity", "rootProcessingContextIdentity",
        "rootedInvocationIdentity", "rootedCommitCompanionIdentity",
    ),
    "ROOTED_CHECKPOINT_REPRESENTATION_POSITION": (
        "documentId", "epoch", "anchorReceiptIdentity", "predecessorPositionIdentity",
        "beforeBlueId", "afterBlueId", "transitionReceiptIdentity", "originalInvocationIdentity",
        "inputClosureIdentity", "outputClosureIdentity", "commitCompanionIdentity",
        "checkpointReferenceProofIdentity",
    ),
}


def identity(constructor, value):
    """Validate only the closed operand types and compute their exact envelope."""
    if constructor not in FIELDS:
        raise ValueError("UNKNOWN_CHECKPOINT_CONSTRUCTOR")
    if not isinstance(value, dict) or set(value) != set(FIELDS[constructor]):
        raise ValueError("CLOSED_FIELDS")
    for field, operand in value.items():
        if field == "epoch":
            if type(operand) is not int or not 0 <= operand <= 9007199254740991:
                raise ValueError("SAFE_INTEGER_EPOCH")
            continue
        if not isinstance(operand, str):
            raise ValueError("STRING_REQUIRED")
        operand.encode("utf-8")
        if field == "documentId":
            if not operand:
                raise ValueError("DOCUMENT_ID")
        elif field.endswith("BlueId"):
            if not BLUE.fullmatch(operand):
                raise ValueError("BLUEID_GRAMMAR")
        elif not SHA.fullmatch(operand):
            raise ValueError("SHA_REQUIRED")
    envelope = json.dumps({"domain": DOMAINS[constructor], "value": value},
                          ensure_ascii=False, sort_keys=True, separators=(",", ":"),
                          allow_nan=False).encode("utf-8")
    return "sha256:" + hashlib.sha256(envelope).hexdigest()
