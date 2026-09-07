#!/usr/bin/env python3
"""Out-of-band provenance recorded by Contracts release validation."""
from __future__ import annotations

from pathlib import Path
import hashlib
from typing import Any


class SourceArchiveProvenanceError(ValueError):
    """Raised when a requested source archive cannot be inspected."""


def source_archive_provenance(source: Path | None) -> dict[str, Any]:
    """Describe an archive without treating it as a semantic release input."""
    if source is None:
        return {"provided": False}
    if not source.is_file():
        raise SourceArchiveProvenanceError(
            f"source archive not found: {source}"
        )
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return {
        "provided": True,
        "suppliedName": source.name,
        "sha256": digest.hexdigest(),
    }
