#!/usr/bin/env python3
"""Independent Contracts runtime-surface projection for fixture identities.

The Java processor derives closure subscription evidence from the resolved
effective Channel contribution and from its sanitized immutable header.  This
module mirrors that closed projection from the released registry ``.blue``
sources; it never hashes the authored contract as a substitute for either
runtime value.
"""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from blue_identity import direct_blue_id, normalized_preliminary_input_bytes


ROOT = Path(__file__).resolve().parents[1]
RELEASE_REGISTRY = ROOT / "conformance/contracts/registry"
REPOSITORY_REGISTRY = (
    ROOT / "resources/blue-contracts-closure-1.0/registry"
)
DEFAULT_REGISTRY = (
    RELEASE_REGISTRY
    if RELEASE_REGISTRY.is_dir()
    else REPOSITORY_REGISTRY
)

# These fields are direct ``Node`` headers in the released runtime classes.
# Their authored values are retained as separate dependency/body evidence; the
# effective runtime surface keeps the inherited registry placeholder closed.
DEFERRED_NODE_FIELDS_BY_REGISTRY_KEY = {
    "EmbeddedNodeChannel": frozenset(("event",)),
    "ScriptedExternalChannel": frozenset(("payload",)),
    "TriggeredEventChannel": frozenset(("event",)),
}


@dataclass(frozen=True)
class RuntimeSurfaceProjection:
    """Exact identity-bearing runtime contribution and subscription header."""

    effective_runtime_contribution: dict[str, Any]
    subscription_header: dict[str, Any]
    effective_runtime_contribution_blue_id: str
    subscription_header_blue_id: str


class RegistryTypeProjector:
    """Resolve the released registry type chain for one authored contract."""

    def __init__(self, registry: Path = DEFAULT_REGISTRY) -> None:
        self.registry = registry
        manifest = yaml.safe_load((registry / "manifest.yaml").read_text(
            encoding="utf-8"
        ))
        entries = manifest.get("entries")
        if not isinstance(entries, list):
            raise ValueError("runtime registry manifest has no entries")
        self._documents_by_blue_id: dict[str, dict[str, Any]] = {}
        self._keys_by_blue_id: dict[str, str] = {}
        for entry in entries:
            if not isinstance(entry, dict):
                raise ValueError("runtime registry entry must be an object")
            blue_id = entry.get("blueId")
            key = entry.get("key")
            relative_path = entry.get("path")
            if not all(isinstance(value, str) and value
                       for value in (blue_id, key, relative_path)):
                raise ValueError("runtime registry entry is incomplete")
            document = yaml.safe_load((registry / relative_path).read_text(
                encoding="utf-8"
            ))
            if not isinstance(document, dict):
                raise ValueError(f"registry document is not an object: {key}")
            self._documents_by_blue_id[blue_id] = document
            self._keys_by_blue_id[blue_id] = key
        self._resolved_types: dict[str, dict[str, Any]] = {}

    def project(self, contract: Any) -> RuntimeSurfaceProjection:
        """Project one authored registered contract into both exact surfaces."""
        if not isinstance(contract, dict):
            raise ValueError("runtime contribution must be an object")
        type_value = contract.get("type")
        if (not isinstance(type_value, dict)
                or set(type_value) != {"blueId"}
                or not isinstance(type_value.get("blueId"), str)):
            raise ValueError("runtime contribution needs one exact type BlueId")
        type_blue_id = type_value["blueId"]
        resolved_type = self._resolve_type(type_blue_id, ())
        registry_key = self._keys_by_blue_id[type_blue_id]
        deferred = DEFERRED_NODE_FIELDS_BY_REGISTRY_KEY.get(
            registry_key, frozenset()
        )

        effective: dict[str, Any] = {"type": deepcopy(resolved_type)}
        for field, value in resolved_type.items():
            if field not in {"type", "name", "description"}:
                effective[field] = deepcopy(value)
        for field, authored in contract.items():
            if field == "type" or authored is None:
                continue
            effective[field] = self._overlay_instance_field(
                effective.get(field), authored, field in deferred
            )

        header = {
            "type": {"blueId": type_blue_id},
            **{
                field: deepcopy(value)
                for field, value in effective.items()
                if field != "type"
            },
        }
        return RuntimeSurfaceProjection(
            effective_runtime_contribution=effective,
            subscription_header=header,
            effective_runtime_contribution_blue_id=direct_blue_id(effective),
            subscription_header_blue_id=direct_blue_id(header),
        )

    def _resolve_type(
        self,
        type_blue_id: str,
        active: tuple[str, ...],
    ) -> dict[str, Any]:
        cached = self._resolved_types.get(type_blue_id)
        if cached is not None:
            return deepcopy(cached)
        document = self._documents_by_blue_id.get(type_blue_id)
        if document is None:
            raise ValueError(
                f"runtime type is absent from released registry: {type_blue_id}"
            )
        if type_blue_id in active:
            raise ValueError(f"cyclic runtime type chain: {type_blue_id}")

        canonical_document = self._canonicalize_schema_enums(document)
        parent_blue_id = self._pure_reference_blue_id(
            canonical_document.get("type")
        )
        parent = (
            self._resolve_type(parent_blue_id, active + (type_blue_id,))
            if parent_blue_id in self._documents_by_blue_id
            else None
        )
        resolved: dict[str, Any] = {}
        if parent is not None:
            resolved["type"] = deepcopy(parent)
            for field, value in parent.items():
                if field not in {"type", "name", "description"}:
                    resolved[field] = deepcopy(value)
        for field, authored in canonical_document.items():
            if field == "type":
                continue
            resolved[field] = self._overlay_definition_field(
                resolved.get(field), authored
            )
        self._resolved_types[type_blue_id] = deepcopy(resolved)
        return resolved

    @classmethod
    def _overlay_definition_field(cls, base: Any, authored: Any) -> Any:
        if base is None or not isinstance(base, dict) \
                or not isinstance(authored, dict):
            return deepcopy(authored)
        result = deepcopy(base)
        for field, value in authored.items():
            result[field] = cls._overlay_definition_field(
                result.get(field), value
            )
        return result

    @classmethod
    def _overlay_instance_field(
        cls,
        base: Any,
        authored: Any,
        deferred_node: bool,
    ) -> Any:
        if deferred_node:
            if base is None:
                raise ValueError(
                    "deferred runtime Node field lacks a registry placeholder"
                )
            return deepcopy(base)
        if isinstance(authored, (str, int, float, bool)):
            if isinstance(base, dict):
                result = deepcopy(base)
                result["value"] = authored
                return result
            return deepcopy(authored)
        if isinstance(authored, list):
            if isinstance(base, dict):
                result = deepcopy(base)
                result["items"] = deepcopy(authored)
                return result
            return deepcopy(authored)
        if isinstance(authored, dict):
            if cls._pure_reference_blue_id(authored) is not None:
                return deepcopy(authored)
            if not isinstance(base, dict):
                return deepcopy(authored)
            result = deepcopy(base)
            for field, value in authored.items():
                result[field] = cls._overlay_instance_field(
                    result.get(field), value, False
                )
            return result
        raise ValueError(
            f"unsupported authored runtime field: {type(authored)!r}"
        )

    @classmethod
    def _canonicalize_schema_enums(cls, value: Any) -> Any:
        if isinstance(value, list):
            return [cls._canonicalize_schema_enums(item) for item in value]
        if not isinstance(value, dict):
            return deepcopy(value)
        result = {
            field: cls._canonicalize_schema_enums(child)
            for field, child in value.items()
        }
        schema = result.get("schema")
        if isinstance(schema, dict) and isinstance(schema.get("enum"), list):
            canonical: dict[bytes, Any] = {}
            for item in schema["enum"]:
                canonical[normalized_preliminary_input_bytes(item)] = item
            schema["enum"] = [canonical[key] for key in sorted(canonical)]
        return result

    @staticmethod
    def _pure_reference_blue_id(value: Any) -> str | None:
        if not isinstance(value, dict) or set(value) != {"blueId"}:
            return None
        blue_id = value.get("blueId")
        return blue_id if isinstance(blue_id, str) and blue_id else None


_DEFAULT_PROJECTOR: RegistryTypeProjector | None = None


def project_runtime_surface(contract: Any) -> RuntimeSurfaceProjection:
    """Use the released package registry to project one runtime surface."""
    global _DEFAULT_PROJECTOR
    if _DEFAULT_PROJECTOR is None:
        _DEFAULT_PROJECTOR = RegistryTypeProjector()
    return _DEFAULT_PROJECTOR.project(contract)
