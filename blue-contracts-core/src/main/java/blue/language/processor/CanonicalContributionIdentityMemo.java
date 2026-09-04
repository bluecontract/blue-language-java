package blue.language.processor;

import blue.language.processor.util.PointerUtils;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Bounded, invocation-owned memo for canonical Source contribution identities.
 *
 * <p>Contract recognition is deliberately rebuilt when it is metered, because
 * physical cache warmth must not change gas or trace. Canonicalizing the same
 * immutable Source contribution is not itself a metered semantic operation,
 * however, and can be reused within one invocation. The exact Source
 * representation and both path sets form the key, so representation-sensitive
 * canonicalization requests never alias.</p>
 *
 * <p>The memo retains successful identities only. Provider absence, invalid
 * evidence, and every other failure are retried through the authoritative
 * resolver and preserve their original diagnostics.</p>
 */
final class CanonicalContributionIdentityMemo {

    private static final int DEFAULT_MAXIMUM_ENTRIES = 256;
    private static final long DEFAULT_MAXIMUM_WEIGHT_BYTES = 8L * 1024L * 1024L;
    private static final long DEFAULT_MAXIMUM_ENTRY_WEIGHT_BYTES = 2L * 1024L * 1024L;

    private final int maximumEntries;
    private final long maximumWeightBytes;
    private final long maximumEntryWeightBytes;
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>();
    private long currentWeightBytes;

    CanonicalContributionIdentityMemo() {
        this(
                DEFAULT_MAXIMUM_ENTRIES,
                DEFAULT_MAXIMUM_WEIGHT_BYTES,
                DEFAULT_MAXIMUM_ENTRY_WEIGHT_BYTES);
    }

    CanonicalContributionIdentityMemo(
            int maximumEntries,
            long maximumWeightBytes,
            long maximumEntryWeightBytes) {
        if (maximumEntries <= 0) {
            throw new IllegalArgumentException(
                    "maximumEntries must be positive");
        }
        if (maximumWeightBytes <= 0L) {
            throw new IllegalArgumentException(
                    "maximumWeightBytes must be positive");
        }
        if (maximumEntryWeightBytes <= 0L
                || maximumEntryWeightBytes > maximumWeightBytes) {
            throw new IllegalArgumentException(
                    "maximumEntryWeightBytes must be positive and no greater "
                            + "than maximumWeightBytes");
        }
        this.maximumEntries = maximumEntries;
        this.maximumWeightBytes = maximumWeightBytes;
        this.maximumEntryWeightBytes = maximumEntryWeightBytes;
    }

    String resolve(
            FrozenNode exactAuthoredSource,
            Collection<String> exactFieldPaths,
            Collection<String> executableBodyPaths,
            Supplier<String> authoritativeResolver) {
        FrozenNode source = Objects.requireNonNull(
                exactAuthoredSource, "exactAuthoredSource");
        Supplier<String> resolver = Objects.requireNonNull(
                authoritativeResolver, "authoritativeResolver");
        Key key = new Key(
                source.resolvedStructuralKey(),
                normalizedPaths(exactFieldPaths, "exactFieldPaths"),
                normalizedPaths(executableBodyPaths, "executableBodyPaths"));
        synchronized (this) {
            Entry retained = entries.get(key);
            if (retained != null) {
                return retained.blueId;
            }
        }

        String resolved = Objects.requireNonNull(
                resolver.get(),
                "canonicalContributionBlueId");
        long weight = estimateWeight(source, key, resolved);
        if (weight > maximumEntryWeightBytes
                || weight > maximumWeightBytes) {
            return resolved;
        }
        synchronized (this) {
            Entry raced = entries.get(key);
            if (raced != null) {
                if (!raced.blueId.equals(resolved)) {
                    throw new IllegalStateException(
                            "Canonical contribution identity changed within "
                                    + "one invocation");
                }
                return raced.blueId;
            }
            entries.put(key, new Entry(resolved, weight));
            currentWeightBytes = saturatedAdd(currentWeightBytes, weight);
            evictToBounds();
        }
        return resolved;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long weightBytes() {
        return currentWeightBytes;
    }

    synchronized void clear() {
        entries.clear();
        currentWeightBytes = 0L;
    }

    private List<String> normalizedPaths(
            Collection<String> paths,
            String label) {
        Collection<String> checked = Objects.requireNonNull(paths, label);
        List<String> normalized = new ArrayList<>(checked.size());
        for (String path : checked) {
            normalized.add(PointerUtils.normalizePointer(
                    Objects.requireNonNull(path, label + " entry")));
        }
        Collections.sort(normalized);
        for (int index = normalized.size() - 1; index > 0; index--) {
            if (normalized.get(index).equals(normalized.get(index - 1))) {
                normalized.remove(index);
            }
        }
        return Collections.unmodifiableList(normalized);
    }

    private long estimateWeight(
            FrozenNode source,
            Key key,
            String blueId) {
        long weight = 192L;
        // The structural key retains an exact projection of the frozen graph.
        // Count both representations conservatively even when they share leaf
        // values internally.
        long sourceWeight = source.approximateRetainedWeightBytes();
        weight = saturatedAdd(
                weight,
                saturatedAdd(sourceWeight, sourceWeight));
        weight = saturatedAdd(weight, retainedStrings(key.exactFieldPaths));
        weight = saturatedAdd(weight, retainedStrings(key.executableBodyPaths));
        return saturatedAdd(weight, retainedString(blueId));
    }

    private long retainedStrings(List<String> values) {
        long weight = 32L;
        for (String value : values) {
            weight = saturatedAdd(weight, retainedString(value));
        }
        return weight;
    }

    private long retainedString(String value) {
        return value != null ? 48L + 2L * value.length() : 0L;
    }

    private long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE
                : left + right;
    }

    private void evictToBounds() {
        Iterator<Map.Entry<Key, Entry>> iterator =
                entries.entrySet().iterator();
        while ((entries.size() > maximumEntries
                || currentWeightBytes > maximumWeightBytes)
                && iterator.hasNext()) {
            Entry eldest = iterator.next().getValue();
            currentWeightBytes -= eldest.weightBytes;
            iterator.remove();
        }
    }

    private static final class Key {
        private final FrozenNode.ResolvedStructuralKey sourceFingerprint;
        private final List<String> exactFieldPaths;
        private final List<String> executableBodyPaths;

        private Key(
                FrozenNode.ResolvedStructuralKey sourceFingerprint,
                List<String> exactFieldPaths,
                List<String> executableBodyPaths) {
            this.sourceFingerprint = Objects.requireNonNull(
                    sourceFingerprint, "sourceFingerprint");
            this.exactFieldPaths = exactFieldPaths;
            this.executableBodyPaths = executableBodyPaths;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key)) {
                return false;
            }
            Key that = (Key) other;
            return sourceFingerprint.equals(that.sourceFingerprint)
                    && exactFieldPaths.equals(that.exactFieldPaths)
                    && executableBodyPaths.equals(that.executableBodyPaths);
        }

        @Override
        public int hashCode() {
            return Objects.hash(
                    sourceFingerprint,
                    exactFieldPaths,
                    executableBodyPaths);
        }
    }

    private static final class Entry {
        private final String blueId;
        private final long weightBytes;

        private Entry(String blueId, long weightBytes) {
            this.blueId = blueId;
            this.weightBytes = weightBytes;
        }
    }
}
