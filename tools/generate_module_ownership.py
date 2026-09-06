#!/usr/bin/env python3
"""Generate deterministic Phase 4 module ownership and API relocation ledgers."""

import argparse
import hashlib
import json
import pathlib
import re


MODULE_MODEL = ":blue-language-model"
MODULE_CORE = ":blue-language-core"
MODULE_CONTRACTS = ":blue-contracts-core"
MODULE_MAPPING = ":blue-language-mapping"
MODULE_IPFS = ":blue-language-ipfs"
MODULE_CONFORMANCE = ":blue-conformance"
MODULE_AGGREGATE = ":blue-language-java"
MODULE_EXAMPLES = ":examples"
MODULE_BUILD_LOGIC = ":build-logic"

PUBLISHED_MODULES = (
    MODULE_MODEL,
    MODULE_CORE,
    MODULE_CONTRACTS,
    MODULE_MAPPING,
    MODULE_IPFS,
    MODULE_CONFORMANCE,
    MODULE_AGGREGATE,
)

MODULE_DIRECTORIES = {
    MODULE_MODEL: "blue-language-model",
    MODULE_CORE: "blue-language-core",
    MODULE_CONTRACTS: "blue-contracts-core",
    MODULE_MAPPING: "blue-language-mapping",
    MODULE_IPFS: "blue-language-ipfs",
    MODULE_CONFORMANCE: "blue-conformance",
    MODULE_AGGREGATE: "blue-language-java",
    MODULE_EXAMPLES: "examples",
    MODULE_BUILD_LOGIC: "build-logic",
}

PHYSICAL_EXTRACTION_COMMIT = "1e9985f6bd8fa0bc93811814c99d565935133d25"
PACKAGE_RELOCATION_COMMIT = "1f799962ef715c9488ae5bde77338993a114022a"

# These public names moved while package cycles were eliminated immediately
# before physical module extraction. Keep the aliases in the API evidence so
# regenerating from the final packages cannot misclassify established types as
# unrelated additions.
PACKAGE_RELOCATIONS = {
    "blue.language.provider.NodeProviderOutcome":
        "blue.language.api.NodeProviderOutcome",
    "blue.language.snapshot.BlueSnapshots":
        "blue.language.merge.BlueSnapshots",
    "blue.language.snapshot.ResolvedReferenceCache":
        "blue.language.merge.ResolvedReferenceCache",
    "blue.language.snapshot.ResolvedSnapshot":
        "blue.language.merge.ResolvedSnapshot",
    "blue.language.api.LanguageRuntimeAccess":
        "blue.language.runtime.LanguageRuntimeAccess",
    "blue.language.patching.BluePatch":
        "blue.language.snapshot.BluePatch",
    "blue.language.patching.BluePatchOperation":
        "blue.language.snapshot.BluePatchOperation",
    "blue.language.patching.ImmutableBluePatch":
        "blue.language.snapshot.ImmutableBluePatch",
}

DEPENDENCY_CONFIGURATIONS = {
    "annotationProcessor",
    "api",
    "classpath",
    "compileOnly",
    "implementation",
    "jmh",
    "jmhImplementation",
    "jmhRuntimeOnly",
    "runtimeOnly",
    "testAnnotationProcessor",
    "testCompileOnly",
    "testFixturesApi",
    "testFixturesImplementation",
    "testFixturesRuntimeOnly",
    "testImplementation",
    "testRuntimeOnly",
}

DEPENDENCY_PATTERN = re.compile(
    r"(?m)\b(" + "|".join(sorted(DEPENDENCY_CONFIGURATIONS)) + r")\s*"
    r"(?:\(\s*)?(?:platform\s*\(\s*)?"
    r"[\"']([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)"
    r"(?::([^\"']+))?[\"']"
)
PLUGIN_PATTERN = re.compile(
    r"(?m)^\s*id\s*(?:\(\s*)?[\"']([^\"']+)[\"']\s*\)?"
    r"\s+version\s+[\"']([^\"']+)[\"']"
)
TYPED_LITERAL_DEPENDENCY_PATTERN = re.compile(
    r"dependencies\.add\(\s*([^,]+),\s*"
    r"(?:dependencies\.platform\(\s*)?"
    r"[\"']([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)"
    r"(?::([^\"']+))?[\"']",
    re.MULTILINE,
)
TYPED_COORDINATE_CONSTANT_PATTERN = re.compile(
    r"(?m)^\s*private\s+static\s+final\s+String\s+"
    r"([A-Z0-9_]*COORDINATE)\s*=\s*"
    r"[\"']([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+)"
    r"(?::([^\"']+))?[\"']"
)

DEPENDENCY_POLICIES = {
    "com.fasterxml.jackson.core:jackson-databind": (
        MODULE_MODEL,
        "api",
        "2.15.2",
        "Defines the public Node and Schema Jackson wire boundary; other "
        "modules consume the same reviewed version.",
    ),
    "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml": (
        MODULE_CORE,
        "implementation",
        "2.15.2",
        "Implements strict YAML parsing in Language core and fixture decoding "
        "in the outer conformance module.",
    ),
    "io.github.erdtman:java-json-canonicalization": (
        MODULE_CORE,
        "implementation",
        "1.1",
        "Implements RFC 8785 canonical JSON hashing for Language identities.",
    ),
    "org.apache.httpcomponents:httpclient": (
        MODULE_IPFS,
        "implementation",
        "4.5.14",
        "Provides optional IPFS HTTP transport and is forbidden in core.",
    ),
    "org.reflections:reflections": (
        MODULE_MAPPING,
        "implementation",
        "0.10.2",
        "Supports optional legacy classpath discovery; explicit registration "
        "remains the deterministic default.",
    ),
    "org.yaml:snakeyaml": (
        MODULE_CONFORMANCE,
        "implementation",
        "2.0",
        "Reads bound fixture-package manifests in conformance tooling only.",
    ),
    "org.jreleaser:org.jreleaser.gradle.plugin": (
        MODULE_BUILD_LOGIC,
        "implementation",
        "1.24.0",
        "Makes the publishing plugin available to typed build conventions.",
    ),
    "me.champeau.jmh:me.champeau.jmh.gradle.plugin": (
        MODULE_BUILD_LOGIC,
        "implementation",
        "0.7.3",
        "Makes JMH source-set conventions available to benchmark modules.",
    ),
    "org.ow2.asm:asm": (
        MODULE_BUILD_LOGIC,
        "implementation",
        "9.9",
        "Inspects bytecode for deterministic module and public-API evidence.",
    ),
    "org.junit:junit-bom": (
        MODULE_BUILD_LOGIC,
        "testImplementation.platform",
        "5.10.2",
        "Pins the build-logic verification test platform.",
    ),
    "org.junit.jupiter:junit-jupiter": (
        MODULE_BUILD_LOGIC,
        "testImplementation",
        "5.10.2 (from org.junit:junit-bom)",
        "Provides build-logic unit tests without entering published artifacts.",
    ),
    "org.junit.platform:junit-platform-launcher": (
        MODULE_BUILD_LOGIC,
        "testRuntimeOnly",
        "1.10.2 (from org.junit:junit-bom)",
        "Launches build-logic tests without entering published artifacts.",
    ),
    "org.mockito:mockito-core": (
        MODULE_BUILD_LOGIC,
        "testImplementation",
        "3.12.4",
        "Supports root compatibility tests without entering published artifacts.",
    ),
}

PLUGIN_POLICIES = {
    "org.gradle.toolchains.foojay-resolver-convention": (
        MODULE_BUILD_LOGIC,
        "Resolves the declared Java toolchains for the build.",
    ),
    "org.jreleaser": (
        MODULE_BUILD_LOGIC,
        "Coordinates root publication through typed build logic.",
    ),
}

MODULE_RUNTIME_ALLOWLISTS = {
    MODULE_MODEL: ["com.fasterxml.jackson.core:jackson-databind"],
    MODULE_CORE: [
        "com.fasterxml.jackson.core:jackson-databind",
        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml",
        "io.github.erdtman:java-json-canonicalization",
    ],
    MODULE_CONTRACTS: [
        "com.fasterxml.jackson.core:jackson-databind",
        "io.github.erdtman:java-json-canonicalization",
    ],
    MODULE_MAPPING: [
        "com.fasterxml.jackson.core:jackson-databind",
        "org.reflections:reflections",
    ],
    MODULE_IPFS: [
        "com.fasterxml.jackson.core:jackson-databind",
        "org.apache.httpcomponents:httpclient",
    ],
    MODULE_CONFORMANCE: [
        "com.fasterxml.jackson.core:jackson-databind",
        "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml",
        "io.github.erdtman:java-json-canonicalization",
        "org.yaml:snakeyaml",
    ],
    MODULE_AGGREGATE: [],
}

INTERNAL_CONFORMANCE_TYPES = {
    "CanonicalGeneralizationPatch",
    "ClosedContractsFixtureValidator",
    "ContractsAssertionEvaluator",
    "ContractsConformanceProjection",
    "ContractsFixtureConstants",
    "ContractsFixtureHarness",
    "ContractsGasSchedule",
    "ContractsProjectionCatalog",
    "FixtureNonChannelContract",
    "FixturePackageContradictionException",
    "MockExternalChannel",
    "MockExternalChannelProcessor",
    "MockHandler",
    "MockHandlerProcessor",
    "MockTypeBlueIds",
    "ScriptedContractsRuntime",
}

# Deliberate supported surface for the destination modules. Public classes not
# listed here are classified for internalization instead of being kept merely
# because the monolith historically exposed their bytecode.
SUPPORTED_PUBLIC_TYPES_BY_PACKAGE = {
    "blue.language": {
        "Blue",
        "BlueCachePolicy",
        "BlueCacheStats",
        "BlueConformanceFailure",
        "BlueConformanceReport",
        "BlueConformanceSuiteRunner",
        "BlueContractsConformanceFailure",
        "BlueContractsConformanceReport",
        "BlueContractsConformanceSuiteRunner",
        "BlueContractsFixtureCategory",
        "BlueContractsFixtureResult",
        "BlueFixtureCategory",
        "BlueLanguageErrorCategory",
        "BlueLanguageErrorClassifier",
        "BlueLanguageRuntime",
        "BlueOperationLimits",
        "BlueOperationOutcome",
        "BlueOperationResult",
        "BlueReleaseConformanceReport",
        "BlueViewPath",
        "LanguageRuntimeAccess",
        "NodeProvider",
    },
    "blue.language.api": {
        "BlueCachePolicy",
        "BlueCacheStats",
        "BlueLanguage",
        "BlueLanguageErrorCategory",
        "BlueLanguageErrorClassifier",
        "BlueLanguageRuntime",
        "BlueOperationLimits",
        "BlueOperationOutcome",
        "BlueOperationResult",
        "BlueViewPath",
        "LanguageRuntimeAccess",
        "NodeProviderOutcome",
    },
    "blue.language.codec": {"BlueCodec", "BlueFormat"},
    "blue.language.conformance": {
        "ConformanceEngine",
        "ConformancePlan",
        "ConformanceResult",
        "ReleaseConformanceCli",
    },
    "blue.language.conformance.api": {
        "BlueConformanceFailure",
        "BlueConformanceReport",
        "BlueConformanceSuiteRunner",
        "BlueContractsConformanceFailure",
        "BlueContractsConformanceReport",
        "BlueContractsFixtureCategory",
        "BlueContractsFixtureResult",
        "BlueFixtureCategory",
        "BlueReleaseConformanceReport",
        "LanguageFixtureRuntime",
    },
    "blue.language.conformance.cli": {"ReleaseConformanceCli"},
    "blue.language.conformance.runner": {
        "BlueContractsConformanceSuiteRunner",
    },
    "blue.language.dictionary": {
        "DictionaryAwareExporter",
        "DictionaryRegistry",
        "ExportContext",
        "TypeDictionary",
    },
    "blue.language.graph": {"BlueGraph"},
    "blue.language.identity": {
        "BlueIdentity",
        "CanonicalJsonHasher",
        "CircularSetIdentityCalculator",
        "DirectBlueIdCalculator",
        "SourceDocumentBlueIdCalculator",
    },
    "blue.language.mapping": {
        "BlueMapper",
        "ObjectFactoryRegistry",
        "TypeCreator",
    },
    "blue.language.matching": {"BlueMatching", "MatchingRuntime"},
    "blue.language.merge": {
        "BlueSnapshots",
        "IncrementalMergingProcessorCapability",
        "IncrementalValueResolutionRequest",
        "MergingProcessor",
        "NodeResolver",
        "ResolutionProvenance",
        "ResolutionSnapshot",
        "ResolvedSnapshot",
        "SnapshotResolution",
        "VerifiedReferenceResolution",
    },
    "blue.language.model": {
        "BlueDescription",
        "BlueId",
        "BlueName",
        "InvalidNodeStructureException",
        "Node",
        "NodeIdentities",
        "NodeIdentityProvider",
        "NodeDeserializer",
        "NodeSerializer",
        "Schema",
        "TypeBlueId",
    },
    "blue.language.patching": {
        "BluePatch",
        "BluePatchOperation",
        "BluePatching",
        "ImmutableBluePatch",
    },
    "blue.language.preprocess": {
        "BluePreprocessing",
        "DirectiveResolver",
        "PreprocessingContext",
        "PreprocessingPlan",
        "Preprocessor",
        "TransformationProcessor",
        "TransformationProcessorProvider",
        "TransformationSnapshot",
    },
    "blue.language.provider": {
        "BasicNodeProvider",
        "BootstrapProvider",
        "CachingNodeProvider",
        "ClasspathBasedNodeProvider",
        "CyclicAwareNodeProvider",
        "CyclicSetProof",
        "CyclicSetProofResult",
        "DirectNodeManifest",
        "DirectoryBasedNodeProvider",
        "ExactNodeGraphFragments",
        "NodeContentHandler",
        "NodeProvider",
        "NodeProviderOutcome",
        "NodeProviderResult",
        "PotentialBlueIdNodeProvider",
        "PreloadedNodeProvider",
        "ProviderMode",
        "ProviderUnavailableException",
        "SequentialNodeProvider",
        "SourceContentVerificationRuntime",
        "SourceProviderEnvironment",
        "VerifiedNodeProvider",
        "VerifyingNodeProvider",
    },
    "blue.language.provider.ipfs": {
        "BlueIdToCid",
        "IPFSContentFetcher",
        "IPFSNodeProvider",
    },
    "blue.language.registry": {"BlueCoreTypeRegistry"},
    "blue.language.resolve": {
        "BlueResolution",
        "ReferenceCacheAdmissionPolicy",
    },
    "blue.language.snapshot": {
        "BluePatch",
        "BluePatchOperation",
        "BlueSnapshots",
        "CanonicalPatchResult",
        "FrozenNode",
        "ImmutableBluePatch",
        "InvalidCanonicalPatchException",
        "ResolvedSnapshot",
    },
    "blue.language.runtime": {
        "BlueLanguage",
        "BlueLanguageRuntime",
        "LanguageRuntimeAccess",
    },
    "blue.language.utils": {"TypeClassResolver"},
    "blue.language.processor": {
        "ChannelCheckpointContext",
        "ChannelEvaluation",
        "ChannelEvaluationContext",
        "ChannelLookupResult",
        "ChannelMemberSnapshot",
        "ChannelProcessor",
        "CheckpointDomain",
        "CompositeProcessingObserver",
        "ContractBundle",
        "ContractMatchingService",
        "ContractProcessor",
        "ContractProcessorRegistry",
        "ContractProcessorRegistryBuilder",
        "DirectSubscriptionSurfaceValidator",
        "DocumentProcessingResult",
        "DocumentProcessor",
        "EffectiveContractSnapshot",
        "EffectiveFragmentationCatalog",
        "ExecutableBodySourceDescriptor",
        "ExecutionEvidenceUnavailableException",
        "ExternalChannelDependencySnapshot",
        "ExternalChannelFunctionContext",
        "ExternalChannelMemberEvaluation",
        "ExternalChannelMemberSnapshot",
        "ExternalChannelSubscriptionFunctions",
        "ExternalDeliveryEvidenceVerifier",
        "ExternalDeliveryPlan",
        "ExternalDeliveryPlanDeriver",
        "ExternalDeliverySnapshot",
        "ExternalOrderKey",
        "ExternalSubscriptionOccurrenceKey",
        "FrozenJsonPatch",
        "GasChargeContext",
        "GasLimitExceededException",
        "GasMeter",
        "GasSchedule",
        "GasScheduleConstants",
        "GasTraceEntry",
        "HandlerMatchContext",
        "HandlerProcessor",
        "HandlerRegistrationContext",
        "InvalidExecutionEvidenceException",
        "IndexedDeliveryDiagnostic",
        "IndexedDeliveryEvaluator",
        "IndexedDeliveryPreparation",
        "JfrProcessingObserver",
        "ManagedDocumentResolutionOverlay",
        "ManagedProcessEmbeddedPath",
        "NoOpProcessingObserver",
        "NoncommittingExecutionException",
        "ObservationKind",
        "PatchSource",
        "PlatformCommitCompanion",
        "PlatformProcessingResult",
        "PortableLimitExceededException",
        "ProcessAttemptResult",
        "ProcessingDebugResult",
        "ProcessingMetricId",
        "ProcessingMetricManifest",
        "ProcessingMetricsSnapshot",
        "ProcessingObservation",
        "ProcessingObservationContext",
        "ProcessingObservationDimension",
        "ProcessingObserver",
        "ProcessingSnapshotManager",
        "ProcessingTraceRecord",
        "ProcessorDiagnostic",
        "ProcessorErrorCategory",
        "ProcessorExecutionContext",
        "ProcessorFailureException",
        "ProcessorFatalException",
        "ProcessorRuntimeAccess",
        "ProcessorStatus",
        "RecordingProcessingObserver",
        "RootExternalDeliveryEvidenceVerifier",
        "RuntimeGasExhaustion",
        "RuntimeWorkBudget",
        "RuntimeWorkSession",
        "ScopeRuntimeContext",
        "SelectedExecutableBody",
        "SemanticGasMeter",
        "SemanticOutputBoundary",
        "SubscriptionDelta",
        "SubscriptionSurfaceProjection",
        "SubscriptionSurfaceInvalidException",
        "SubscriptionSurfaceValidationContext",
        "SubscriptionSurfaceValidator",
        "VerifiedExecutionEvidence",
        "WorkingDocument",
        "UnclassifiedProcessingException",
    },
    "blue.language.processor.closure": {
        "AcceptedAttachmentView",
        "AcceptedInitializationInstallation",
        "AcceptedInitializationInstallationCodec",
        "ClosureImplementationEvidence",
        "ClosureResourceDemand",
        "ClosureResourceDemandCodec",
        "ExactNodeDemand",
        "FrozenNodeEvidenceCodec",
        "ManagedOccurrenceEvidenceDemand",
        "ManagedReactionContext",
        "ManagedReactionContextCodec",
        "ManagedReadPin",
        "ReusableComponentAuthority",
        "ReusableComponentAuthorityCodec",
        "RootChannelMetadata",
        "RootChannelMetadataCodec",
        "SameOriginAttachmentPolicy",
        "SameOriginGroupEvidence",
        "SameOriginOperationResult",
        "SameOriginProcessAttempt",
        "SameOriginRejectedChargeEvidence",
        "SourceFrontierView",
        "SourceFrontierViewCodec",
        "SourceExecutionBasis",
        "SourceInitialization",
        "SourceInitializationDemand",
        "SourceObservationGap",
        "SourceObservationProgram",
        "SourceObservationProgramCodec",
        "SourceOperationFailure",
        "SourceOperationFailureCodec",
    },
    "blue.language.processor.registry": {
        "RuntimeBlueIds",
        "RuntimeTypeKey",
    },
}

# Contracts model records are specification-level values and remain supported
# as a group; implementation and fixture packages are not treated this way.
SUPPORTED_PUBLIC_PACKAGE_PREFIXES = (
    "blue.language.processor.model",
    "blue.language.utils.limits",
)

PACKAGE_PATTERN = re.compile(
    r"(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;"
)
PUBLIC_TOP_LEVEL_PATTERN = re.compile(
    r"(?m)^public\s+"
    r"(?:(?:abstract|final|sealed|non-sealed|strictfp)\s+)*"
    r"(?:class|interface|enum|@interface)\s+"
    r"([A-Za-z_$][\w$]*)\b"
)


def digest_lines(values):
    digest = hashlib.sha256()
    for value in values:
        digest.update(value.encode("utf-8"))
        digest.update(b"\n")
    return "sha256:" + digest.hexdigest()


def module_definitions():
    return [
        module(MODULE_MODEL, True, []),
        module(MODULE_CORE, True, [MODULE_MODEL]),
        module(
            MODULE_CONTRACTS,
            True,
            [MODULE_MODEL, MODULE_CORE, MODULE_MAPPING],
        ),
        module(MODULE_MAPPING, True, [MODULE_MODEL, MODULE_CORE]),
        module(MODULE_IPFS, True, [MODULE_CORE]),
        module(
            MODULE_CONFORMANCE,
            True,
            [MODULE_MODEL, MODULE_CORE, MODULE_CONTRACTS, MODULE_MAPPING],
        ),
        module(
            MODULE_AGGREGATE,
            True,
            [
                MODULE_MODEL,
                MODULE_CORE,
                MODULE_CONTRACTS,
                MODULE_MAPPING,
                MODULE_IPFS,
            ],
        ),
        module(MODULE_EXAMPLES, False, [MODULE_AGGREGATE]),
        module(MODULE_BUILD_LOGIC, False, []),
    ]


def module(identifier, published, dependencies):
    return {
        "id": identifier,
        "directory": MODULE_DIRECTORIES[identifier],
        "published": published,
        "dependencies": dependencies,
    }


def package_of(relative_path):
    source = PROJECT_ROOT.joinpath(relative_path).read_text(encoding="utf-8")
    match = PACKAGE_PATTERN.search(source)
    if not match:
        raise ValueError("No package declaration: " + relative_path)
    return match.group(1)


def source_entries():
    entries = []
    for owner in PUBLISHED_MODULES:
        root = PROJECT_ROOT / MODULE_DIRECTORIES[owner] / "src/main/java"
        for path in sorted(root.rglob("*.java")):
            relative = path.relative_to(PROJECT_ROOT).as_posix()
            current_package = package_of(relative)
            entries.append(
                {
                    "currentPath": relative,
                    "currentPackage": current_package,
                    "targetModule": owner,
                    "targetPath": relative,
                    "targetPackage": current_package,
                }
            )
    entries.sort(key=lambda entry: entry["currentPath"])
    return entries


def resource_entries():
    entries = []
    for owner in PUBLISHED_MODULES:
        root = PROJECT_ROOT / MODULE_DIRECTORIES[owner] / "src/main/resources"
        if not root.is_dir():
            continue
        for path in sorted(value for value in root.rglob("*") if value.is_file()):
            relative = path.relative_to(PROJECT_ROOT).as_posix()
            entries.append(
                {
                    "currentPath": relative,
                    "targetModule": owner,
                    "targetPath": relative,
                }
            )
    entries.sort(key=lambda entry: entry["currentPath"])
    return entries


def ownership_manifest(sources, resources):
    source_paths = [entry["currentPath"] for entry in sources]
    resource_paths = [entry["currentPath"] for entry in resources]
    return {
        "schema": "blue-language-java-module-ownership/1.0",
        "status": "phase-04-physical-module-ownership",
        "physicalExtractionCommit": PHYSICAL_EXTRACTION_COMMIT,
        "modules": module_definitions(),
        "inventory": {
            "productionSourceCount": len(sources),
            "productionResourceCount": len(resources),
            "productionSourcePathIdentity": digest_lines(source_paths),
            "productionResourcePathIdentity": digest_lines(resource_paths),
        },
        "ownershipRule": (
            "Every production file is owned at its conventional module path; "
            "root source redirection is forbidden."
        ),
        "sources": sources,
        "resources": resources,
    }


def source_by_top_level_type(sources):
    result = {}
    for source in sources:
        name = pathlib.PurePosixPath(source["currentPath"]).stem
        type_name = source["currentPackage"] + "." + name
        if type_name in result:
            raise ValueError("Duplicate top-level production type: " + type_name)
        result[type_name] = source
    return result


def source_for_type(type_name, sources_by_type):
    outer_type = type_name.split("$", 1)[0]
    return sources_by_type.get(outer_type)


def has_public_top_level_type(source):
    content = PROJECT_ROOT.joinpath(source["currentPath"]).read_text(
        encoding="utf-8"
    )
    return PUBLIC_TOP_LEVEL_PATTERN.search(content) is not None


def package_relocation_aliases(type_name):
    aliases = []
    for previous, current in PACKAGE_RELOCATIONS.items():
        if type_name == current or type_name.startswith(current + "$"):
            aliases.append(previous + type_name[len(current):])
    return aliases


def historical_type_names(type_name, baseline_types):
    aliases = set(package_relocation_aliases(type_name))
    if type_name in baseline_types:
        aliases.add(type_name)
    simple_binary_name = type_name.rsplit(".", 1)[-1]
    aliases.update(
        baseline_type
        for baseline_type in baseline_types
        if baseline_type.rsplit(".", 1)[-1] == simple_binary_name
    )
    aliases.discard(type_name)
    return sorted(aliases)


def api_classification(type_name, source, baseline_types, previous_types):
    top_level_type = type_name.split("$", 1)[0]
    package_name, top_level = top_level_type.rsplit(".", 1)
    if "/api/internal/" in source["currentPath"]:
        return "internal-type-removed-from-public-surface"
    if top_level in INTERNAL_CONFORMANCE_TYPES:
        return "internal-type-removed-from-public-surface"
    supported_names = SUPPORTED_PUBLIC_TYPES_BY_PACKAGE.get(
        package_name, set()
    )
    supported_package = any(
        package_name == prefix
        or package_name.startswith(prefix + ".")
        for prefix in SUPPORTED_PUBLIC_PACKAGE_PREFIXES
    )
    if top_level not in supported_names and not supported_package:
        return "internal-type-removed-from-public-surface"
    if type_name in baseline_types or set(previous_types) & baseline_types:
        return "compatible-relocation-through-aggregate-facade"
    return "new-supported-api-spi"


def api_classification_reason(classification):
    if classification == "internal-type-removed-from-public-surface":
        return (
            "Fixture implementation or legacy adapter becomes module-internal."
        )
    if classification == "new-supported-api-spi":
        return "Supported API or SPI introduced after the 1.0 API baseline."
    if classification == "intentional-next-major-break":
        return "Approved next-major removal with migration guidance."
    return (
        "Established supported use moved to its published module; runtime "
        "modules remain reachable through the aggregate facade."
    )


def api_inventory_types(paths):
    result = set()
    for path in paths:
        text = pathlib.Path(path).read_text(encoding="utf-8")
        if text.lstrip().startswith("{"):
            payload = json.loads(text)
            result.update(
                entry["name"]
                for entry in payload["classes"]
                if entry.get("access", 0) & 0x0001
            )
            continue
        for line in text.splitlines():
            normalized = line.strip()
            if normalized.startswith("type "):
                result.add(normalized.split(" ", 2)[1])
    return sorted(result)


def module_coordinate(module_id):
    return "blue.language:" + MODULE_DIRECTORIES[module_id]


def api_ledger(current_types, baseline, sources):
    sources_by_type = source_by_top_level_type(sources)
    baseline_types = {entry["name"] for entry in baseline["classes"]}
    entries = []
    for type_name in sorted(current_types):
        source = source_for_type(type_name, sources_by_type)
        if source is None:
            raise ValueError(
                "Public type has no production source ownership: " + type_name
            )
        if not has_public_top_level_type(source):
            continue
        previous_types = historical_type_names(type_name, baseline_types)
        classification = api_classification(
            type_name, source, baseline_types, previous_types
        )
        relocation_history = []
        for previous_type in package_relocation_aliases(type_name):
            relocation_history.append(
                {
                    "from": previous_type,
                    "to": type_name,
                    "commit": PACKAGE_RELOCATION_COMMIT,
                }
            )
        entries.append(
            {
                "type": type_name,
                "sourcePath": source["currentPath"],
                "currentArtifact": module_coordinate(source["targetModule"]),
                "targetModule": source["targetModule"],
                "targetType": type_name,
                "previousTypes": previous_types,
                "relocationHistory": relocation_history,
                "classification": classification,
                "reason": api_classification_reason(classification),
            }
        )
    counts = {}
    for entry in entries:
        classification = entry["classification"]
        counts[classification] = counts.get(classification, 0) + 1
    return {
        "schema": "blue-language-java-module-api-relocation/1.0",
        "baseline": "api/blue-language-java-1.0.json",
        "physicalExtractionCommit": PHYSICAL_EXTRACTION_COMMIT,
        "packageRelocationCommit": PACKAGE_RELOCATION_COMMIT,
        "inventory": {
            "publicProductionTypeCount": len(entries),
            "publicTypeIdentity": digest_lines(
                entry["type"] for entry in entries
            ),
            "classificationCounts": dict(sorted(counts.items())),
        },
        "allowedClassifications": [
            "intentional-next-major-break",
            "compatible-relocation-through-aggregate-facade",
            "internal-type-removed-from-public-surface",
            "new-supported-api-spi",
        ],
        "types": entries,
    }


def build_scripts():
    names = {
        "build.gradle",
        "build.gradle.kts",
        "settings.gradle",
        "settings.gradle.kts",
    }
    candidates = [
        path for path in PROJECT_ROOT.iterdir()
        if path.is_file() and path.name in names
    ]
    for directory in sorted(set(MODULE_DIRECTORIES.values())):
        root = PROJECT_ROOT / directory
        if not root.is_dir():
            continue
        candidates.extend(
            path for path in root.rglob("*")
            if path.is_file()
            and path.name in names
            and ".gradle" not in path.relative_to(root).parts
            and "build" not in path.relative_to(root).parts
        )
    return sorted(set(candidates))


def declaring_project(script):
    relative = script.relative_to(PROJECT_ROOT)
    if len(relative.parts) == 1:
        return ":root"
    directory = relative.parts[0]
    for module_id, module_directory in MODULE_DIRECTORIES.items():
        if directory == module_directory:
            return module_id
    raise ValueError("Build script has no declared project owner: " + str(relative))


def typed_build_logic_sources():
    root = PROJECT_ROOT / "build-logic/src/main/java"
    if not root.is_dir():
        return []
    result = []
    for path in sorted(root.rglob("*.java")):
        content = path.read_text(encoding="utf-8")
        if (TYPED_LITERAL_DEPENDENCY_PATTERN.search(content)
                or TYPED_COORDINATE_CONSTANT_PATTERN.search(content)):
            result.append(path)
    return result


def typed_configuration(expression, coordinate_name=None):
    if coordinate_name and "LAUNCHER" in coordinate_name:
        return "testRuntimeOnly"
    if coordinate_name:
        return "testImplementation"
    normalized = expression.strip().strip("\"'")
    if "TEST_RUNTIME_ONLY" in normalized:
        return "testRuntimeOnly"
    if "TEST_IMPLEMENTATION" in normalized:
        return "testImplementation"
    return normalized


def append_declaration(result, component, declaration):
    result.setdefault(component, []).append(declaration)


def dependency_declarations(scripts, typed_sources):
    result = {}
    for script in scripts:
        relative = script.relative_to(PROJECT_ROOT).as_posix()
        content = script.read_text(encoding="utf-8")
        for match in DEPENDENCY_PATTERN.finditer(content):
            configuration, component, version = match.groups()
            declaration = {
                "path": relative,
                "declaringProject": declaring_project(script),
                "configuration": configuration,
                "declaredVersion": version or "managed",
            }
            append_declaration(result, component, declaration)
    for source in typed_sources:
        relative = source.relative_to(PROJECT_ROOT).as_posix()
        content = source.read_text(encoding="utf-8")
        declaring = (
            ":root"
            if source.name == "RootOrchestrationPlugin.java"
            else MODULE_BUILD_LOGIC
        )
        for match in TYPED_LITERAL_DEPENDENCY_PATTERN.finditer(content):
            expression, component, version = match.groups()
            append_declaration(
                result,
                component,
                {
                    "path": relative,
                    "declaringProject": declaring,
                    "configuration": typed_configuration(expression),
                    "declaredVersion": version or "managed",
                },
            )
        for match in TYPED_COORDINATE_CONSTANT_PATTERN.finditer(content):
            name, component, version = match.groups()
            append_declaration(
                result,
                component,
                {
                    "path": relative,
                    "declaringProject": MODULE_BUILD_LOGIC,
                    "configuration": typed_configuration("", name),
                    "declaredVersion": version or "managed",
                },
            )
    for declarations in result.values():
        declarations.sort(
            key=lambda value: (
                value["path"],
                value["configuration"],
                value["declaringProject"],
                value["declaredVersion"],
            )
        )
    return result


def plugin_declarations(scripts):
    result = {}
    for script in scripts:
        relative = script.relative_to(PROJECT_ROOT).as_posix()
        content = script.read_text(encoding="utf-8")
        for component, version in PLUGIN_PATTERN.findall(content):
            result.setdefault(component, []).append(
                {
                    "path": relative,
                    "declaringProject": declaring_project(script),
                    "version": version,
                }
            )
    for declarations in result.values():
        declarations.sort(
            key=lambda value: (
                value["path"],
                value["declaringProject"],
                value["version"],
            )
        )
    return result


def dependency_ownership():
    scripts = build_scripts()
    typed_sources = typed_build_logic_sources()
    declarations = dependency_declarations(scripts, typed_sources)
    plugins = plugin_declarations(scripts)
    unknown_dependencies = sorted(set(declarations) - set(DEPENDENCY_POLICIES))
    missing_dependencies = sorted(set(DEPENDENCY_POLICIES) - set(declarations))
    unknown_plugins = sorted(set(plugins) - set(PLUGIN_POLICIES))
    missing_plugins = sorted(set(PLUGIN_POLICIES) - set(plugins))
    if unknown_dependencies or missing_dependencies:
        raise ValueError(
            "Dependency policies do not match discovered build scripts; unknown={} "
            "missing={}".format(unknown_dependencies, missing_dependencies)
        )
    if unknown_plugins or missing_plugins:
        raise ValueError(
            "Plugin policies do not match discovered build scripts; unknown={} "
            "missing={}".format(unknown_plugins, missing_plugins)
        )

    libraries = []
    for component in sorted(declarations):
        owner, target_configuration, managed_version, reason = (
            DEPENDENCY_POLICIES[component]
        )
        explicit_versions = sorted(
            {
                declaration["declaredVersion"]
                for declaration in declarations[component]
                if declaration["declaredVersion"] != "managed"
            }
        )
        if len(explicit_versions) > 1:
            raise ValueError(
                "Conflicting direct versions for {}: {}".format(
                    component, explicit_versions
                )
            )
        libraries.append(
            {
                "component": component,
                "currentVersion": (
                    explicit_versions[0]
                    if explicit_versions else managed_version
                ),
                "owner": owner,
                "targetConfiguration": target_configuration,
                "reason": reason,
                "declarations": declarations[component],
            }
        )

    plugin_entries = []
    for component in sorted(plugins):
        owner, reason = PLUGIN_POLICIES[component]
        versions = sorted(
            {declaration["version"] for declaration in plugins[component]}
        )
        if len(versions) != 1:
            raise ValueError(
                "Conflicting plugin versions for {}: {}".format(
                    component, versions
                )
            )
        plugin_entries.append(
            {
                "component": component,
                "currentVersion": versions[0],
                "owner": owner,
                "reason": reason,
                "declarations": plugins[component],
            }
        )

    script_paths = [
        path.relative_to(PROJECT_ROOT).as_posix() for path in scripts
    ]
    typed_source_paths = [
        path.relative_to(PROJECT_ROOT).as_posix()
        for path in typed_sources
    ]
    return {
        "schema": "blue-language-java-dependency-ownership/1.0",
        "inventory": {
            "buildScriptCount": len(script_paths),
            "buildScriptPathIdentity": digest_lines(script_paths),
            "typedBuildLogicSourceCount": len(typed_source_paths),
            "typedBuildLogicSourcePathIdentity": digest_lines(
                typed_source_paths
            ),
            "ownedLibraries": len(libraries),
            "ownedPlugins": len(plugin_entries),
            "removedLibraries": 1,
        },
        "policy": {
            "oneOwningModulePerComponent": True,
            "moduleRuntimeAllowlist": MODULE_RUNTIME_ALLOWLISTS,
            "forbiddenInCoreRuntime": [
                "org.apache.httpcomponents:httpclient",
                "org.reflections:reflections",
                "org.yaml:snakeyaml",
            ],
        },
        "scannedBuildScripts": script_paths,
        "scannedTypedBuildLogicSources": typed_source_paths,
        "libraries": libraries,
        "plugins": plugin_entries,
        "removedLibraries": [
            {
                "component": "commons-codec:commons-codec",
                "reason": (
                    "No production use remains after internal deterministic "
                    "Base58 and hexadecimal support."
                ),
            }
        ],
    }


def write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--api-inventory", action="append", required=True,
        help=(
            "JSON or blue-java-public-api/1.0 inventory; repeat for each "
            "published module"
        ),
    )
    parser.add_argument(
        "--baseline", default="api/blue-language-java-1.0.json"
    )
    parser.add_argument(
        "--ownership-output",
        default="architecture/module-ownership-1.0.json",
    )
    parser.add_argument(
        "--api-output",
        default="api/module-api-relocation-ledger-1.0.json",
    )
    parser.add_argument(
        "--dependency-output",
        default="architecture/dependency-ownership-1.0.json",
    )
    args = parser.parse_args()

    sources = source_entries()
    resources = resource_entries()
    current_types = api_inventory_types(args.api_inventory)
    baseline = json.loads(
        PROJECT_ROOT.joinpath(args.baseline).read_text(encoding="utf-8")
    )
    write_json(
        PROJECT_ROOT.joinpath(args.ownership_output),
        ownership_manifest(sources, resources),
    )
    write_json(
        PROJECT_ROOT.joinpath(args.api_output),
        api_ledger(current_types, baseline, sources),
    )
    write_json(
        PROJECT_ROOT.joinpath(args.dependency_output),
        dependency_ownership(),
    )


PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]


if __name__ == "__main__":
    main()
