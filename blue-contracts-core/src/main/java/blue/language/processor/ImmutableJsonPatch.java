package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.BluePatchOperation;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.ParsedJsonPointer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Defensively captured JSON patch used at transaction boundaries.
 *
 * <p>The authored pointer and value are retained alongside parsed and frozen
 * forms. Mutable caller input is never consulted after construction, and
 * canonical/resolved value materialization is memoized for the owning
 * transaction.</p>
 */
final class ImmutableJsonPatch {

    private final JsonPatch.Op op;
    private final String authoredPath;
    private final ParsedJsonPointer path;
    private final Node authoredValue;
    private final FrozenNode canonicalValue;
    private final FrozenNode resolvedValue;
    private final String valueBlueId;
    private final ProcessingObserver metrics;
    private final boolean exactValueWrite;

    private ImmutableJsonPatch(JsonPatch.Op op,
                               String authoredPath,
                               ParsedJsonPointer path,
                               Node authoredValue,
                               FrozenNode canonicalValue,
                               FrozenNode resolvedValue,
                               ProcessingObserver metrics) {
        this(op,
                authoredPath,
                path,
                authoredValue,
                canonicalValue,
                resolvedValue,
                metrics,
                false);
    }

    private ImmutableJsonPatch(JsonPatch.Op op,
                               String authoredPath,
                               ParsedJsonPointer path,
                               Node authoredValue,
                               FrozenNode canonicalValue,
                               FrozenNode resolvedValue,
                               ProcessingObserver metrics,
                               boolean exactValueWrite) {
        this.op = Objects.requireNonNull(op, "op");
        this.authoredPath = Objects.requireNonNull(authoredPath, "authoredPath");
        this.path = Objects.requireNonNull(path, "path");
        this.authoredValue = authoredValue;
        this.canonicalValue = canonicalValue;
        this.resolvedValue = resolvedValue;
        this.valueBlueId = op == JsonPatch.Op.REMOVE ? null : resolvedValue.blueId();
        this.metrics = metrics != null ? metrics : NoOpProcessingObserver.INSTANCE;
        this.exactValueWrite = exactValueWrite;
    }

    static PreparationContext preparationContext(ProcessingObserver metrics) {
        return new PreparationContext(metrics);
    }

    /** Takes an authored-value snapshot without parsing or canonicalizing it. */
    static JsonPatch copy(JsonPatch patch) {
        if (patch == null) {
            return null;
        }
        switch (patch.getOp()) {
            case ADD:
                return JsonPatch.add(patch.getPath(), patch.getVal().clone());
            case REPLACE:
                return JsonPatch.replace(patch.getPath(), patch.getVal().clone());
            case REMOVE:
                return JsonPatch.remove(patch.getPath());
            default:
                throw new IllegalStateException("Unsupported patch op: " + patch.getOp());
        }
    }

    static ImmutableJsonPatch from(JsonPatch patch,
                                   FrozenNode canonicalRoot,
                                   FrozenNode resolvedRoot) {
        return new PreparationContext(NoOpProcessingObserver.INSTANCE)
                .prepare(patch, canonicalRoot, resolvedRoot);
    }

    static ImmutableJsonPatch from(FrozenJsonPatch patch,
                                   FrozenNode canonicalRoot,
                                   FrozenNode resolvedRoot) {
        return new PreparationContext(NoOpProcessingObserver.INSTANCE)
                .prepare(patch, canonicalRoot, resolvedRoot);
    }

    JsonPatch.Op op() {
        return op;
    }

    /** Returns the Language-owned operation used by the patch engine. */
    BluePatchOperation blueOperation() {
        return op.blueOperation();
    }

    String authoredPath() {
        return authoredPath;
    }

    ParsedJsonPointer path() {
        return path;
    }

    String normalizedPath() {
        return path.pointer();
    }

    FrozenNode canonicalValue() {
        return canonicalValue;
    }

    FrozenNode resolvedValue() {
        return resolvedValue;
    }

    ImmutableJsonPatch withResolvedValue(FrozenNode replacement) {
        if (op == JsonPatch.Op.REMOVE) {
            return this;
        }
        FrozenNode checked = Objects.requireNonNull(replacement, "resolved patch value");
        if (checked.isStrictCanonical()) {
            throw new IllegalArgumentException("Resolved patch value must use resolved construction mode");
        }
        return new ImmutableJsonPatch(op,
                authoredPath,
                path,
                authoredValue,
                canonicalValue,
                checked,
                metrics,
                exactValueWrite);
    }

    ImmutableJsonPatch withCanonicalAndResolvedValues(
            FrozenNode canonicalReplacement,
            FrozenNode resolvedReplacement) {
        if (op == JsonPatch.Op.REMOVE) {
            return this;
        }
        FrozenNode canonical = Objects.requireNonNull(
                canonicalReplacement, "canonical patch value");
        FrozenNode resolved = Objects.requireNonNull(
                resolvedReplacement, "resolved patch value");
        if (!sameFreezeMode(canonicalValue, canonical)) {
            throw new IllegalArgumentException(
                    "Selected patch value must preserve its construction mode");
        }
        if (!sameFreezeMode(resolvedValue, resolved)) {
            throw new IllegalArgumentException(
                    "Resolved patch value must preserve its construction mode");
        }
        return new ImmutableJsonPatch(
                op,
                authoredPath,
                path,
                authoredValue,
                canonical,
                resolved,
                metrics,
                exactValueWrite);
    }

    ImmutableJsonPatch withExactValueWrite() {
        if (op == JsonPatch.Op.REMOVE || exactValueWrite) {
            return this;
        }
        return new ImmutableJsonPatch(
                op,
                authoredPath,
                path,
                authoredValue,
                canonicalValue,
                resolvedValue,
                metrics,
                true);
    }

    boolean exactValueWrite() {
        return exactValueWrite;
    }

    FrozenNode valueFor(FrozenNode root) {
        if (op == JsonPatch.Op.REMOVE) {
            return null;
        }
        return sameFreezeMode(root, canonicalValue) ? canonicalValue : resolvedValue;
    }

    String valueBlueId() {
        return valueBlueId;
    }

    boolean matches(ImmutableJsonPatch candidate) {
        if (candidate == null || op != candidate.op || !path.equals(candidate.path)) {
            return false;
        }
        return op == JsonPatch.Op.REMOVE
                || valueBlueId.equals(candidate.valueBlueId)
                && canonicalValue.resolvedStructuralKey().equals(
                        candidate.canonicalValue.resolvedStructuralKey());
    }

    JsonPatch materialize() {
        switch (op) {
            case ADD:
                return JsonPatch.add(authoredPath, materializedAuthoredValue());
            case REPLACE:
                return JsonPatch.replace(authoredPath, materializedAuthoredValue());
            case REMOVE:
                return JsonPatch.remove(authoredPath);
            default:
                throw new IllegalStateException("Unsupported patch op: " + op);
        }
    }

    private Node materializedAuthoredValue() {
        if (authoredValue != null) {
            return authoredValue.clone();
        }
        ProcessingObservations.record(metrics,
                ProcessingMetricId.FROZEN_PATCH_VALUES_MATERIALIZED, 1L);
        return canonicalValue.toNode();
    }

    private static FrozenNode freeze(Node value, FrozenNode modeRoot) {
        if (modeRoot.isStrictCanonical()) {
            return modeRoot.isStrictBlueIdValidation()
                    ? FrozenNode.fromNode(value)
                    : FrozenNode.fromUncheckedCanonicalNode(value);
        }
        return FrozenNode.fromResolvedNode(value);
    }

    private static boolean sameFreezeMode(FrozenNode left, FrozenNode right) {
        return left.isStrictCanonical() == right.isStrictCanonical()
                && left.isStrictBlueIdValidation() == right.isStrictBlueIdValidation();
    }

    static final class PreparationContext {
        private static final int MAX_PARSED_POINTERS = 256;
        private final ProcessingObserver metrics;
        private final Map<String, ParsedJsonPointer> parsedPointers =
                new LinkedHashMap<String, ParsedJsonPointer>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, ParsedJsonPointer> eldest) {
                        return size() > MAX_PARSED_POINTERS;
                    }
                };

        private PreparationContext(ProcessingObserver metrics) {
            this.metrics = metrics != null ? metrics : NoOpProcessingObserver.INSTANCE;
        }

        ImmutableJsonPatch prepare(JsonPatch patch,
                                   FrozenNode canonicalRoot,
                                   FrozenNode resolvedRoot) {
            return prepare(patch, canonicalRoot, resolvedRoot, PatchSource.LEGACY_PUBLIC_API);
        }

        ImmutableJsonPatch prepare(JsonPatch patch,
                                   FrozenNode canonicalRoot,
                                   FrozenNode resolvedRoot,
                                   PatchSource source) {
            Objects.requireNonNull(patch, "patch");
            Objects.requireNonNull(canonicalRoot, "canonicalRoot");
            Objects.requireNonNull(resolvedRoot, "resolvedRoot");
            JsonPatch.Op op = Objects.requireNonNull(patch.getOp(), "patch op");
            String authoredPath = Objects.requireNonNull(patch.getPath(), "patch path");
            ParsedJsonPointer parsed = parsedPointers.get(authoredPath);
            if (parsed == null) {
                parsed = ParsedJsonPointer.parse(authoredPath);
                parsedPointers.put(authoredPath, parsed);
                ProcessingObservations.record(metrics,
                        ProcessingMetricId.PARSED_POINTER_CACHE_MISSES, 1L);
            } else {
                ProcessingObservations.record(metrics,
                        ProcessingMetricId.PARSED_POINTER_CACHE_HITS, 1L);
            }

            if (op == JsonPatch.Op.REMOVE) {
                return new ImmutableJsonPatch(op, authoredPath, parsed, null, null, null, metrics);
            }

            Node value = Objects.requireNonNull(patch.getVal(), "patch value");
            PatchSource fixedSource = source != null
                    ? source
                    : PatchSource.UNKNOWN_INTERNAL;
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.MUTABLE_PATCH_VALUES_FROZEN, 1L);
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.MUTABLE_PATCH_VALUES_FROZEN_BY_SOURCE,
                    1L,
                    ProcessingObservationContext.of(
                            ProcessingObservationDimension.PATCH_SOURCE,
                            fixedSource.name()));
            FrozenNode canonical = freeze(value, canonicalRoot);
            FrozenNode resolved;
            if (sameFreezeMode(canonicalRoot, resolvedRoot)) {
                resolved = canonical;
                ProcessingObservations.record(metrics,
                        ProcessingMetricId.FROZEN_PATCH_VALUE_HITS, 1L);
            } else {
                resolved = freeze(value, resolvedRoot);
            }
            return new ImmutableJsonPatch(op, authoredPath, parsed, value, canonical, resolved, metrics);
        }

        ImmutableJsonPatch prepare(FrozenJsonPatch patch,
                                   FrozenNode canonicalRoot,
                                   FrozenNode resolvedRoot) {
            Objects.requireNonNull(patch, "patch");
            Objects.requireNonNull(canonicalRoot, "canonicalRoot");
            Objects.requireNonNull(resolvedRoot, "resolvedRoot");
            JsonPatch.Op op = Objects.requireNonNull(patch.getOp(), "patch op");
            String authoredPath = Objects.requireNonNull(patch.getPath(), "patch path");
            ParsedJsonPointer parsed = patch.parsedPath();
            if (op == JsonPatch.Op.REMOVE) {
                return new ImmutableJsonPatch(op, authoredPath, parsed, null, null, null, metrics);
            }

            FrozenNode authored = Objects.requireNonNull(patch.getValue(), "patch value");
            ProcessingObservations.record(metrics,
                    ProcessingMetricId.FROZEN_PATCH_VALUES_ACCEPTED, 1L);
            FrozenNode canonical = FrozenNode.authoredValueInModeOf(authored, canonicalRoot);
            FrozenNode resolved;
            if (sameFreezeMode(canonicalRoot, resolvedRoot)) {
                resolved = canonical;
                ProcessingObservations.record(metrics,
                        ProcessingMetricId.FROZEN_PATCH_VALUE_HITS, 1L);
            } else {
                resolved = FrozenNode.authoredValueInModeOf(authored, resolvedRoot);
            }
            return new ImmutableJsonPatch(op, authoredPath, parsed, null, canonical, resolved, metrics);
        }

        int cachedPointerCount() {
            return parsedPointers.size();
        }
    }
}
