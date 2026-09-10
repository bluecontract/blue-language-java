"""Independent closed numbered/successor envelopes; hashing proves no history authority."""
import hashlib
import json
import re
import unicodedata

LEGACY = "blue-contracts-managed-revision-cause/1.0"
SUCCESSOR = "blue-contracts-managed-revision-cause-with-representation-successor/1.0"
FIELDS = ("targetOccurrenceIdentity", "childDocumentId", "fromEpoch", "toEpoch",
          "beforeBlueId", "afterBlueId", "originalSourceCauseIdentity", "sourceRevisionReceiptIdentity")


def identity(domain, value):
    if domain not in (LEGACY, SUCCESSOR) or not isinstance(value, dict):
        raise ValueError("Unknown revision constructor")
    fields = FIELDS + (("successorRepresentationCauseIdentity",) if domain == SUCCESSOR else ())
    if set(value) != set(fields):
        raise ValueError("Revision constructor fields are closed")
    for field in fields:
        operand = value[field]
        if field in ("fromEpoch", "toEpoch"):
            if type(operand) is not int or not (-1 if field == "fromEpoch" else 0) <= operand <= 9007199254740991:
                raise ValueError("Invalid managed epoch")
        else:
            if not isinstance(operand, str) or not operand or unicodedata.normalize("NFC", operand) != operand:
                raise ValueError("Invalid portable text")
            if domain == SUCCESSOR and field.endswith("BlueId") and not re.fullmatch(r"[1-9A-HJ-NP-Za-km-z]{32,44}(?:#(?:0|[1-9][0-9]*))?", operand):
                raise ValueError("Invalid BlueId operand")
            if field.endswith("Identity") and not re.fullmatch(r"sha256:[0-9a-f]{64}", operand):
                raise ValueError("Invalid identity operand")
    if value["toEpoch"] != value["fromEpoch"] + 1:
        raise ValueError("Numbered work must advance exactly one epoch")
    encoded = json.dumps({"domain": domain, "value": value}, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode()
    return "sha256:" + hashlib.sha256(encoded).hexdigest()
