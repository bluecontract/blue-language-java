package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.ManagedProcessEmbeddedPath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Read-only typed demand discovery over complete projected Root surfaces. */
final class ProcessEmbeddedDemandDiscovery {

    List<ClosureResourceDemand> discoverAll(
            Map<DocumentId, Node> resultingSources,
            Map<DocumentId, List<ManagedProcessEmbeddedPath>>
                    effectiveSurfaces,
            List<ManagedOccurrenceBinding> currentBindings,
            List<ManagedDocumentSnapshot> currentDocuments,
            ProcessEmbeddedSurfaceReconciler.DemandContext demandContext) {
        Map<DocumentId, Node> sources = Objects.requireNonNull(
                resultingSources, "resultingSources");
        Map<DocumentId, List<ManagedProcessEmbeddedPath>> surfaces =
                Objects.requireNonNull(
                        effectiveSurfaces, "effectiveSurfaces");
        if (!sources.keySet().equals(surfaces.keySet())) {
            throw new IllegalArgumentException(
                    "Every resulting Root must have one complete projected "
                            + "Process Embedded surface");
        }
        ArrayList<DocumentId> orderedSources =
                new ArrayList<DocumentId>(sources.keySet());
        Collections.sort(orderedSources);
        ArrayList<ClosureResourceDemand> demands =
                new ArrayList<ClosureResourceDemand>();
        for (DocumentId source : orderedSources) {
            demands.addAll(discover(
                    source,
                    Objects.requireNonNull(
                            sources.get(source), "resulting source"),
                    Objects.requireNonNull(
                            surfaces.get(source), "effective surface"),
                    currentBindings,
                    currentDocuments,
                    demandContext));
        }
        Collections.sort(demands);
        return Collections.unmodifiableList(demands);
    }

    List<ClosureResourceDemand> discover(
            DocumentId sourceDocumentId,
            Node resultingSource,
            List<ManagedProcessEmbeddedPath> effectiveSurface,
            List<ManagedOccurrenceBinding> currentBindings,
            List<ManagedDocumentSnapshot> currentDocuments,
            ProcessEmbeddedSurfaceReconciler.DemandContext demandContext) {
        DocumentId source = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        Node document = Objects.requireNonNull(
                resultingSource, "resultingSource");
        ProcessEmbeddedSurfaceReconciler.DemandContext context =
                Objects.requireNonNull(demandContext, "demandContext");
        Map<DocumentId, ManagedDocumentSnapshot> documents = documentsById(
                currentDocuments);
        if (!documents.containsKey(source)) {
            throw new IllegalArgumentException(
                    "Process Embedded source is outside closure membership");
        }
        Map<String, ManagedProcessEmbeddedPath> declared = projectedByPath(
                effectiveSurface);
        Map<String, ManagedOccurrenceBinding> bindings = bindingsByPath(
                source, currentBindings);
        ArrayList<ClosureResourceDemand> demands =
                new ArrayList<ClosureResourceDemand>();
        long ordinal = 0L;
        for (ManagedProcessEmbeddedPath projection : declared.values()) {
            String path = projection.absolutePath();
            Node supplied = NodePathEditor.getOrNull(document, path);
            if (supplied == null) {
                throw missingEffectiveValue(source, path);
            }
            ManagedOccurrenceBinding binding = bindings.get(path);
            if (binding != null) {
                if (binding.pendingHistoricalEpoch() != null) {
                    if (context.verifyHistoricalExactReferences()
                            && supplied.isReferenceOnly()
                            && binding.expectedTargetBlueId().equals(
                                    supplied.getBlueId())
                            && !context.exactReferenceAvailability()
                                    .isAvailable(supplied.getBlueId())) {
                        demands.add(ExactNodeDemand.providerResource(
                                supplied.getBlueId()));
                    }
                    ordinal++;
                    continue;
                }
                if (!binding.active()
                        || exactTarget(supplied, binding, documents) != null) {
                    ordinal++;
                    continue;
                }
            }
            String suppliedBlueId = supplied.isReferenceOnly()
                    ? supplied.getBlueId()
                    : DirectBlueIdCalculator.calculateBlueId(supplied);
            if (supplied.isReferenceOnly()
                    && !knownExactContent(
                            supplied,
                            suppliedBlueId,
                            documents.values(),
                            context.exactReferenceAvailability())) {
                demands.add(ExactNodeDemand.derived(
                        suppliedBlueId, source, path));
            } else {
                if (supplied.isReferenceOnly()) {
                    demands.add(ManagedOccurrenceEvidenceDemand.derived(
                            context.logicalCauseIdentity(),
                            context.inputClosureIdentity(),
                            context.inputGraphGeneration(),
                            source,
                            path,
                            projection.declarationContributionBlueId(),
                            suppliedBlueId,
                            ordinal));
                } else {
                    demands.add(ManagedOccurrenceEvidenceDemand.derived(
                            context.logicalCauseIdentity(),
                            context.inputClosureIdentity(),
                            context.inputGraphGeneration(),
                            source,
                            path,
                            projection.declarationContributionBlueId(),
                            suppliedBlueId,
                            ordinal,
                            supplied));
                }
            }
            ordinal++;
        }
        Collections.sort(demands);
        return Collections.unmodifiableList(demands);
    }

    private static Map<DocumentId, ManagedDocumentSnapshot> documentsById(
            List<ManagedDocumentSnapshot> currentDocuments) {
        Map<DocumentId, ManagedDocumentSnapshot> result =
                new LinkedHashMap<DocumentId, ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot document : Objects.requireNonNull(
                currentDocuments, "currentDocuments")) {
            ManagedDocumentSnapshot checked = Objects.requireNonNull(
                    document, "managed document");
            if (result.put(checked.documentId(), checked) != null) {
                throw new IllegalArgumentException(
                        "Duplicate managed DocumentId");
            }
        }
        return result;
    }

    private static Map<String, ManagedProcessEmbeddedPath> projectedByPath(
            List<ManagedProcessEmbeddedPath> effectiveSurface) {
        ArrayList<ManagedProcessEmbeddedPath> ordered =
                new ArrayList<ManagedProcessEmbeddedPath>(
                        Objects.requireNonNull(
                                effectiveSurface, "effectiveSurface"));
        Collections.sort(ordered);
        Map<String, ManagedProcessEmbeddedPath> result =
                new LinkedHashMap<String, ManagedProcessEmbeddedPath>();
        for (ManagedProcessEmbeddedPath projection : ordered) {
            ManagedProcessEmbeddedPath checked = Objects.requireNonNull(
                    projection, "Process Embedded projection");
            if (result.put(checked.absolutePath(), checked) != null) {
                throw new IllegalArgumentException(
                        "Duplicate concrete Process Embedded path: "
                                + checked.absolutePath());
            }
        }
        return result;
    }

    private static Map<String, ManagedOccurrenceBinding> bindingsByPath(
            DocumentId source,
            List<ManagedOccurrenceBinding> currentBindings) {
        Map<String, ManagedOccurrenceBinding> result =
                new LinkedHashMap<String, ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : Objects.requireNonNull(
                currentBindings, "currentBindings")) {
            if (source.equals(binding.sourceDocumentId())
                    && result.put(binding.sourcePath(), binding) != null) {
                throw new IllegalArgumentException(
                        "Managed Root has duplicate current occurrence paths");
            }
        }
        return result;
    }

    private static ManagedDocumentSnapshot exactTarget(
            Node value,
            ManagedOccurrenceBinding current,
            Map<DocumentId, ManagedDocumentSnapshot> documents) {
        ManagedDocumentSnapshot prior = documents.get(
                current.targetDocumentId());
        if (ManagedOccurrenceTargetVerifier.establishesExactTarget(
                value, prior)) {
            return prior;
        }
        ManagedDocumentSnapshot result = null;
        for (ManagedDocumentSnapshot candidate : documents.values()) {
            if (!ManagedOccurrenceTargetVerifier.establishesExactTarget(
                    value, candidate)) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = candidate;
        }
        return result;
    }

    private static boolean knownExactContent(
            Node supplied,
            String suppliedBlueId,
            Iterable<ManagedDocumentSnapshot> documents,
            ProcessEmbeddedSurfaceReconciler.ExactReferenceAvailability
                    availability) {
        for (ManagedDocumentSnapshot candidate : documents) {
            if (suppliedBlueId.equals(candidate.blueId())
                    && ManagedOccurrenceTargetVerifier.establishesExactTarget(
                            supplied, candidate)) {
                return true;
            }
        }
        return availability.isAvailable(suppliedBlueId);
    }

    private static ClosureCapabilityGapException missingEffectiveValue(
            DocumentId source,
            String path) {
        return new ClosureCapabilityGapException(
                "PROCESS_EMBEDDED_EFFECTIVE_VALUE_PROJECTION_REQUIRED",
                "An effective Process Embedded path has no exact value: "
                        + source.value() + ":" + path);
    }
}
