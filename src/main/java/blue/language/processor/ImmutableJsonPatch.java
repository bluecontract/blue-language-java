package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.ParsedJsonPointer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable transaction-boundary representation of a JSON patch. */
final class ImmutableJsonPatch {

    private final JsonPatch.Op op;
    private final String authoredPath;
    private final ParsedJsonPointer path;
    private final Node authoredValue;
    private final FrozenNode canonicalValue;
    private final FrozenNode resolvedValue;
    private final String valueBlueId;
    private final ProcessingMetricsSink metrics;

    private ImmutableJsonPatch(JsonPatch.Op op,
                               String authoredPath,
                               ParsedJsonPointer path,
                               Node authoredValue,
                               FrozenNode canonicalValue,
                               FrozenNode resolvedValue,
                               ProcessingMetricsSink metrics) {
        this.op = Objects.requireNonNull(op, "op");
        this.authoredPath = Objects.requireNonNull(authoredPath, "authoredPath");
        this.path = Objects.requireNonNull(path, "path");
        this.authoredValue = authoredValue;
        this.canonicalValue = canonicalValue;
        this.resolvedValue = resolvedValue;
        this.valueBlueId = op == JsonPatch.Op.REMOVE ? null : resolvedValue.blueId();
        this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
    }

    static PreparationContext preparationContext(ProcessingMetricsSink metrics) {
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
        return new PreparationContext(ProcessingMetricsSink.NOOP)
                .prepare(patch, canonicalRoot, resolvedRoot);
    }

    static ImmutableJsonPatch from(FrozenJsonPatch patch,
                                   FrozenNode canonicalRoot,
                                   FrozenNode resolvedRoot) {
        return new PreparationContext(ProcessingMetricsSink.NOOP)
                .prepare(patch, canonicalRoot, resolvedRoot);
    }

    JsonPatch.Op op() {
        return op;
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
                metrics);
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
        metrics.incrementFrozenPatchValuesMaterialized();
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
        private final ProcessingMetricsSink metrics;
        private final Map<String, ParsedJsonPointer> parsedPointers =
                new LinkedHashMap<String, ParsedJsonPointer>(16, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, ParsedJsonPointer> eldest) {
                        return size() > MAX_PARSED_POINTERS;
                    }
                };

        private PreparationContext(ProcessingMetricsSink metrics) {
            this.metrics = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
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
                metrics.incrementParsedPointerCacheMisses();
            } else {
                metrics.incrementParsedPointerCacheHits();
            }

            if (op == JsonPatch.Op.REMOVE) {
                return new ImmutableJsonPatch(op, authoredPath, parsed, null, null, null, metrics);
            }

            Node value = Objects.requireNonNull(patch.getVal(), "patch value");
            metrics.incrementMutablePatchValuesFrozen(source);
            FrozenNode canonical = freeze(value, canonicalRoot);
            FrozenNode resolved;
            if (sameFreezeMode(canonicalRoot, resolvedRoot)) {
                resolved = canonical;
                metrics.incrementFrozenPatchValueHits();
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
            metrics.incrementFrozenPatchValuesAccepted();
            FrozenNode canonical = FrozenNode.authoredValueInModeOf(authored, canonicalRoot);
            FrozenNode resolved;
            if (sameFreezeMode(canonicalRoot, resolvedRoot)) {
                resolved = canonical;
                metrics.incrementFrozenPatchValueHits();
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
