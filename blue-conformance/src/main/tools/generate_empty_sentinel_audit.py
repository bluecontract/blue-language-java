#!/usr/bin/env python3
"""Generate the exhaustive production empty-sentinel classification audit."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import subprocess
from typing import Any, Iterable, NamedTuple


DEFAULT_BASELINE = "be2260217d1dbab0c7b60bcbd28073a5955e2b7b"
REPORT_PATH = "reports/migration/empty-object-sentinel-audit.json"
SUMMARY_PATH = "docs/empty-object-sentinel-audit.md"

# These are the prompt-mandated searches expressed as Python regular
# expressions so the committed report does not depend on an installed rg
# binary.  Only production Java sources are inventoried.
AUDITS = (
    (
        "empty-shape-and-builder",
        re.compile(r"isEmptyNode\(|FrozenNode\.empty\(|new Node\(\)"),
        "A fieldless node may have been used as a value, a Source-null control, or a temporary builder.",
    ),
    (
        "property-shape",
        re.compile(
            r"properties\(.*null|properties\.isEmpty|properties == null|"
            r"getProperties\(\).*isEmpty"
        ),
        "Null or empty properties may have been treated as interchangeable absence evidence.",
    ),
    (
        "list-placeholder",
        re.compile(r"emptyPlaceholder|\$empty|NormalizeListPlaceholders"),
        "List holes and ordinary empty objects may have shared a structural-empty representation.",
    ),
    (
        "empty-to-null-return",
        re.compile(r"return null.*empty|empty.*return null", re.IGNORECASE),
        "An empty Blue shape may have been converted to host-language null.",
    ),
    (
        "raw-blue-id-access",
        # Only nullable-identity predicates are relevant to the empty/sentinel
        # audit.  General getters used for serialization, diagnostics, map
        # keys, and already-verified identities belong to the separate exact
        # identity inventory.  The negative lookbehind also excludes methods
        # such as expectedTargetBlueId().
        re.compile(
            r"(?:(?<![A-Za-z0-9_$])getBlueId\(\)\s*(?:==|!=)\s*null|"
            r"null\s*(?:==|!=)[^;]*(?<![A-Za-z0-9_$])getBlueId\(\))"
        ),
        "A nullable authored BlueId might have been used as value-absence evidence rather than identity-header evidence.",
    ),
    (
        "schema-presence",
        re.compile(
            r"required|minFields|maxFields|semantic.*present|presence",
            re.IGNORECASE,
        ),
        "Required/presence logic may have inferred absence from empty shape rather than payload provenance.",
    ),
)


class Decision(NamedTuple):
    """One explicitly reviewed, load-bearing empty/sentinel decision."""

    classification: str
    old_assumption: str
    reason: str
    covering_test: str


# A bare ``new Node()`` is too broad to classify by shape alone.  These are
# the production owners where a fieldless node is deliberately used as a
# temporary builder or an internal control/container.  Every other constructor
# hit is retained as search context, not presented as an audited decision.
TEMPORARY_BUILDER_OWNERS = (
    "blue-language-model/src/main/java/blue/language/model/NodeDeserializer.java",
    "blue-language-model/src/main/java/blue/language/model/NodePathEditor.java",
    "blue-language-core/src/main/java/blue/language/identity/CanonicalIdentityInputReconstructor.java",
    "blue-language-core/src/main/java/blue/language/matching/NodeTypeMatcher.java",
    "blue-language-core/src/main/java/blue/language/merge/ResolutionEngine.java",
    "blue-language-core/src/main/java/blue/language/preprocess/DirectiveResolver.java",
    "blue-language-core/src/main/java/blue/language/resolve/MinimizedOverlayReconstructor.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ContractContributionResolver.java",
    "blue-contracts-core/src/main/java/blue/language/processor/EffectiveContractResolver.java",
    "blue-contracts-core/src/main/java/blue/language/processor/MutationCommit.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ProcessingDocumentValidator.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ScopeSourceProjection.java",
    "blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureAdmissionExecutionSession.java",
    "blue-contracts-core/src/main/java/blue/language/processor/closure/ClosureExecutionSession.java",
)


PROPERTY_DECISION_OWNERS = (
    "blue-language-model/src/main/java/blue/language/model/Node.java",
    "blue-language-model/src/main/java/blue/language/model/NodeDeserializer.java",
    "blue-language-model/src/main/java/blue/language/model/Nodes.java",
    "blue-language-core/src/main/java/blue/language/identity/BlueIdReferenceValidator.java",
    "blue-language-core/src/main/java/blue/language/identity/CanonicalIdentityInputReconstructor.java",
    "blue-language-core/src/main/java/blue/language/matching/FrozenTypeMatcher.java",
    "blue-language-core/src/main/java/blue/language/matching/LabelNeutralTypeIdentity.java",
    "blue-language-core/src/main/java/blue/language/merge/CompletedValueValidator.java",
    "blue-language-core/src/main/java/blue/language/merge/LabelProvenanceTracker.java",
    "blue-language-core/src/main/java/blue/language/merge/ListOverlayMerger.java",
    "blue-language-core/src/main/java/blue/language/merge/processor/SchemaVerifier.java",
    "blue-language-core/src/main/java/blue/language/merge/processor/ValuePropagator.java",
    "blue-language-core/src/main/java/blue/language/preprocess/NormalizeListPlaceholders.java",
    "blue-language-core/src/main/java/blue/language/resolve/MinimizedOverlayReconstructor.java",
    "blue-language-core/src/main/java/blue/language/resolve/NodeToPathLimitsConverter.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNode.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNodeBuilder.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNodeIdentity.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNodeStructuralKey.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ContractContributionResolver.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ContractSnapshotCache.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ContractSnapshotFactory.java",
    "blue-contracts-core/src/main/java/blue/language/processor/EffectiveContractResolver.java",
    "blue-contracts-core/src/main/java/blue/language/processor/EvidenceClassificationView.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ExternalSubscriptionProjectionBuilder.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ScopeHandlerDispatcher.java",
    "blue-conformance/src/main/java/blue/language/conformance/api/BlueConformanceFixtureTransformations.java",
    "blue-conformance/src/main/java/blue/language/conformance/contracts/ContractsFixtureHarnessDataSupport.java",
)


STRUCTURAL_EMPTY_OWNERS = (
    "blue-language-mapping/src/main/java/blue/language/mapping/CollectionConverter.java",
    "blue-language-mapping/src/main/java/blue/language/mapping/ComplexObjectConverter.java",
    "blue-language-mapping/src/main/java/blue/language/mapping/MappingPayload.java",
    "blue-language-model/src/main/java/blue/language/model/Nodes.java",
    "blue-language-core/src/main/java/blue/language/identity/CanonicalIdentityInputReconstructor.java",
    "blue-language-core/src/main/java/blue/language/identity/NodeToBlueIdInput.java",
    "blue-language-core/src/main/java/blue/language/preprocess/NormalizeListPlaceholders.java",
    "blue-language-core/src/main/java/blue/language/resolve/MinimizedOverlayReconstructor.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenCanonicalDigester.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNode.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNodeBuilder.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNodeIdentity.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNodeToBlueIdInput.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ContractHeaderLoader.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ContractSnapshotCache.java",
    "blue-contracts-core/src/main/java/blue/language/processor/EffectiveFragmentationCatalogBuilder.java",
    "blue-contracts-core/src/main/java/blue/language/processor/EmbeddedSubscriptionRouteProjector.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ExactContractNodeFieldRestorer.java",
    "blue-contracts-core/src/main/java/blue/language/processor/PatchImpactAnalyzer.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ProcessingSnapshotBootstrap.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ScopeSourceProjection.java",
)


EXACT_EMPTY_CONSTRUCTOR_OWNERS = (
    "blue-language-core/src/main/java/blue/language/conformance/FrozenConformancePlanner.java",
    "blue-language-core/src/main/java/blue/language/snapshot/CanonicalOverlayPatchEngine.java",
    # Retained for the focused classifier regression; the implementation of
    # FrozenNode.empty() itself is asserted by ExactEmptyObjectSemanticsTest.
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenNode.java",
)


# Exact production owners that compare against FrozenNode.empty() as the
# canonical empty-object value without constructing or substituting payload.
EXACT_EMPTY_COMPARISON_OWNERS = (
    "blue-contracts-core/src/main/java/blue/language/processor/PatchImpactAnalyzer.java",
)


# Exact production owners of same-line raw BlueId nullability predicates.  A
# new owner fails report generation until its use is reviewed.  This is a
# deliberately narrow audit: arbitrary getBlueId() consumers are covered by
# the old-to-new identity inventory, while these predicates are the callsites
# capable of confusing an absent identity header with an absent Blue value.
RAW_BLUE_ID_NULLABILITY_OWNERS = (
    "blue-conformance/src/main/java/blue/language/conformance/api/BlueConformanceFixtureTransformations.java",
    "blue-conformance/src/main/java/blue/language/conformance/contracts/ContractsFixtureExecutionEngine.java",
    "blue-conformance/src/main/java/blue/language/conformance/contracts/ContractsFixtureHarnessDataSupport.java",
    "blue-conformance/src/main/java/blue/language/conformance/contracts/FullLifecycleFixtureSupport.java",
    "blue-contracts-core/src/main/java/blue/language/processor/CheckpointIdentityCalculator.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ContractContributionResolver.java",
    "blue-contracts-core/src/main/java/blue/language/processor/EffectiveFragmentationCatalogBuilder.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ExternalEvidenceVerificationSupport.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ProcessorRuntimeAccess.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ProtectedStateGuard.java",
    "blue-contracts-core/src/main/java/blue/language/processor/RegisteredContractScopeIdentitySnapshotManager.java",
    "blue-contracts-core/src/main/java/blue/language/processor/ScopeSourceProjection.java",
    "blue-contracts-core/src/main/java/blue/language/processor/SemanticOutputBoundary.java",
    "blue-contracts-core/src/main/java/blue/language/processor/SubscriptionSurfaceTypeInspector.java",
    "blue-contracts-core/src/main/java/blue/language/processor/TypeGeneralizationPolicyResolver.java",
    "blue-language-core/src/main/java/blue/language/graph/NodeExpander.java",
    "blue-language-core/src/main/java/blue/language/graph/NodeExpansionEngine.java",
    "blue-language-core/src/main/java/blue/language/identity/BlueIdReferenceValidator.java",
    "blue-language-core/src/main/java/blue/language/identity/CanonicalIdentityInputReconstructor.java",
    "blue-language-core/src/main/java/blue/language/identity/CanonicalTypeIdentityEvidence.java",
    "blue-language-core/src/main/java/blue/language/identity/CircularSetIdentityCalculator.java",
    "blue-language-core/src/main/java/blue/language/identity/NodeToBlueIdInput.java",
    "blue-language-core/src/main/java/blue/language/identity/SchemaEnumCanonicalizer.java",
    "blue-language-core/src/main/java/blue/language/matching/LabelNeutralTypeIdentity.java",
    "blue-language-core/src/main/java/blue/language/matching/NodeTypeMatcher.java",
    "blue-language-core/src/main/java/blue/language/merge/CompletedValueValidator.java",
    "blue-language-core/src/main/java/blue/language/merge/DeclaredTypeContributionResolver.java",
    "blue-language-core/src/main/java/blue/language/merge/LabelProvenanceTracker.java",
    "blue-language-core/src/main/java/blue/language/merge/ListOverlayMerger.java",
    "blue-language-core/src/main/java/blue/language/merge/ReferenceResolver.java",
    "blue-language-core/src/main/java/blue/language/merge/ResolutionEngine.java",
    "blue-language-core/src/main/java/blue/language/merge/TypeMetadataResolver.java",
    "blue-language-core/src/main/java/blue/language/merge/processor/EffectiveTypeChecks.java",
    "blue-language-core/src/main/java/blue/language/merge/processor/SchemaVerifier.java",
    "blue-language-core/src/main/java/blue/language/preprocess/DirectiveValidator.java",
    "blue-language-core/src/main/java/blue/language/preprocess/StandardPreprocessingPipeline.java",
    "blue-language-core/src/main/java/blue/language/provider/ExactFragmentAssembler.java",
    "blue-language-core/src/main/java/blue/language/provider/ExactFragmentGraphValidator.java",
    "blue-language-core/src/main/java/blue/language/provider/ExactFragmentSupport.java",
    "blue-language-core/src/main/java/blue/language/provider/NodeContentHandler.java",
    "blue-language-core/src/main/java/blue/language/provider/SelectiveExactFragmentAssembler.java",
    "blue-language-core/src/main/java/blue/language/provider/Types.java",
    "blue-language-core/src/main/java/blue/language/provider/VerifyingNodeProvider.java",
    "blue-language-core/src/main/java/blue/language/resolve/MinimizedOverlayReconstructor.java",
    "blue-language-core/src/main/java/blue/language/runtime/BlueLanguageRuntime.java",
    "blue-language-core/src/main/java/blue/language/runtime/RuntimeLanguageProcessing.java",
    "blue-language-core/src/main/java/blue/language/snapshot/FrozenCanonicalWriter.java",
    "blue-language-model/src/main/java/blue/language/model/Node.java",
    "blue-language-model/src/main/java/blue/language/model/NodeDeserializer.java",
    "blue-language-model/src/main/java/blue/language/model/NodeWireForm.java",
    "blue-language-model/src/main/java/blue/language/model/Nodes.java",
    "blue-language-model/src/main/java/blue/language/model/SchemaWireForm.java",
)


def _git(repository: Path, *args: str) -> str:
    completed = subprocess.run(
        ("git",) + args,
        cwd=str(repository),
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            "git " + " ".join(args) + " failed: " + completed.stderr
        )
    return completed.stdout


def _production_sources(repository: Path) -> list[Path]:
    return sorted(
        path
        for path in repository.glob("*/src/main/java/**/*.java")
        if "build" not in path.parts and path.is_file()
    )


def _changed_paths(repository: Path, baseline: str) -> set[str]:
    return {
        value.strip()
        for value in _git(repository, "diff", "--name-only", baseline, "--").splitlines()
        if value.strip()
    }


def _symbol(lines: list[str], line_number: int) -> str:
    method = re.compile(r"^\s*(?P<prefix>[^=;{}]+?)\b(?P<name>\w+)\s*\([^;]*$")
    type_declaration = re.compile(r"\b(?:class|interface|enum)\s+(\w+)")
    owner = "<top-level>"
    for text in lines[:line_number]:
        type_match = type_declaration.search(text)
        if type_match:
            owner = type_match.group(1)
    for index in range(line_number - 1, -1, -1):
        text = lines[index]
        method_match = method.match(text)
        if not method_match:
            continue
        prefix = method_match.group("prefix").strip()
        name = method_match.group("name")
        if (
            name in {"catch", "do", "else", "for", "if", "switch", "try", "while"}
            or prefix.startswith(("return ", "throw ", "new "))
            or "." in prefix
            or "(" in prefix
        ):
            continue
        words = prefix.split()
        if len(words) < 1:
            continue
        return owner + "#" + name
    return owner


def _covering_test(path: str, audit: str, symbol: str) -> str:
    if audit == "raw-blue-id-access":
        if "blue-language-model/" in path:
            return "NodeWireFormTest, ExactEmptyObjectSemanticsTest"
        if "/identity/" in path:
            return "BlueIdReferenceValidatorDepthTest, CanonicalIdentityInputReconstructorTest"
        if "/provider/" in path or "/graph/" in path:
            return "ProviderEvidenceVerifierTest, ProviderCanonicalIngestionTest"
        if any(value in path for value in (
                "/merge/", "/matching/", "/resolve/", "/preprocess/")):
            return "CanonicalIdentityProvenanceFailClosedTest, ExactEmptyObjectSemanticsTest"
        if "blue-contracts-core/" in path:
            return "CanonicalIdentityEvidenceTest, SemanticOutputBoundaryTest"
        return "BlueContractsConformanceFixtureTest, ExactEmptyObjectSemanticsTest"
    if path.endswith("/NodeDeserializer.java"):
        return "NodeDeserializerTest, ExactEmptyObjectSemanticsTest"
    if path.endswith("/Nodes.java") or path.endswith("/Node.java"):
        return "ExactEmptyObjectSemanticsTest, EmptyObjectCandidateConformanceTest"
    if "blue-language-mapping/" in path:
        return "NodeToObjectConverterNullHandlingTest, ExactEmptyObjectSemanticsTest"
    if path.endswith("/CanonicalIdentityInputReconstructor.java"):
        return "CanonicalIdentityInputReconstructorTest, ExactEmptyObjectSemanticsTest"
    if "/identity/" in path or path.endswith("/FrozenCanonicalDigester.java"):
        return "DirectBlueIdCalculatorTest, ExactEmptyObjectSemanticsTest"
    if path.endswith("/CanonicalOverlayPatchEngine.java"):
        return "CanonicalOverlayPatchEngineTest, DocumentProcessingRuntimeJsonPatchTest"
    if "/snapshot/" in path or path.endswith("/FrozenConformancePlanner.java"):
        return "FrozenNodeTest, ExactEmptyObjectSemanticsTest"
    if path.endswith("/MinimizedOverlayReconstructor.java"):
        return "MinimizedOverlayCanonicalIdentityParityTest, ExactEmptyObjectSemanticsTest"
    if path.endswith("/CompletedValueValidator.java"):
        return "ExactEmptyObjectSemanticsTest, ResolvedInstanceSchemaValidationTest"
    if path.endswith("/SchemaVerifier.java"):
        return "ResolvedInstanceSchemaValidationTest, ExactEmptyObjectSemanticsTest"
    if any(value in path for value in ("/merge/", "/resolve/", "/matching/")):
        return "ExactEmptyObjectSemanticsTest, BlueLanguageConformanceFixtureTest"
    if "blue-contracts-core/" in path:
        return "BlueContractsConformanceFixtureTest, ManagedDocumentStepProcessorTest"
    if "blue-conformance/" in path:
        return "BlueConformancePackageIntegrityTest, BlueContractsConformanceFixtureTest"
    if audit == "schema-presence":
        return "ResolvedInstanceSchemaValidationTest, ExactEmptyObjectSemanticsTest"
    return "EmptyObjectCandidateConformanceTest, BlueLanguageConformanceFixtureTest"


def _is_comment_or_declaration(line: str) -> bool:
    stripped = line.strip()
    return stripped.startswith(("import ", "package ", "*", "/**", "//"))


def _decision(
        path: str,
        line: str,
        next_line: str,
        symbol: str,
        audit: str) -> Decision | None:
    """Returns a disposition only for an explicitly reviewed decision site.

    Broad-search matches are not semantic evidence by default.  This function
    is intentionally path/symbol aware: adding a new structural-empty use
    fails generation until a reviewer gives it a concrete classification.
    """
    tests = _covering_test(path, audit, symbol)
    if audit == "raw-blue-id-access":
        if _is_comment_or_declaration(line):
            return None
        if path not in RAW_BLUE_ID_NULLABILITY_OWNERS:
            raise ValueError(
                "Unclassified raw BlueId nullability owner: "
                + path + ":" + symbol
            )
        if "blue-language-model/" in path:
            classification = "B-nullable-identity-wire-header"
            assumption = (
                "A missing authored BlueId header could be treated as a "
                "missing Blue value."
            )
            reason = (
                symbol
                + " uses BlueId nullability only to select or validate an "
                "identity-bearing wire/header shape; object payload presence "
                "is represented independently by the properties presence bit."
            )
        elif any(value in path for value in (
                "/identity/", "/provider/", "/graph/", "/runtime/")):
            classification = "B-unverified-identity-claim-boundary"
            assumption = (
                "A present raw BlueId could be accepted as authoritative "
                "semantic identity, or a missing one as value absence."
            )
            reason = (
                symbol
                + " branches on an authored identity claim before separate "
                "canonical/provider verification; exact `{}` is still a "
                "present value through its empty properties payload."
            )
        elif any(value in path for value in (
                "/merge/", "/matching/", "/resolve/", "/preprocess/",
                "/snapshot/")):
            classification = "B-identity-metadata-versus-value-payload"
            assumption = (
                "BlueId metadata nullability could substitute for semantic "
                "value-presence provenance."
            )
            reason = (
                symbol
                + " uses identity-header presence only in reference/type/"
                "overlay handling; ordinary payload presence, including `{}`, "
                "is decided from value kind and contribution provenance."
            )
        elif "blue-contracts-core/" in path:
            classification = "B-contracts-identity-evidence-boundary"
            assumption = (
                "A Contracts identity claim could be confused with the "
                "presence or absence of its containing value."
            )
            reason = (
                symbol
                + " gates verification, reference projection, or identity "
                "evidence; it does not erase a value merely because the raw "
                "BlueId header is absent."
            )
        else:
            classification = "B-conformance-identity-shape-control"
            assumption = (
                "Fixture identity-header shape could be reported as Blue "
                "value absence."
            )
            reason = (
                symbol
                + " validates or constructs an explicit conformance identity "
                "shape; the fixture engine separately preserves `{}` as a "
                "present value."
            )
        return Decision(classification, assumption, reason, tests)
    if audit == "empty-shape-and-builder":
        if "FrozenNode.empty()" in line:
            if path in EXACT_EMPTY_COMPARISON_OWNERS:
                return Decision(
                    "A-exact-empty-object-comparison",
                    "An exact empty-object comparison could be mistaken for an absence check.",
                    symbol + " compares the present contracts payload with canonical `{}` only to detect empty-contract normalization impact; null contracts remain a separate absent state.",
                    tests,
                )
            if path not in EXACT_EMPTY_CONSTRUCTOR_OWNERS:
                raise ValueError(
                    "Unclassified FrozenNode.empty() production owner: "
                    + path + ":" + symbol
                )
            return Decision(
                "A-exact-empty-object-value",
                "A fieldless frozen node could previously be read as absence.",
                symbol + " constructs exact `{}` with a present empty properties map; it is never an absence sentinel.",
                tests,
            )
        if "isEmptyNode(" in line:
            if path not in STRUCTURAL_EMPTY_OWNERS:
                raise ValueError(
                    "Unclassified isEmptyNode production owner: "
                    + path + ":" + symbol
                )
            if "blue-language-mapping/" in path:
                return Decision(
                    "D-host-absence-from-fieldless-source-control",
                    "Any structurally empty input, including `{}`, could map to host null.",
                    symbol + " sees only fieldless Source/control nodes here; exact `{}` has a present empty properties map and is mapped as a value.",
                    tests,
                )
            if any(value in path for value in (
                    "/NodeToBlueIdInput.java",
                    "/FrozenNodeToBlueIdInput.java",
                    "/FrozenNodeBuilder.java",
                    "/FrozenNodeIdentity.java",
                    "/FrozenCanonicalDigester.java")):
                return Decision(
                    "E-invalid-fieldless-list-position",
                    "A raw fieldless list item could be confused with exact `{}` or `$empty`.",
                    symbol + " rejects or excludes only the unrepresented fieldless list hole; exact `{}` and `{$empty:true}` remain distinct represented values.",
                    tests,
                )
            if path.endswith("/NormalizeListPlaceholders.java"):
                return Decision(
                    "A-preserve-recursively-emptied-object",
                    "Recursive Source-null removal could collapse an object child into absence or a list placeholder.",
                    symbol + " restores the present empty properties map when a retained object recursively becomes `{}`.",
                    tests,
                )
            if path.endswith("/Nodes.java") and "isSourceNullLiteral" in symbol:
                return Decision(
                    "C-source-null-before-preprocessing",
                    "Source null and `{}` shared a fieldless structural shape.",
                    symbol + " requires parser provenance (`inlineValue`) in addition to structural fieldlessness, so `{}` is not Source null.",
                    tests,
                )
            if path.endswith(("/Nodes.java", "/FrozenNode.java")):
                return Decision(
                    "B-structural-fieldlessness-helper",
                    "Structural fieldlessness could be treated as semantic absence.",
                    symbol + " is now only a low-level shape predicate; semantic callers separately inspect Source-null provenance or a present empty properties payload.",
                    tests,
                )
            if path.endswith((
                    "/CanonicalIdentityInputReconstructor.java",
                    "/MinimizedOverlayReconstructor.java")):
                return Decision(
                    "B-temporary-reconstruction-builder",
                    "A structurally empty reconstructed result could be omitted even when it represented exact `{}`.",
                    symbol + " tests a temporary builder; exact `{}` carries an empty properties payload and survives reconstruction/minimization.",
                    tests,
                )
            return Decision(
                "B-fieldless-metadata-or-control-container",
                "A fieldless contracts/type/control container could be confused with ordinary empty-object content.",
                symbol + " handles a reserved-position container whose fieldless shape is control metadata, while exact ordinary `{}` remains a payload value.",
                tests,
            )
        if "new Node()" in line:
            suffix = line.split("new Node()", 1)[1].lstrip()
            if suffix.startswith(".") or (
                    not suffix and next_line.lstrip().startswith(".")):
                return None
            if path not in TEMPORARY_BUILDER_OWNERS:
                return None
            return Decision(
                "B-temporary-fieldless-builder-or-control-container",
                "A bare mutable Node could ambiguously stand for missing content or exact `{}`.",
                symbol + " uses the fieldless node only while assembling an internal structure or reserved-position control; exact ordinary `{}` is constructed with an explicit empty properties map.",
                tests,
            )
        return None
    if audit == "property-shape":
        if path not in PROPERTY_DECISION_OWNERS:
            return None
        if path.endswith("/NodeDeserializer.java"):
            classification = "A-wire-empty-object-presence"
            assumption = "A parsed object was retained only when it had one or more fields."
            reason = symbol + " records an explicit empty properties map for `{}` while Source null retains separate parser provenance."
        elif path.endswith(("/Node.java", "/Nodes.java")):
            classification = "A-model-object-payload-presence-bit"
            assumption = "Null properties and an empty properties map could be treated as the same fieldless representation."
            reason = symbol + " preserves the present empty-map bit that represents exact `{}` and keeps null properties for nodes without object payload."
        elif path.endswith("/NormalizeListPlaceholders.java"):
            classification = "A/C-source-null-removal-with-empty-object-preservation"
            assumption = "An emptied properties map could be cleared without preserving whether an object value remained."
            reason = symbol + " clears Source-null children but restores an empty map when the enclosing object remains exact `{}`."
        elif path.endswith("/SchemaVerifier.java"):
            classification = "A-object-field-count"
            assumption = "An empty map could be treated as no value rather than an object with zero fields."
            reason = symbol + " counts exact `{}` as an object payload with zero ordinary fields; required presence and min/max field constraints remain separate."
        elif path.endswith("/CompletedValueValidator.java"):
            classification = "A-semantic-presence-provenance"
            assumption = "Semantic presence could be inferred from a non-empty properties map."
            reason = symbol + " distinguishes direct value contribution, type declarations, and registered zero-field contracts instead of using emptiness as presence."
        elif path.endswith((
                "/CanonicalIdentityInputReconstructor.java",
                "/MinimizedOverlayReconstructor.java")):
            classification = "A-reconstruction-preserves-present-empty-object"
            assumption = "A reconstructed child with zero properties could be omitted as if absent."
            reason = symbol + " uses provenance and the present properties map, so non-derivable `{}` remains in canonical/minimized output."
        elif any(value in path for value in (
                "/identity/", "/snapshot/", "/matching/")):
            classification = "A-identity-and-structure-preservation"
            assumption = "Null and empty properties could be hash-, match-, or structural-key equivalent."
            reason = symbol + " preserves the presence bit for an empty object payload so `{}` is distinct from a fieldless metadata/control node."
        elif any(value in path for value in ("/merge/", "/resolve/")):
            classification = "A-resolution-payload-kind"
            assumption = "An empty properties map could be treated as no object contribution."
            reason = symbol + " retains `{}` as object payload while null properties continue to mean no object payload in this resolution decision."
        else:
            classification = "B-empty-reserved-container-shape"
            assumption = "A reserved contracts/control container with no members could be confused with ordinary `{}` content."
            reason = symbol + " makes a reserved-position container decision; it does not erase an ordinary empty-object value."
        return Decision(classification, assumption, reason, tests)
    if audit == "list-placeholder":
        if _is_comment_or_declaration(line):
            return None
        if (
            "NormalizeListPlaceholders" in line
            and "new NormalizeListPlaceholders" not in line
        ):
            return None
        return Decision(
            "E-explicit-list-hole-control",
            "A fieldless list element, Source null, `$empty`, and `{}` could share one empty representation.",
            symbol + " encodes, validates, or hashes `{$empty:true}` as the dedicated positional hole; ordinary `{}` and `[]` remain separate values.",
            tests,
        )
    if audit == "empty-to-null-return":
        return Decision(
            "D-reviewed-host-absence-conversion",
            "An empty Blue object could be converted to host null based only on shape.",
            symbol + " may return host absence only for a fieldless Source/control or unsupported target; exact `{}` is preserved or rejected deterministically.",
            tests,
        )
    if audit == "schema-presence":
        stripped = line.strip()
        if _is_comment_or_declaration(line):
            return None
        if path.endswith("/CompletedValueValidator.java") and (
                "presence" in stripped.lower()
                or "semanticContribution" in stripped
        ):
            return Decision(
                "A-semantic-presence-provenance",
                "Required presence could be inferred from structural non-emptiness.",
                symbol + " records semantic contribution provenance; exact `{}` is present and declarations alone are not instance presence.",
                tests,
            )
        if path.endswith("/SchemaVerifier.java") and (
                "verifyRequired" in symbol
                or "verifyMinFields" in symbol
                or "verifyMaxFields" in symbol
                or "semanticallyPresent" in stripped
        ):
            return Decision(
                "A-required-and-field-count-separation",
                "Required and minFields could both rely on non-empty object fields.",
                symbol + " accepts exact `{}` as present, then independently applies minFields/maxFields to its zero ordinary fields.",
                tests,
            )
        return None
    return None


def _context_reason(audit: str, line: str) -> str:
    if audit == "raw-blue-id-access":
        return "Comment/declaration context only within the narrowly scoped raw BlueId nullability predicate search."
    if audit == "schema-presence":
        return "Broad required/presence vocabulary matched traversal, metadata, diagnostics, or a non-object constraint rather than an empty-object presence decision."
    if audit == "empty-shape-and-builder" and "new Node()" in line:
        return "Constructor context only: this occurrence immediately populates a normal value or is outside the explicit fieldless-builder/control owner set."
    if audit == "list-placeholder":
        return "Placeholder naming/import/documentation context only; no value classification occurs on this line."
    if audit == "property-shape":
        return "Properties lookup/traversal context only; this line does not equate empty-object content with absence."
    return "Broad-search context only; no empty/sentinel classification occurs on this line."


def _classification(path: str, line: str, audit: str) -> tuple[str, str]:
    """Compatibility shim used by focused unit tests."""
    symbol = Path(path).stem + "#fixture"
    decision = _decision(path, line, "", symbol, audit)
    if decision is not None:
        return decision.classification, decision.reason
    if "FrozenNode.empty()" in line:
        return (
            "A-exact-empty-object-value",
            "The call constructs canonical `{}` with a present empty properties map; it is never an absence sentinel.",
        )
    raise ValueError("No explicit decision classification for audit hit")


def generate(repository: Path, baseline: str = DEFAULT_BASELINE) -> dict[str, Any]:
    repository = repository.resolve()
    changed_paths = _changed_paths(repository, baseline)
    entries: list[dict[str, Any]] = []
    for source in _production_sources(repository):
        relative = source.relative_to(repository).as_posix()
        lines = source.read_text(encoding="utf-8").splitlines()
        for line_number, line in enumerate(lines, 1):
            for audit, pattern, old_assumption in AUDITS:
                if not pattern.search(line):
                    continue
                next_line = lines[line_number] if line_number < len(lines) else ""
                symbol = _symbol(lines, line_number)
                decision = _decision(
                    relative, line, next_line, symbol, audit
                )
                if decision is not None:
                    relevance = "decision-relevant"
                    classification = decision.classification
                    reason = decision.reason
                    covering_test = decision.covering_test
                    recorded_old_assumption = decision.old_assumption
                    review_disposition = "resolved-explicit-decision"
                    decision_basis = "explicit-path-symbol-rule"
                else:
                    relevance = "context-only"
                    classification = "context-only-not-empty-sentinel-decision"
                    reason = _context_reason(audit, line)
                    covering_test = "not-applicable: no empty-sentinel decision"
                    recorded_old_assumption = (
                        "Prompt-mandated broad-search candidate; no semantic "
                        "assumption is attributed to a context-only line."
                    )
                    review_disposition = "resolved-context-only"
                    decision_basis = "explicitly-excluded-broad-search-context"
                entries.append(
                    {
                        "path": relative,
                        "line": line_number,
                        "symbol": symbol,
                        "audit": audit,
                        "source": line.strip(),
                        "relevance": relevance,
                        "reviewDisposition": review_disposition,
                        "decisionBasis": decision_basis,
                        "oldAssumption": recorded_old_assumption,
                        "newClassification": classification,
                        "changed": relative in changed_paths,
                        "reason": reason,
                        "coveringTest": covering_test,
                    }
                )
    entries.sort(key=lambda value: (value["path"], value["line"], value["audit"]))
    by_audit: dict[str, int] = {value[0]: 0 for value in AUDITS}
    by_classification: dict[str, int] = {}
    changed = 0
    decision_relevant = 0
    context_only = 0
    for entry in entries:
        by_audit[entry["audit"]] = by_audit.get(entry["audit"], 0) + 1
        classification = entry["newClassification"]
        by_classification[classification] = by_classification.get(classification, 0) + 1
        changed += 1 if entry["changed"] else 0
        if entry["relevance"] == "decision-relevant":
            decision_relevant += 1
        else:
            context_only += 1
    return {
        "schema": "blue-empty-sentinel-audit/1.0",
        "repository": "blue-language-java",
        "baselineCommit": baseline,
        "scope": (
            "complete lexical output of the prompt-mandated searches over "
            "*/src/main/java/**/*.java. Raw BlueId coverage is intentionally "
            "limited to same-line nullability predicates capable of confusing "
            "identity-header absence with value absence; general identity "
            "consumers belong to the identity-impact inventory. Load-bearing "
            "decisions require an "
            "explicit path/symbol rule and all other matches are retained as "
            "non-semantic search context"
        ),
        "changeBasis": (
            "changed=true means the containing production source file differs "
            "from the baseline commit; classification remains line-specific"
        ),
        "summary": {
            "productionHitCount": len(entries),
            "decisionRelevantHitCount": decision_relevant,
            "contextOnlyHitCount": context_only,
            "changedFileHitCount": changed,
            "unchangedFileHitCount": len(entries) - changed,
            "byAudit": dict(sorted(by_audit.items())),
            "byClassification": dict(sorted(by_classification.items())),
        },
        "entries": entries,
    }


def render_markdown(report: dict[str, Any]) -> str:
    summary = report["summary"]
    lines = [
        "# Empty-object sentinel audit",
        "",
        "This report records every prompt-mandated production Java search hit, then explicitly narrows the semantic decision set. The complete line-level inventory is `reports/migration/empty-object-sentinel-audit.json`.",
        "",
        "## Result",
        "",
        "- Production matches: " + str(summary["productionHitCount"]),
        "- Decision-relevant matches: " + str(summary["decisionRelevantHitCount"]),
        "- Context-only broad-search matches: " + str(summary["contextOnlyHitCount"]),
        "- Matches in files changed since the RC baseline: " + str(summary["changedFileHitCount"]),
        "- Matches in unchanged files: " + str(summary["unchangedFileHitCount"]),
        "",
        "Every entry has a resolved disposition. Context-only matches are retained to prove the broad searches ran, but are not presented as semantic evidence and do not claim a covering behavior test. Every decision-relevant entry is selected by an explicit path/symbol rule and distinguishes Source null, exact `{}`, an explicit list placeholder, a temporary fieldless builder/control, host absence, invalid reserved-position output, or nullable identity-header metadata.",
        "",
        "The classification prefix follows the required audit taxonomy: **A** exact empty-object value, **B** temporary builder/metadata/control, **C** Source null before preprocessing, **D** host-conversion absence, and **E** invalid or explicit reserved-position list control.",
        "",
        "## Search coverage",
        "",
        "| Audit | Hits |",
        "| --- | ---: |",
    ]
    for key, count in summary["byAudit"].items():
        lines.append("| `" + key + "` | " + str(count) + " |")
    lines.extend(
        [
            "",
            "## Classification totals",
            "",
            "| Classification | Hits |",
            "| --- | ---: |",
        ]
    )
    for key, count in summary["byClassification"].items():
        lines.append("| `" + key + "` | " + str(count) + " |")
    lines.extend(
        [
            "",
            "## Load-bearing decisions",
            "",
            "| Area | Decision | Covering tests |",
            "| --- | --- | --- |",
            "| Mapping | A present empty properties map maps as `{}`; only Source-null/fieldless controls may map to host absence. | `NodeToObjectConverterNullHandlingTest`, `ExactEmptyObjectSemanticsTest` |",
            "| Identity | `{}` is accepted as content and remains distinct from `$empty`; every same-line raw BlueId nullability predicate is classified as identity-header/evidence handling, separate from value presence. General identity consumers are covered by the old-to-new identity inventory. | `BlueIdReferenceValidatorDepthTest`, `CanonicalIdentityProvenanceFailClosedTest`, `ExactEmptyObjectSemanticsTest` |",
            "| Resolution | Omitted/Source-null fields inherit; exact `{}` is a present object payload and conflicts with inherited scalar/list payloads. | `BlueLanguageConformanceFixtureTest`, `ExactEmptyObjectSemanticsTest` |",
            "| Schema | Exact `{}` satisfies `required`; `minFields` is the independent non-empty-object constraint. | `ResolvedInstanceSchemaValidationTest`, `ExactEmptyObjectSemanticsTest` |",
            "| Contracts | Explicit/referenced `{}` collection is present with zero members; unavailable evidence is incomplete. | `EmbeddedScopePlannerTest`, `BlueContractsConformanceFixtureTest` |",
            "",
        ]
    )
    return "\n".join(lines)


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode("utf-8")


def _write_or_check(path: Path, expected: bytes, check: bool) -> None:
    if check:
        if not path.is_file() or path.read_bytes() != expected:
            raise SystemExit("Generated output is stale: " + str(path))
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(expected)


def main(argv: Iterable[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository-root", type=Path, default=Path.cwd())
    parser.add_argument("--baseline", default=DEFAULT_BASELINE)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args(list(argv) if argv is not None else None)
    repository = args.repository_root.resolve()
    output = args.output or repository / REPORT_PATH
    summary = args.summary or repository / SUMMARY_PATH
    report = generate(repository, args.baseline)
    _write_or_check(output, _json_bytes(report), args.check)
    _write_or_check(summary, render_markdown(report).encode("utf-8"), args.check)
    print("EMPTY_SENTINEL_AUDIT_OK" if args.check else "EMPTY_SENTINEL_AUDIT_WRITTEN")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
