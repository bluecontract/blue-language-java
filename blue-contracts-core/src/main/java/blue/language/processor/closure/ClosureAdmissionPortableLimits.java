package blue.language.processor.closure;

import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorErrorCategory;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pre-meter portable-limit gate for a closed admission snapshot. */
final class ClosureAdmissionPortableLimits {

    private ClosureAdmissionPortableLimits() {
    }

    static void verify(ClosureInvocationInput input) {
        ClosureInvocationInput selected = Objects.requireNonNull(input, "input");
        AffectedClosureSnapshot snapshot = selected.snapshot();
        Map<String, Long> limits = selected.environment()
                .portableLimitPolicy().limits();
        requireAtMost(
                "managedDocumentsPerClosure",
                snapshot.managedDocuments().size(), limits);

        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                documentIds(snapshot), snapshot.occurrences());
        requireAtMost(
                "processEmbeddedEdgesPerClosure",
                graph.activeBindings().size(), limits);

        long memberLimit = limit("cyclicMembersPerComponent", limits);
        long edgeLimit = limit("cyclicEdgesPerComponent", limits);
        long byteLimit = limit(
                "cyclicCanonicalBytesPerComponent", limits);
        for (ComponentSnapshot component : snapshot.components()) {
            if (component.kind() != ComponentKind.CYCLIC) {
                continue;
            }
            long members = component.orderedMemberDocumentIds().size();
            if (members > memberLimit) {
                throw exceeded(
                        "cyclicMembersPerComponent", members, memberLimit);
            }
            Set<DocumentId> componentMembers = new HashSet<DocumentId>(
                    component.orderedMemberDocumentIds());
            long edges = 0L;
            for (ManagedOccurrenceBinding binding : graph.activeBindings()) {
                if (componentMembers.contains(binding.sourceDocumentId())
                        && componentMembers.contains(
                                binding.targetDocumentId())) {
                    edges++;
                }
            }
            if (edges > edgeLimit) {
                throw exceeded(
                        "cyclicEdgesPerComponent", edges, edgeLimit);
            }
            long bytes = new CyclicCanonicalLimitProjection()
                    .canonicalBytesForProof(component.completeCyclicProof()
                            .declaredPlaceholderSet());
            if (bytes > byteLimit) {
                throw exceeded(
                        "cyclicCanonicalBytesPerComponent",
                        bytes,
                        byteLimit);
            }
        }
    }

    static long limit(ClosureInvocationInput input, String name) {
        return limit(name, input.environment()
                .portableLimitPolicy().limits());
    }

    private static void requireAtMost(
            String name,
            long observed,
            Map<String, Long> limits) {
        long maximum = limit(name, limits);
        if (observed > maximum) {
            throw exceeded(name, observed, maximum);
        }
    }

    private static long limit(String name, Map<String, Long> limits) {
        Long value = Objects.requireNonNull(limits, "limits").get(name);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Portable policy omits " + name);
        }
        return value.longValue();
    }

    static PortableLimitExceededException exceeded(
            String name,
            long observed,
            long limit) {
        return new PortableLimitExceededException(
                category(name),
                name,
                observed,
                limit);
    }

    private static ProcessorErrorCategory category(String name) {
        if ("managedDocumentsPerClosure".equals(name)) {
            return ProcessorErrorCategory.ManagedDocumentsPerClosureExceeded;
        }
        if ("processEmbeddedEdgesPerClosure".equals(name)) {
            return ProcessorErrorCategory.ProcessEmbeddedEdgesPerClosureExceeded;
        }
        if ("cyclicMembersPerComponent".equals(name)) {
            return ProcessorErrorCategory.CyclicComponentMemberLimitExceeded;
        }
        if ("cyclicEdgesPerComponent".equals(name)) {
            return ProcessorErrorCategory.CyclicComponentEdgeLimitExceeded;
        }
        if ("cyclicCanonicalBytesPerComponent".equals(name)) {
            return ProcessorErrorCategory.CyclicComponentCanonicalBytesExceeded;
        }
        if ("closureGraphChangesPerInvocation".equals(name)) {
            return ProcessorErrorCategory.ClosureGraphChangeLimitExceeded;
        }
        if ("closureExpansionsPerInvocation".equals(name)) {
            return ProcessorErrorCategory.ClosureExpansionLimitExceeded;
        }
        if ("closureWorkOccurrencesPerInvocation".equals(name)) {
            return ProcessorErrorCategory.ClosureWorkOccurrenceLimitExceeded;
        }
        if ("closureTentativeFinalizationsPerInvocation".equals(name)) {
            return ProcessorErrorCategory
                    .ClosureTentativeFinalizationLimitExceeded;
        }
        throw new IllegalArgumentException(
                "Unknown closure portable limit " + name);
    }

    private static List<DocumentId> documentIds(
            AffectedClosureSnapshot snapshot) {
        java.util.ArrayList<DocumentId> result =
                new java.util.ArrayList<DocumentId>();
        for (ManagedDocumentSnapshot document
                : snapshot.managedDocuments()) {
            result.add(document.documentId());
        }
        return result;
    }
}
