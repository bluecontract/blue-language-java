"""RCP draft.2 internal envelopes. Not a BlueId calculator or evidence verifier.
Only string/object/array JCS values are accepted. All keys are ASCII. Callers
must independently authenticate content/admission/graph operands before use.
"""
from __future__ import annotations
import hashlib,json,re,unicodedata
SHA=re.compile(r"sha256:[0-9a-f]{64}\Z")
BLUE=re.compile(r"[1-9A-HJ-NP-Za-km-z]{32,44}(?:#(?:0|[1-9][0-9]*))?\Z")
def closed(x,keys):
    if not isinstance(x,dict) or set(x)!=set(keys): raise ValueError("CLOSED_FIELDS:"+str(keys))
def scalar(s):
    if not isinstance(s,str):raise ValueError("STRING_REQUIRED")
    try:s.encode("utf-8")
    except UnicodeEncodeError:raise ValueError("LONE_SURROGATE")
    return s

def sha(s):
    if not isinstance(s,str) or not SHA.fullmatch(s):raise ValueError("SHA_REQUIRED")
    return s

def blue(s):
    if not isinstance(s,str) or not BLUE.fullmatch(s):raise ValueError("BLUEID_GRAMMAR")
    return s

def doc(s):
    scalar(s)
    if not s or "\x00" in s or unicodedata.normalize("NFC",s)!=s or len(s.encode())>512:raise ValueError("DOCUMENT_ID")
    return s

def decimal(s):
    if not isinstance(s,str) or not re.fullmatch(r"0|[1-9][0-9]*",s) or int(s)>9007199254740991:raise ValueError("DECIMAL")
    return s

def pointer(s):
    scalar(s)
    if s and (not s.startswith("/") or re.search(r"~(?![01])",s)):raise ValueError("POINTER")
    return s

def canonical(value):
    def check(x):
        if isinstance(x,str):scalar(x)
        elif isinstance(x,list):
            for y in x:check(y)
        elif isinstance(x,dict):
            for k,v in x.items():
                if not isinstance(k,str) or not k.isascii():raise ValueError("ASCII_KEYS")
                check(v)
        else:raise ValueError("ENVELOPE_SUBSET")
    check(value)
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(",",":"),allow_nan=False).encode("utf-8")

def digest(domain,value):return "sha256:"+hashlib.sha256(canonical({"domain":domain,"value":value})).hexdigest()

def order_key(x):
    closed(x,["timestampUs","timelineBlueId","entryBlueId"]);decimal(x["timestampUs"]);blue(x["timelineBlueId"]);blue(x["entryBlueId"])

def history(x):
    closed(x,["documentId","initialDocumentBlueId","runtimeSemanticsIdentity","admission"])
    doc(x["documentId"]);blue(x["initialDocumentBlueId"]);sha(x["runtimeSemanticsIdentity"])
    a=x["admission"];m=a.get("mode") if isinstance(a,dict) else None
    if m=="FULL_HISTORY":closed(a,["mode"])
    elif m in ("FROM_FRONTIER","FROM_NOW"):
        closed(a,["mode","lowerExclusiveOrder"]);order_key(a["lowerExclusiveOrder"])
    elif m=="CREATED_IN_OPERATION":
        closed(a,["mode","lowerExclusiveOrder","creatorOperationIdentity","birthOccurrenceIdentity"])
        order_key(a["lowerExclusiveOrder"]);sha(a["creatorOperationIdentity"]);sha(a["birthOccurrenceIdentity"])
    else:raise ValueError("ADMISSION_MODE")
    return digest("blue-document-history-basis/1.0-draft.2",x)

def owner(x):
    closed(x,["members","internalEdges"])
    if not isinstance(x["members"],list) or not x["members"] or not isinstance(x["internalEdges"],list):raise ValueError("OWNER_SETS")
    members=[];ids=set()
    for m in x["members"]:
        closed(m,["documentId","historyBasisIdentity"]);doc(m["documentId"]);sha(m["historyBasisIdentity"])
        if m["documentId"] in ids:raise ValueError("DUPLICATE_MEMBER")
        ids.add(m["documentId"]);members.append(m)
    edges=[];seen=set()
    for e in x["internalEdges"]:
        closed(e,["occurrenceIdentity","parentDocumentId","sourcePath","childDocumentId","activationGeneration"])
        sha(e["occurrenceIdentity"]);pointer(e["sourcePath"]);decimal(e["activationGeneration"])
        if e["parentDocumentId"] not in ids or e["childDocumentId"] not in ids:raise ValueError("NON_INTERNAL_EDGE")
        if e["occurrenceIdentity"] in seen:raise ValueError("DUPLICATE_EDGE")
        seen.add(e["occurrenceIdentity"]);edges.append(e)
    value={"members":sorted(members,key=lambda y:y["documentId"].encode()),"internalEdges":sorted(edges,key=lambda y:y["occurrenceIdentity"].encode())}
    return value,digest("blue-operation-owner/1.0-draft.2",value)

def context(owner_descriptor):
    o,identity=owner(owner_descriptor)
    value={"canonicalRootDocumentId":o["members"][0]["documentId"],"operationOwnerIdentity":identity}
    return value,digest("blue-contracts-root-processing-context/1.0-draft.2",value)

def delivery(x):
    closed(x,["operationOwnerIdentity","causeIdentity","kind","receivingBindings","sourcePositionIdentity"])
    sha(x["operationOwnerIdentity"]);sha(x["causeIdentity"])
    if x["kind"] not in ("LIVE","ADMISSION","MANAGED_REVISION","MANAGED_REPRESENTATION"):raise ValueError("KIND")
    if not isinstance(x["receivingBindings"],list) or not x["receivingBindings"]:raise ValueError("RECEIVING_BINDINGS")
    rows=[];seen=set()
    for b in x["receivingBindings"]:
        closed(b,["documentId","scopePath","activationGeneration","channelKey","occurrenceIdentity"])
        doc(b["documentId"]);pointer(b["scopePath"]);decimal(b["activationGeneration"]);sha(b["occurrenceIdentity"])
        if not scalar(b["channelKey"]):raise ValueError("CHANNEL_KEY")
        raw=canonical(b)
        if raw in seen:raise ValueError("DUPLICATE_BINDING")
        seen.add(raw);rows.append(b)
    p=x["sourcePositionIdentity"]
    if x["kind"] in ("LIVE","ADMISSION"):
        closed(p,["kind"])
        if p["kind"]!="NONE":raise ValueError("POSITION_TAG")
    else:
        closed(p,["kind","identity"])
        if p["kind"]!="POSITION":raise ValueError("POSITION_TAG")
        sha(p["identity"])
    value=dict(x);value["receivingBindings"]=sorted(rows,key=canonical)
    return value,digest("blue-rooted-delivery-basis/1.0-draft.2",value)

def wrapper(name,values):
    types={
      "rootedInvocationIdentity":("blue-contracts-rooted-invocation/1.0-draft.2",["baseInvocationIdentity","rootProcessingContextIdentity","deliveryBasisIdentity"]),
      "rootedCommitCompanionIdentity":("blue-contracts-rooted-commit-companion/1.0-draft.2",["baseCommitCompanionIdentity","rootedInvocationIdentity","rootProcessingContextIdentity"]),
      "rootedTerminalKey":("blue-coordination-rooted-terminal-key/1.0-draft.2",["operationOwnerIdentity","deliveryBasisIdentity"])}
    if name not in types:raise ValueError("CONSTRUCTOR")
    domain,fields=types[name];closed(values,fields)
    for v in values.values():sha(v)
    return digest(domain,values)
