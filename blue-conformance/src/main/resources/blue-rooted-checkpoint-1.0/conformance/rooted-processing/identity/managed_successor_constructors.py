"""Closed Coordination work/receipt envelopes. Hashes do not authenticate history."""
import hashlib
import json
import re
import unicodedata

WORK = "blue-coordination-managed-epoch-application-work/1.0"
REP_WORK = "blue-coordination-managed-representation-application-work/1.0"
SUCCESSOR_WORK = "blue-coordination-managed-epoch-with-representation-successor-work/1.0"
RECEIPT = "blue-coordination-managed-epoch-application-receipt/1.0"
REP_RECEIPT = "blue-coordination-managed-representation-application-receipt/1.0"
SUCCESSOR_RECEIPT = "blue-coordination-managed-epoch-with-representation-successor-receipt/1.0"
WORK_FIELDS = ("planIdentity", "barrierIdentity", "sourceReceiptIdentity", "sourceDocumentId",
               "sourceEpoch", "consumerDocumentId", "targetOccurrenceIdentity", "targetPath",
               "activationGeneration", "expectedConsumerCommittedEpoch",
               "expectedConsumerCommittedBlueId", "expectedGraphGeneration")
RECEIPT_FIELDS = ("workIdentity", "planIdentity", "sourceReceiptIdentity", "contractsInvocationIdentity",
                  "contractsResultIdentity", "commitCompanionIdentity", "consumerDocumentId",
                  "consumerRevisionEpoch", "consumerRevisionReceiptIdentity", "consumerCommittedBlueId",
                  "resultingSourceCursor")
CURSOR_FIELDS = ("anchorReceiptIdentity", "positionIdentity", "targetPositionIdentity", "nextRevisionReceiptIdentity")
DOMAINS = {
    WORK: WORK_FIELDS,
    REP_WORK: WORK_FIELDS + ("representationCauseIdentity",),
    SUCCESSOR_WORK: WORK_FIELDS + ("successorRepresentationCauseIdentity",),
    RECEIPT: RECEIPT_FIELDS,
    REP_RECEIPT: RECEIPT_FIELDS + ("representationCauseIdentity", "resultingRepresentationCursor"),
    SUCCESSOR_RECEIPT: RECEIPT_FIELDS + ("successorRepresentationCauseIdentity", "resultingRepresentationCursor"),
}
INTEGERS = {"sourceEpoch", "activationGeneration", "expectedConsumerCommittedEpoch",
            "expectedGraphGeneration", "consumerRevisionEpoch", "resultingSourceCursor"}
ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
# String.isBlank / Character.isWhitespace on the Java 21 host. Python strip()
# additionally removes NEL and nonbreaking spaces, which are valid Java text.
JAVA_WHITESPACE = frozenset("\t\n\v\f\r\x1c\x1d\x1e\x1f "
                            "\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006"
                            "\u2008\u2009\u200a\u2028\u2029\u205f\u3000")


def blue_id(value):
    if not isinstance(value, str):
        raise ValueError("BlueId must be text")
    parts = value.split("#")
    if len(parts) > 2 or (len(parts) == 2 and not re.fullmatch(r"0|[1-9][0-9]*", parts[1])):
        raise ValueError("Invalid cyclic member")
    plain = parts[0]
    if not plain or len(plain) > 44 or any(c not in ALPHABET for c in plain):
        raise ValueError("Invalid plain BlueId")
    magnitude = 0
    for c in plain:
        magnitude = magnitude * 58 + ALPHABET.index(c)
    leading = len(plain) - len(plain.lstrip("1"))
    if magnitude == 0 or leading + (magnitude.bit_length() + 7) // 8 != 32:
        raise ValueError("BlueId must encode a canonical SHA-256 value")


def text(field, value):
    if (not isinstance(value, str) or all(c in JAVA_WHITESPACE for c in value)
            or "\x00" in value or unicodedata.normalize("NFC", value) != value):
        raise ValueError("Invalid portable text: " + field)
    if any(0xD800 <= ord(c) <= 0xDFFF for c in value):
        raise ValueError("Unpaired surrogate")
    if field.endswith("Identity") and not re.fullmatch(r"sha256:[0-9a-f]{64}", value):
        raise ValueError("Invalid SHA-256 identity: " + field)
    if field.endswith("BlueId"):
        blue_id(value)
    if field == "targetPath" and (not value.startswith("/") or re.search(r"~(?![01])", value)):
        raise ValueError("Noncanonical occurrence pointer")


def cursor(value, required):
    if value is None and not required:
        return
    if not isinstance(value, dict) or set(value) != set(CURSOR_FIELDS):
        raise ValueError("Representation cursor fields are closed")
    for field, operand in value.items():
        if field == "nextRevisionReceiptIdentity" and operand is None:
            continue
        text(field, operand)


def identity(domain, value):
    if domain not in DOMAINS or not isinstance(value, dict) or set(value) != set(DOMAINS[domain]):
        raise ValueError("Work/receipt constructor fields and domains are closed")
    for field, operand in value.items():
        if field in INTEGERS:
            if type(operand) is not int or not (1 if field == "activationGeneration" else 0) <= operand <= 9007199254740991:
                raise ValueError("Invalid nonnegative safe integer: " + field)
        elif field == "resultingRepresentationCursor":
            cursor(operand, domain == SUCCESSOR_RECEIPT)
        else:
            text(field, operand)
    if domain == SUCCESSOR_RECEIPT:
        c = value["resultingRepresentationCursor"]
        if c["anchorReceiptIdentity"] != value["sourceReceiptIdentity"] or c["positionIdentity"] != value["sourceReceiptIdentity"]:
            raise ValueError("Numbered receipt must remain at its immutable anchor")
        if c["nextRevisionReceiptIdentity"] is not None or c["targetPositionIdentity"] == c["anchorReceiptIdentity"]:
            raise ValueError("Numbered receipt must retain a distinct unconsumed terminal goal")
    # JCS-equivalent for this closed subset: every key (including cursor keys)
    # is fixed ASCII; all other operands are Unicode scalars, safe integers or
    # null. Do not reuse this encoder for arbitrary non-ASCII object keys.
    encoded = json.dumps({"domain": domain, "value": value}, sort_keys=True, ensure_ascii=False,
                         separators=(",", ":"), allow_nan=False).encode("utf-8")
    return "sha256:" + hashlib.sha256(encoded).hexdigest()
