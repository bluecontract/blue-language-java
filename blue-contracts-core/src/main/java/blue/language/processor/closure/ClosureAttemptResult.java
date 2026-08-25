package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Complete closure result or typed resource suspension; never both. */
public final class ClosureAttemptResult {

    /** Closed completion-or-suspension discriminator. */
    public enum Kind {
        /** Attempt produced one completed result. */
        COMPLETE("complete"),
        /** Attempt suspended for exact named resources. */
        NEEDS_RESOURCES("needs-resources");

        private final String wireValue;

        Kind(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * Returns the documented value.
         *
         * @return stable attempt-kind wire value
         */
        public String wireValue() {
            return wireValue;
        }
    }

    private final Kind kind;
    private final ClosureProcessResult processResult;
    private final List<ClosureResourceDemand> resourceDemands;
    private final List<String> requiredExactBlueIds;

    private ClosureAttemptResult(
            Kind kind,
            ClosureProcessResult processResult,
            List<ClosureResourceDemand> resourceDemands) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.processResult = processResult;
        this.resourceDemands = Collections.unmodifiableList(
                new ArrayList<ClosureResourceDemand>(resourceDemands));
        if ((kind == Kind.COMPLETE) != (processResult != null)
                || (kind == Kind.COMPLETE) != resourceDemands.isEmpty()) {
            throw new IllegalArgumentException(
                    "A closure attempt must be complete or suspended, never both");
        }
        TreeSet<String> exactBlueIds = new TreeSet<String>(
                ClosureValueSupport::comparePortableText);
        for (ClosureResourceDemand demand : this.resourceDemands) {
            if (demand instanceof ExactNodeDemand) {
                exactBlueIds.add(((ExactNodeDemand) demand).blueId());
            }
        }
        this.requiredExactBlueIds = Collections.unmodifiableList(
                new ArrayList<String>(exactBlueIds));
    }

    /**
     * Wraps one completed closure result.
     *
     * @param result completed result
     * @return complete attempt
     */
    public static ClosureAttemptResult complete(ClosureProcessResult result) {
        return new ClosureAttemptResult(
                Kind.COMPLETE,
                Objects.requireNonNull(result, "result"),
                Collections.<ClosureResourceDemand>emptyList());
    }

    /**
     * Creates a canonical duplicate-free typed-resource suspension.
     *
     * <p>Typed demands are authoritative and ordered exclusively by their
     * canonical multi-demand key. Demand kind is not an ordering prefix.
     * Demands with the same canonical identity collapse to one entry. When
     * duplicate managed-occurrence demands differ only because one carries a
     * verified inline supplied value, the resource-bearing form is retained
     * regardless of discovery order.</p>
     *
     * @param resourceDemands exact demands required to retry
     * @return suspended attempt
     */
    public static ClosureAttemptResult needsResources(
            Collection<? extends ClosureResourceDemand> resourceDemands) {
        Objects.requireNonNull(resourceDemands, "resourceDemands");
        TreeMap<String, ClosureResourceDemand> unique =
                new TreeMap<String, ClosureResourceDemand>(
                        ClosureValueSupport::comparePortableText);
        for (ClosureResourceDemand demand : resourceDemands) {
            ClosureResourceDemand selected = Objects.requireNonNull(
                    demand, "resourceDemand");
            String identity = selected.demandIdentity();
            unique.put(identity, preferredDuplicate(
                    unique.get(identity), selected));
        }
        if (unique.isEmpty()) {
            throw new IllegalArgumentException(
                    "NeedsResources must name at least one typed demand");
        }
        ArrayList<ClosureResourceDemand> ordered =
                new ArrayList<ClosureResourceDemand>(unique.values());
        Collections.sort(ordered);
        return new ClosureAttemptResult(
                Kind.NEEDS_RESOURCES,
                null,
                ordered);
    }

    private static ClosureResourceDemand preferredDuplicate(
            ClosureResourceDemand retained,
            ClosureResourceDemand candidate) {
        if (retained == null) {
            return candidate;
        }
        if (retained instanceof ManagedOccurrenceEvidenceDemand
                && candidate instanceof ManagedOccurrenceEvidenceDemand) {
            ManagedOccurrenceEvidenceDemand retainedOccurrence =
                    (ManagedOccurrenceEvidenceDemand) retained;
            ManagedOccurrenceEvidenceDemand candidateOccurrence =
                    (ManagedOccurrenceEvidenceDemand) candidate;
            if (!retainedOccurrence.suppliedExactValue().isPresent()
                    && candidateOccurrence.suppliedExactValue().isPresent()) {
                return candidateOccurrence;
            }
        }
        return retained;
    }

    /**
     * Compatibility bridge for callers that only expose exact BlueIds.
     *
     * <p>Because this legacy shape carries no source provenance, its typed
     * exact-node demands use a deterministic provider source and root logical
     * path. New callers should supply fully attributed typed demands.</p>
     *
     * @param requiredExactBlueIds exact BlueIds required to retry
     * @return suspended attempt
     */
    public static ClosureAttemptResult needsResources(
            List<String> requiredExactBlueIds) {
        return needsExactResources(requiredExactBlueIds);
    }

    static ClosureAttemptResult needsExactResources(
            Collection<String> requiredExactBlueIds) {
        Objects.requireNonNull(requiredExactBlueIds, "requiredExactBlueIds");
        TreeSet<String> sorted = new TreeSet<String>(
                ClosureValueSupport::comparePortableText);
        for (String blueId : requiredExactBlueIds) {
            sorted.add(ClosureValueSupport.requireBlueId(
                    blueId, "required exact BlueId"));
        }
        if (sorted.isEmpty()) {
            throw new IllegalArgumentException(
                    "NeedsResources must name at least one exact BlueId");
        }
        ArrayList<ClosureResourceDemand> demands =
                new ArrayList<ClosureResourceDemand>();
        for (String blueId : sorted) {
            demands.add(ExactNodeDemand.providerResource(blueId));
        }
        return needsResources(demands);
    }

    /**
     * Returns the documented value.
     *
     * @return completion-or-suspension discriminator
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Returns the documented value.
     *
     * @return whether this attempt contains a completed result
     */
    public boolean isComplete() {
        return kind == Kind.COMPLETE;
    }

    /**
     * Returns the documented value.
     *
     * @return completed result, or {@code null} for suspension
     */
    public ClosureProcessResult processResult() {
        return processResult;
    }

    /**
     * Returns the authoritative typed resource demands.
     *
     * @return immutable canonical duplicate-free demand list
     */
    public List<ClosureResourceDemand> resourceDemands() {
        return resourceDemands;
    }

    /**
     * Returns the compatibility projection of exact-node demands.
     *
     * <p>Managed-occurrence evidence demands are intentionally absent. The
     * typed {@link #resourceDemands()} list is authoritative.</p>
     *
     * @return immutable sorted duplicate-free exact BlueId list
     */
    public List<String> requiredExactBlueIds() {
        return requiredExactBlueIds;
    }

    /**
     * Returns gas only for a completed result.
     *
     * @return exact total gas, or {@code null} for suspension
     */
    public Long totalGas() {
        return processResult == null
                ? null
                : Long.valueOf(processResult.totalGas());
    }
}
