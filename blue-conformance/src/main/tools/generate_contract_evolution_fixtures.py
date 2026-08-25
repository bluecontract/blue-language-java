#!/usr/bin/env python3
"""Generate the portable ordinary C-EVO fixture family deterministically.

The generator is the authoritative source for C-EVO-01..10 and C-EVO-14..17.
C-EVO-11..13 remain aliases on the identity-bound closure graph fixtures.
Demand fixtures C-EVO-18..23 are generated separately through the normative
full-lifecycle closure exporter.
"""
from __future__ import annotations

from argparse import ArgumentParser
from copy import deepcopy
from pathlib import Path
from typing import Any, Callable

import yaml


EXTERNAL_CHANNEL = "2hesjWGVbvcJSu6woCUTssU9S7A69ep93UzdgvwosDLt"
HANDLER = "6rznQbYVahD1UVqdRXbPy7wF1NV5LYhDyzThEL1znaFw"
OPERATION = "FE68gr14jPkb9RfHbQH1tHGE4YALJbZvgC6CtdHwYheX"
FIXTURE_EVENT = "5KUZWsqRuW7SyRj1oCK7hRTmJKVCHTiVJboxy4nas8KX"
PROCESS_EMBEDDED = "EVJk3e7MLRhtTfMBNyrWYz1pWFXsbDTkPczeTviUuB4e"
INITIALIZED = "Hp3fNbpFxKwLiTwWAf3swpN7gKbsr6ofwEDMntiwXPaB"
TERMINATED = "4c1aabU6a3idKpWPzTRS4upLjCb6eZh3F1PDXkNh7i6v"
CHECKPOINT = "9cZbgd8aMa9wmFZyFxz6TCXBDEHqLMhrdZmhH7su96XR"
GENERALIZATION = "8VeXb3GgP88WtosVLu2mamHmbvY8f5cxA9z6yAETbbFz"
MANIFEST = "../../registry/manifest.yaml"


def typed(blue_id: str, **fields: Any) -> dict[str, Any]:
    return {"type": {"blueId": blue_id}, **fields}


def channel(
    subscription: str = "evolution",
    domain: str = "evolution-v1",
    order: int = 0,
) -> dict[str, Any]:
    return typed(
        EXTERNAL_CHANNEL,
        order=order,
        subscriptionKey=subscription,
        eventKey=subscription,
        accept=True,
        checkpointDomain=domain,
    )


def handler(channel_key: str, patches: list[dict[str, Any]]) -> dict[str, Any]:
    return typed(
        HANDLER,
        channel=channel_key,
        order=0,
        result={"patches": deepcopy(patches)},
    )


def completed_handler(
    channel_key: str,
    patches: list[dict[str, Any]],
) -> dict[str, Any]:
    value = handler(channel_key, patches)
    value["result"]["runtimeLedger"] = {
        "runtimeType": HANDLER,
        "counters": [],
    }
    return value


def scripted_handler(channel_key: str) -> dict[str, Any]:
    """Declare a real Handler whose result is supplied by fixture runtime."""
    return typed(HANDLER, channel=channel_key, order=0)


def operation(
    channel_key: str,
    patches: list[dict[str, Any]],
    operation_id: str | None = None,
    events: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    value = typed(
        OPERATION,
        channel=channel_key,
        order=0,
        result={"patches": deepcopy(patches)},
    )
    if operation_id is not None:
        value["operationId"] = operation_id
    if events is not None:
        value["result"]["events"] = deepcopy(events)
    return value


def event(event_id: str, subscription: str = "evolution") -> dict[str, Any]:
    return typed(FIXTURE_EVENT, subscriptionKey=subscription, id=event_id)


def feeder(ordinal: int, channel_key: str = "in") -> dict[str, Any]:
    return {
        "managedRootRevision": 1,
        "indexedRootRevision": 1,
        "eventOrderKey": [100, "evolution", ordinal],
        "deliverySnapshot": [
            {
                "scopePath": "/",
                "channelKey": channel_key,
                "order": 0,
                "activationStartExclusive": [0, "", 0],
            }
        ],
    }


def runtime(**fields: Any) -> dict[str, Any]:
    return {"typeRegistryManifest": MANIFEST, **fields}


def assertion(actual: str, op: str, expected: Any = None) -> dict[str, Any]:
    value: dict[str, Any] = {"actual": actual, "op": op}
    if op not in {"absent", "present"}:
        value["expected"] = expected
    return value


def envelope(
    fixture_id: str,
    vector: str,
    category: str,
    description: str,
    root: dict[str, Any],
    event_value: dict[str, Any],
    feeder_value: dict[str, Any],
    runtime_value: dict[str, Any],
    assertions: list[dict[str, Any]],
    operation_name: str = "process",
) -> dict[str, Any]:
    return {
        "schema": "blue-contracts-fixture/1.0",
        "id": fixture_id,
        "vectors": [vector],
        "category": category,
        "description": description,
        "operation": operation_name,
        "input": {
            "root": root,
            "event": event_value,
            "feeder": feeder_value,
            "provider": {"mode": "exact-node", "semanticDemandsOnly": True},
            "runtime": runtime_value,
        },
        "expected": {"assertions": assertions},
    }


def generalization_runtime(
    subtype_contracts: dict[str, Any],
    handler_key: str | None = None,
    patches: list[dict[str, Any]] | None = None,
) -> dict[str, Any]:
    fields: dict[str, Any] = {
        "generalizationCandidates": [
            "MostSpecific",
            "NearestValidAncestor",
            "Any",
        ],
        "generalizationSubtypeContracts": subtype_contracts,
    }
    if handler_key is not None:
        if patches is None:
            raise ValueError("scripted generalization Handler requires patches")
        fields["handlers"] = {
            f"/contracts/{handler_key}": {
                "result": {"patches": deepcopy(patches)}
            }
        }
    return runtime(
        **fields,
    )


def generalization_policy(mode: str = "nearest-valid-ancestor") -> dict[str, Any]:
    return typed(GENERALIZATION, defaultMode=mode)


def required_schema() -> dict[str, Any]:
    return {"schema": {"required": True}}


def required_workflow() -> dict[str, Any]:
    # Model the required application contract as a real registered Operation.
    # Its deliberately unrelated operation id prevents event selection; the
    # separate `mutate` Handler is the only executable transition in these
    # fixtures.  Ordinary authored fields keep the value valid before the
    # Handler removes it and conformance generalizes the document type.
    return typed(
        OPERATION,
        channel="in",
        operationId="EVO-UNRELATED-REQUIRED-WORKFLOW",
    )


def initialized_marker() -> dict[str, Any]:
    return typed(INITIALIZED, document={"name": "exact pre-initialization witness"})


def checkpoint_marker() -> dict[str, Any]:
    return typed(
        CHECKPOINT,
        entries={
            "old": {
                "domain": "old-domain",
                "subject": "old-subject",
            }
        },
    )


def c_evo_01() -> dict[str, Any]:
    patches = [
        {"op": "replace", "path": "/value", "val": 1},
        {"op": "remove", "path": "/contracts/workflow"},
    ]
    root = {
        "value": 0,
        "contracts": {"in": channel(), "workflow": handler("in", patches)},
    }
    return envelope(
        "c-evo-01",
        "C-EVO-01",
        "disc",
        "A selected Workflow removes itself while its frozen current delivery completes exactly once.",
        root,
        event("EVO-SELF-REMOVE-WORKFLOW"),
        feeder(1),
        runtime(),
        [
            assertion("result.status", "equals", "success"),
            assertion("result.document.value", "equals", 1),
            assertion("result.document.contracts.workflow", "absent"),
            assertion("trace.handlerExecutionCount", "equals", 1),
        ],
    )


def c_evo_02() -> dict[str, Any]:
    event_id = "EVO-SELF-REMOVE-OPERATION"
    patches = [
        {"op": "replace", "path": "/value", "val": 2},
        {"op": "remove", "path": "/contracts/operation"},
    ]
    root = {
        "value": 0,
        "contracts": {
            "in": channel(),
            "operation": operation("in", patches, event_id),
        },
    }
    return envelope(
        "c-evo-02",
        "C-EVO-02",
        "disc",
        "A selected distinct Scripted Operation removes itself while its frozen current invocation completes exactly once.",
        root,
        event(event_id),
        feeder(2),
        runtime(),
        [
            assertion("result.status", "equals", "success"),
            assertion("result.document.value", "equals", 2),
            assertion("result.document.contracts.operation", "absent"),
            assertion("trace.handlerExecutionCount", "equals", 1),
        ],
    )


def c_evo_03() -> dict[str, Any]:
    event_id = "EVO-CREATE-OPERATION"
    created = typed(
        OPERATION,
        channel="in",
        order=1,
        operationId=event_id,
        result={
            "patches": [
                {
                    "op": "replace",
                    "path": "/value",
                    "val": 99,
                }
            ],
            "runtimeLedger": {
                "runtimeType": OPERATION,
                "counters": [],
            },
        },
    )
    root = {
        "value": 0,
        "contracts": {
            "in": channel(),
            "creator": handler(
                "in",
                [
                    {
                        "op": "add",
                        "path": "/contracts/newOperation",
                        "val": created,
                    }
                ],
            ),
        },
    }
    return envelope(
        "c-evo-03",
        "C-EVO-03",
        "upd",
        "A Scripted Operation added during a transition is retained for later work but cannot receive its creating entry.",
        root,
        event(event_id),
        feeder(3),
        runtime(),
        [
            assertion("result.status", "equals", "success"),
            assertion("result.document.value", "equals", 0),
            assertion("result.document.contracts.newOperation", "present"),
            assertion("result.events", "sequenceEquals", []),
            assertion("trace.handlerExecutionCount", "equals", 1),
        ],
    )


def c_evo_04() -> dict[str, Any]:
    target_v2 = channel("evolution", "target-v2", 1)
    root = {
        "value": 0,
        "contracts": {
            "in": channel(),
            "target": channel("evolution", "target-v1", 1),
            "mutate": handler(
                "in",
                [
                    {"op": "remove", "path": "/contracts/target"},
                    {
                        "op": "add",
                        "path": "/contracts/target",
                        "val": target_v2,
                    },
                ],
            ),
        },
    }
    feeder_value = feeder(4)
    feeder_value["deliverySnapshot"].append(
        {
            "scopePath": "/",
            "channelKey": "target",
            "order": 1,
            "activationStartExclusive": [0, "", 0],
        }
    )
    return envelope(
        "c-evo-04",
        "C-EVO-04",
        "feed",
        "Removing and later re-adding one Channel creates a fresh activation interval rather than reusing its retired lineage.",
        root,
        event("EVO-CHANNEL-READD"),
        feeder_value,
        runtime(),
        [
            assertion("result.status", "equals", "success"),
            assertion(
                "commit.retiredIntervals.0.channelKey",
                "equals",
                "target",
            ),
            assertion(
                "commit.newIntervals.0.channelKey",
                "equals",
                "target",
            ),
            assertion(
                "commit.newIntervals.0.startAfterExternalOrderKey",
                "sequenceEquals",
                [100, "evolution", 4],
            ),
        ],
    )


def required_contract_fixture(
    fixture_id: str,
    vector: str,
    ordinal: int,
    mode: str,
    patches: list[dict[str, Any]],
    assertions: list[dict[str, Any]],
    description: str,
    category: str,
    root_fields: dict[str, Any] | None = None,
) -> dict[str, Any]:
    root = deepcopy(root_fields) if root_fields is not None else {"value": 0}
    root["contracts"] = {
        "in": channel(),
        "requiredWorkflow": required_workflow(),
        "mutate": scripted_handler("in"),
        "generalization": generalization_policy(mode),
    }
    return envelope(
        fixture_id,
        vector,
        category,
        description,
        root,
        event(f"{vector}-EVENT"),
        feeder(ordinal),
        generalization_runtime(
            {"requiredWorkflow": required_schema()},
            "mutate",
            patches,
        ),
        assertions,
    )


def c_evo_05() -> dict[str, Any]:
    return required_contract_fixture(
        "c-evo-05",
        "C-EVO-05",
        5,
        "nearest-valid-ancestor",
        [{"op": "remove", "path": "/contracts/requiredWorkflow"}],
        [
            assertion("result.status", "equals", "success"),
            assertion("result.document.contracts.requiredWorkflow", "absent"),
            assertion(
                "trace.generalizationSelected",
                "equals",
                "NearestValidAncestor",
            ),
            assertion(
                "trace.generalizationTestOrder",
                "sequenceEquals",
                ["MostSpecific", "NearestValidAncestor"],
            ),
        ],
        "Removing a subtype-required application contract uses the frozen nearest-valid-ancestor policy.",
        "snd",
    )


def c_evo_06() -> dict[str, Any]:
    return required_contract_fixture(
        "c-evo-06",
        "C-EVO-06",
        6,
        "reject",
        [{"op": "remove", "path": "/contracts/requiredWorkflow"}],
        [
            assertion("result.status", "equals", "runtime-fatal"),
            assertion(
                "result.diagnostic.category",
                "equals",
                "TypeGeneralizationFailure",
            ),
            {
                "actual": "result.document",
                "op": "equalsProjection",
                "expectedProjection": "input.root",
            },
            assertion("result.events", "sequenceEquals", []),
        ],
        "The same required-contract mutation under frozen reject policy is completely noncommitting.",
        "fail",
    )


def protected_fixture(
    suffix: str,
    protected_contracts: dict[str, Any],
    patches: list[dict[str, Any]],
    assertions: list[dict[str, Any]],
) -> dict[str, Any]:
    contracts = deepcopy(protected_contracts)
    contracts["in"] = channel()
    contracts["protectedAttempt"] = handler("in", patches)
    return envelope(
        f"c-evo-07-{suffix}",
        "C-EVO-07",
        "prot",
        f"Application mutation cannot change protected processor {suffix} state.",
        {"value": 0, "contracts": contracts},
        event(f"C-EVO-07-{suffix.upper()}"),
        feeder(7),
        runtime(),
        [
            assertion("result.status", "equals", "runtime-fatal"),
            assertion(
                "result.diagnostic.category",
                "equals",
                "ProtectedProcessorStateMutation",
            ),
            assertion("result.document.value", "equals", 0),
            assertion("result.events", "sequenceEquals", []),
            *assertions,
        ],
    )


def c_evo_07_initialized() -> dict[str, Any]:
    return protected_fixture(
        "initialized",
        {"initialized": initialized_marker()},
        [{"op": "replace", "path": "/contracts", "val": {}}],
        [assertion("result.document.contracts.initialized", "present")],
    )


def c_evo_07_checkpoint() -> dict[str, Any]:
    return protected_fixture(
        "checkpoint",
        {"checkpoint": checkpoint_marker()},
        [{"op": "replace", "path": "/contracts", "val": {}}],
        [assertion("result.document.contracts.checkpoint", "present")],
    )


def c_evo_07_terminated() -> dict[str, Any]:
    marker = typed(TERMINATED, cause="application-write")
    return protected_fixture(
        "terminated",
        {},
        [{"op": "add", "path": "/contracts/terminated", "val": marker}],
        [assertion("result.document.contracts.terminated", "absent")],
    )


def c_evo_08() -> dict[str, Any]:
    marker = initialized_marker()
    next_channel = channel("next-evolution", "next-evolution-v1")
    replacement = {"initialized": deepcopy(marker), "next": next_channel}
    root = {
        "value": 0,
        "contracts": {
            "initialized": marker,
            "in": channel(),
            "replaceContracts": handler(
                "in",
                [{"op": "replace", "path": "/contracts", "val": replacement}],
            ),
        },
    }
    return envelope(
        "c-evo-08",
        "C-EVO-08",
        "prot",
        "A complete contracts-map replacement is accepted when protected state preserves its exact Blue identity across canonical inline/reference representation.",
        root,
        event("EVO-PRESERVE-MARKER"),
        feeder(8),
        runtime(),
        [
            assertion("result.status", "equals", "success"),
            {
                "actual": "result.document.contracts.initialized.document",
                "op": "equalsProjection",
                "expectedProjection": "input.root.contracts.initialized.document",
            },
            {
                "actual": "result.document.contracts.initialized.directBlueId",
                "op": "equalsProjection",
                "expectedProjection": "input.root.contracts.initialized.directBlueId",
            },
            assertion(
                "result.document.contracts.next.subscriptionKey",
                "equals",
                "next-evolution",
            ),
            assertion("result.document.contracts.in", "absent"),
            assertion("result.document.contracts.replaceContracts", "absent"),
        ],
    )


def embedded_root(patch: dict[str, Any], handler_key: str) -> dict[str, Any]:
    return {
        "child": {"payload": "retained"},
        "contracts": {
            "initialized": initialized_marker(),
            "embedded": typed(PROCESS_EMBEDDED, paths=["/child"]),
            "in": channel(),
            handler_key: handler("in", [patch]),
        },
    }


def c_evo_09() -> dict[str, Any]:
    root = embedded_root(
        {"op": "remove", "path": "/contracts/embedded"},
        "removeEmbedded",
    )
    return envelope(
        "c-evo-09",
        "C-EVO-09",
        "emb",
        "Removing the complete Process Embedded contract preserves the former child as passive content.",
        root,
        event("EVO-REMOVE-EMBEDDED"),
        feeder(9),
        runtime(),
        [
            assertion("result.status", "equals", "success"),
            assertion("result.document.contracts.embedded", "absent"),
            assertion("result.document.child.payload", "equals", "retained"),
        ],
    )


def c_evo_10() -> dict[str, Any]:
    root = embedded_root(
        {"op": "remove", "path": "/contracts/embedded/paths/0"},
        "makePassive",
    )
    root["contracts"]["embedded"]["paths"].append("/reserved")
    return envelope(
        "c-evo-10",
        "C-EVO-10",
        "emb",
        "Removing the final route to one child converts that occurrence to passive content while another reserved route keeps Process Embedded valid.",
        root,
        event("EVO-PASSIVE-CHILD"),
        feeder(10),
        runtime(),
        [
            assertion("result.status", "equals", "success"),
            assertion("result.document.contracts.embedded", "present"),
            assertion(
                "result.document.contracts.embedded.paths",
                "sequenceEquals",
                ["/reserved"],
            ),
            assertion("result.document.child.payload", "equals", "retained"),
        ],
    )


def c_evo_14() -> dict[str, Any]:
    updates = [
        {
            "path": "/counter",
            "scopePath": "/",
            "beforePresent": True,
            "afterPresent": True,
        },
        {
            "path": "/type",
            "scopePath": "/",
            "beforePresent": True,
            "afterPresent": True,
        },
        {
            "path": "/contracts/requiredWorkflow",
            "scopePath": "/",
            "beforePresent": True,
            "afterPresent": False,
        },
    ]
    return required_contract_fixture(
        "c-evo-14",
        "C-EVO-14",
        14,
        "nearest-valid-ancestor",
        [
            {"op": "replace", "path": "/counter", "val": 1},
            {"op": "remove", "path": "/contracts/requiredWorkflow"},
        ],
        [
            assertion("result.status", "equals", "success"),
            assertion("trace.documentUpdates", "sequenceEquals", updates),
        ],
        "The generated type write is ordered immediately before the exact authored patch that first requires widening.",
        "snd",
        {"counter": 0},
    )


def c_evo_15() -> dict[str, Any]:
    root = {
        "value": 0,
        "contracts": {
            "in": channel(),
            "requiredWorkflow": required_workflow(),
            "generalize": scripted_handler("in"),
            "generalization": generalization_policy(),
        },
    }
    subtype_channel = channel("subtype-only", "subtype-only-v1", 1)
    return envelope(
        "c-evo-15",
        "C-EVO-15",
        "snd",
        "Nearest-ancestor generalization removes a subtype-only Channel and retires its active subscription.",
        root,
        event("EVO-REMOVE-SUBTYPE-CHANNEL"),
        feeder(15),
        generalization_runtime(
            {
                "requiredWorkflow": required_schema(),
                "subtypeChannel": subtype_channel,
            },
            "generalize",
            [
                {"op": "replace", "path": "/value", "val": 1},
                {"op": "remove", "path": "/contracts/requiredWorkflow"},
            ],
        ),
        [
            assertion("result.status", "equals", "success"),
            assertion(
                "trace.generalizationSelected",
                "equals",
                "NearestValidAncestor",
            ),
            assertion(
                "commit.retiredIntervals.0.channelKey",
                "equals",
                "subtypeChannel",
            ),
        ],
    )


def preinitialized_child() -> dict[str, Any]:
    body = {
        "payload": "retained",
        "contracts": {
            "childChannel": channel("child-only", "child-only-v1"),
        },
    }
    result = deepcopy(body)
    result["contracts"]["initialized"] = typed(
        INITIALIZED,
        document=deepcopy(body),
    )
    return result


def c_evo_16() -> dict[str, Any]:
    root = {
        "revision": 0,
        "child": preinitialized_child(),
        "contracts": {
            "in": channel(),
            "requiredWorkflow": required_workflow(),
            "generalize": scripted_handler("in"),
            "generalization": generalization_policy(),
        },
    }
    return envelope(
        "c-evo-16",
        "C-EVO-16",
        "emb",
        "Nearest-ancestor generalization removes a subtype-only Process Embedded declaration and retires its child subscriptions.",
        root,
        event("EVO-REMOVE-SUBTYPE-EMBEDDED"),
        feeder(16),
        generalization_runtime(
            {
                "requiredWorkflow": required_schema(),
                "embedded": typed(PROCESS_EMBEDDED, paths=["/child"]),
            },
            "generalize",
            [
                {
                    "op": "replace",
                    "path": "/revision",
                    "val": 1,
                },
                {"op": "remove", "path": "/contracts/requiredWorkflow"},
            ],
        ),
        [
            assertion("result.status", "equals", "success"),
            assertion(
                "trace.generalizationSelected",
                "equals",
                "NearestValidAncestor",
            ),
            assertion("commit.retiredIntervals.0.scopePath", "equals", "/child"),
            assertion(
                "result.document.child.payload",
                "equals",
                "retained",
            ),
        ],
    )


def c_evo_17() -> dict[str, Any]:
    patches = [
        {
            "op": "add",
            "path": "/contracts/embedded",
            "val": typed(PROCESS_EMBEDDED, paths=["/child"]),
        },
        {"op": "remove", "path": "/contracts/requiredWorkflow"},
    ]
    root = {
        "child": {
            "payload": "retained",
            "contracts": {
                "childChannel": channel("child-only", "child-only-v1"),
            },
        },
        "contracts": {
            "in": channel(),
            "requiredWorkflow": required_workflow(),
            "generalizeAndEmbed": scripted_handler("in"),
            "generalization": generalization_policy(),
        },
    }
    return envelope(
        "c-evo-17",
        "C-EVO-17",
        "emb",
        "One transition generalizes away a required Workflow and directly adds exact Process Embedded over existing content.",
        root,
        event("EVO-GENERALIZE-AND-EMBED"),
        feeder(17),
        generalization_runtime(
            {"requiredWorkflow": required_schema()},
            "generalizeAndEmbed",
            patches,
        ),
        [
            assertion("result.status", "equals", "success"),
            assertion("result.document.contracts.requiredWorkflow", "absent"),
            assertion("result.document.contracts.embedded", "present"),
            assertion("commit.newIntervals.0.scopePath", "equals", "/child"),
            assertion("result.document.child.payload", "equals", "retained"),
        ],
    )


GENERATORS: tuple[Callable[[], dict[str, Any]], ...] = (
    c_evo_01,
    c_evo_02,
    c_evo_03,
    c_evo_04,
    c_evo_05,
    c_evo_06,
    c_evo_07_initialized,
    c_evo_07_checkpoint,
    c_evo_07_terminated,
    c_evo_08,
    c_evo_09,
    c_evo_10,
    c_evo_14,
    c_evo_15,
    c_evo_16,
    c_evo_17,
)

FULL_LIFECYCLE_DEMAND_VECTORS = tuple(
    f"C-EVO-{ordinal:02d}" for ordinal in range(18, 24)
)


def render_fixtures() -> dict[str, bytes]:
    rendered: dict[str, bytes] = {}
    for generate in GENERATORS:
        fixture = generate()
        name = f"{fixture['id']}.yaml"
        text = yaml.safe_dump(
            fixture,
            allow_unicode=True,
            default_flow_style=False,
            sort_keys=False,
            width=100_000,
        )
        rendered[name] = text.encode("utf-8")
    return rendered


def write_fixtures(output_root: Path) -> None:
    output_root.mkdir(parents=True, exist_ok=True)
    expected = set(render_fixtures())
    existing = {path.name for path in output_root.glob("c-evo-*.yaml")}
    stale = existing - expected
    if stale:
        raise RuntimeError(f"unexpected stale C-EVO fixtures: {sorted(stale)}")
    for name, content in render_fixtures().items():
        (output_root / name).write_bytes(content)


def main() -> None:
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("--output-root", required=True, type=Path)
    arguments = parser.parse_args()
    write_fixtures(arguments.output_root.resolve())
    print(f"CONTRACT_EVOLUTION_FIXTURES_GENERATED count={len(GENERATORS)}")


if __name__ == "__main__":
    main()
