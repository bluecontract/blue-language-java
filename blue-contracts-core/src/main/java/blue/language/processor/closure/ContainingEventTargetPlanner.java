package blue.language.processor.closure;

import blue.language.model.wire.JsonPointer;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.PortableLimitExceededException;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.util.PointerUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Freezes finite, transitive reverse-containment routes for one event. */
final class ContainingEventTargetPlanner {

    private static final Comparator<ManagedOccurrenceBinding>
            BINDING_ORDER = new Comparator<ManagedOccurrenceBinding>() {
                @Override
                public int compare(
                        ManagedOccurrenceBinding left,
                        ManagedOccurrenceBinding right) {
                    int order = left.sourceDocumentId().compareTo(
                            right.sourceDocumentId());
                    if (order != 0) {
                        return order;
                    }
                    order = ClosureValueSupport.comparePortableText(
                            left.sourcePath(), right.sourcePath());
                    if (order != 0) {
                        return order;
                    }
                    order = Long.compare(
                            left.activationGeneration(),
                            right.activationGeneration());
                    return order != 0 ? order
                            : ClosureValueSupport.comparePortableText(
                                    left.occurrenceIdentity(),
                                    right.occurrenceIdentity());
                }
            };

    private static final Comparator<FrozenContainingEventTarget>
            TARGET_ORDER = new Comparator<FrozenContainingEventTarget>() {
                @Override
                public int compare(
                        FrozenContainingEventTarget left,
                        FrozenContainingEventTarget right) {
                    int order = left.receivingDocumentId().compareTo(
                            right.receivingDocumentId());
                    if (order != 0) {
                        return order;
                    }
                    order = ClosureValueSupport.comparePortableText(
                            left.sourcePath(), right.sourcePath());
                    if (order != 0) {
                        return order;
                    }
                    List<ManagedOccurrenceBinding> leftLineage =
                            left.lineage();
                    List<ManagedOccurrenceBinding> rightLineage =
                            right.lineage();
                    order = Long.compare(
                            leftLineage.get(0).activationGeneration(),
                            rightLineage.get(0).activationGeneration());
                    if (order != 0) {
                        return order;
                    }
                    int common = Math.min(
                            leftLineage.size(), rightLineage.size());
                    for (int index = 0; index < common; index++) {
                        order = ClosureValueSupport.comparePortableText(
                                leftLineage.get(index).occurrenceIdentity(),
                                rightLineage.get(index).occurrenceIdentity());
                        if (order != 0) {
                            return order;
                        }
                    }
                    return Integer.compare(
                            leftLineage.size(), rightLineage.size());
                }
            };

    private ContainingEventTargetPlanner() {
    }

    /**
     * Enumerates every simple reverse-containment path ending at the source.
     * A path-local visited set prevents cycles while retaining distinct paths
     * through diamonds and repeated managed-document occurrences.
     */
    static List<FrozenContainingEventTarget> freeze(
            DocumentId eventSourceDocumentId,
            List<ManagedOccurrenceBinding> bindings,
            long targetLimit,
            long pointerSegmentLimit,
            long pointerUtf8Limit) {
        DocumentId source = Objects.requireNonNull(
                eventSourceDocumentId, "eventSourceDocumentId");
        requireNonNegativeLimit(targetLimit, "targetLimit");
        requireNonNegativeLimit(pointerSegmentLimit, "pointerSegmentLimit");
        requireNonNegativeLimit(pointerUtf8Limit, "pointerUtf8Limit");

        Map<DocumentId, List<ManagedOccurrenceBinding>> incoming =
                incomingActiveBindings(bindings);
        Deque<TraversalState> pending = new ArrayDeque<TraversalState>();
        pending.addLast(TraversalState.root(source));
        ArrayList<FrozenContainingEventTarget> result =
                new ArrayList<FrozenContainingEventTarget>();
        Map<TargetKey, FrozenContainingEventTarget> unique =
                new LinkedHashMap<TargetKey, FrozenContainingEventTarget>();

        while (!pending.isEmpty()) {
            TraversalState state = pending.removeLast();
            List<ManagedOccurrenceBinding> candidates = incoming.get(
                    state.innerDocumentId);
            if (candidates == null) {
                continue;
            }
            for (int index = candidates.size() - 1; index >= 0; index--) {
                ManagedOccurrenceBinding outer = candidates.get(index);
                if (state.visitedDocumentIds.contains(
                        outer.sourceDocumentId())) {
                    continue;
                }
                validateDerivedPointer(
                        outer.sourcePath(),
                        pointerSegmentLimit,
                        pointerUtf8Limit);
                String path = PointerUtils.joinRelativePointers(
                        outer.sourcePath(), state.innerPath);
                validateDerivedPointer(
                        path, pointerSegmentLimit, pointerUtf8Limit);
                ArrayList<ManagedOccurrenceBinding> lineage =
                        new ArrayList<ManagedOccurrenceBinding>(
                                state.lineage.size() + 1);
                lineage.add(outer);
                lineage.addAll(state.lineage);
                FrozenContainingEventTarget target =
                        new FrozenContainingEventTarget(
                                outer.sourceDocumentId(), path, lineage);
                TargetKey key = new TargetKey(
                        target.receivingDocumentId(), target.sourcePath());
                FrozenContainingEventTarget previous = unique.put(key, target);
                if (previous != null) {
                    throw new IllegalStateException(
                            "Distinct containing lineages resolve to the same "
                                    + "event target "
                                    + target.receivingDocumentId() + path);
                }
                result.add(target);
                if (result.size() > targetLimit) {
                    throw exceeded(
                            GasScheduleConstants.PortableLimit
                                    .PARTICIPATING_SCOPES_PER_EVENT,
                            result.size(), targetLimit);
                }

                LinkedHashSet<DocumentId> visited =
                        new LinkedHashSet<DocumentId>(
                                state.visitedDocumentIds);
                visited.add(outer.sourceDocumentId());
                pending.addLast(new TraversalState(
                        outer.sourceDocumentId(), path, lineage, visited));
            }
        }
        Collections.sort(result, TARGET_ORDER);
        return Collections.unmodifiableList(result);
    }

    private static Map<DocumentId, List<ManagedOccurrenceBinding>>
            incomingActiveBindings(
                    List<ManagedOccurrenceBinding> bindings) {
        LinkedHashMap<DocumentId, List<ManagedOccurrenceBinding>> result =
                new LinkedHashMap<DocumentId,
                        List<ManagedOccurrenceBinding>>();
        for (ManagedOccurrenceBinding binding : Objects.requireNonNull(
                bindings, "bindings")) {
            if (!binding.active()) {
                continue;
            }
            List<ManagedOccurrenceBinding> target = result.get(
                    binding.targetDocumentId());
            if (target == null) {
                target = new ArrayList<ManagedOccurrenceBinding>();
                result.put(binding.targetDocumentId(), target);
            }
            target.add(binding);
        }
        for (List<ManagedOccurrenceBinding> target : result.values()) {
            Collections.sort(target, BINDING_ORDER);
        }
        return result;
    }

    private static void validateDerivedPointer(
            String path,
            long segmentLimit,
            long utf8Limit) {
        long utf8Bytes = path.getBytes(StandardCharsets.UTF_8).length;
        if (utf8Bytes > utf8Limit) {
            throw exceeded(
                    GasScheduleConstants.PortableLimit
                            .RUNTIME_POINTER_UTF8_BYTES,
                    utf8Bytes, utf8Limit);
        }
        String canonical = PointerUtils.assertValidRuntimePointer(path);
        if (!canonical.equals(path)) {
            throw new IllegalArgumentException(
                    "Containing event source path must be canonical");
        }
        long segments = JsonPointer.split(path).size();
        if (segments > segmentLimit) {
            throw exceeded(
                    GasScheduleConstants.PortableLimit
                            .RUNTIME_POINTER_SEGMENTS,
                    segments, segmentLimit);
        }
    }

    private static PortableLimitExceededException exceeded(
            String name,
            long observed,
            long limit) {
        return new PortableLimitExceededException(
                ProcessorErrorCategory.InvalidProcessingDocument,
                name,
                observed,
                limit);
    }

    private static void requireNonNegativeLimit(long limit, String name) {
        if (limit < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }

    private static final class TraversalState {
        private final DocumentId innerDocumentId;
        private final String innerPath;
        private final List<ManagedOccurrenceBinding> lineage;
        private final Set<DocumentId> visitedDocumentIds;

        private TraversalState(
                DocumentId innerDocumentId,
                String innerPath,
                List<ManagedOccurrenceBinding> lineage,
                Set<DocumentId> visitedDocumentIds) {
            this.innerDocumentId = innerDocumentId;
            this.innerPath = innerPath;
            this.lineage = lineage;
            this.visitedDocumentIds = visitedDocumentIds;
        }

        private static TraversalState root(DocumentId source) {
            return new TraversalState(
                    source,
                    "/",
                    Collections.<ManagedOccurrenceBinding>emptyList(),
                    Collections.singleton(source));
        }
    }

    private static final class TargetKey {
        private final DocumentId documentId;
        private final String sourcePath;

        private TargetKey(DocumentId documentId, String sourcePath) {
            this.documentId = documentId;
            this.sourcePath = sourcePath;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof TargetKey)) {
                return false;
            }
            TargetKey target = (TargetKey) other;
            return documentId.equals(target.documentId)
                    && sourcePath.equals(target.sourcePath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(documentId, sourcePath);
        }
    }
}
