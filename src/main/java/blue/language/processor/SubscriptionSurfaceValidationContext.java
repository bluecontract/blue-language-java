package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.ResolvedSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable production context for deterministic changed-subscription
 * validation.
 *
 * <p>The resolved snapshots are optional companions to the exact selected
 * Roots.  They let the built-in validator inspect inherited and referenced
 * effective contracts without treating a resolved representation as Source.
 * The event-order and revision values bind interval changes to the committing
 * processor attempt.</p>
 */
public final class SubscriptionSurfaceValidationContext {

    private final Node inputRoot;
    private final Node tentativeRoot;
    private final ResolvedSnapshot inputSnapshot;
    private final ResolvedSnapshot tentativeSnapshot;
    private final Set<String> changedPaths;
    private final List<SubscriptionDelta.Entry> activeSubscriptionIntervals;
    private final boolean activeSubscriptionIntervalsSupplied;
    private final GasSchedule gasSchedule;
    private final ExternalOrderKey currentEventOrderKey;
    private final Long committingRootRevision;
    private final RuntimeWorkSessionFactory
            runtimeWorkSessionFactory;

    private SubscriptionSurfaceValidationContext(Builder builder) {
        this.inputRoot = Objects.requireNonNull(
                builder.inputRoot, "inputRoot");
        this.tentativeRoot = Objects.requireNonNull(
                builder.tentativeRoot, "tentativeRoot");
        this.inputSnapshot = builder.inputSnapshot;
        this.tentativeSnapshot = builder.tentativeSnapshot;
        this.changedPaths = Collections.unmodifiableSet(
                new LinkedHashSet<>(Objects.requireNonNull(
                        builder.changedPaths, "changedPaths")));
        this.activeSubscriptionIntervals =
                immutableActiveIntervals(
                        builder.activeSubscriptionIntervals);
        this.activeSubscriptionIntervalsSupplied =
                builder.activeSubscriptionIntervalsSupplied;
        this.gasSchedule = Objects.requireNonNull(
                builder.gasSchedule, "gasSchedule");
        this.currentEventOrderKey = builder.currentEventOrderKey;
        this.committingRootRevision = builder.committingRootRevision;
        this.runtimeWorkSessionFactory =
                builder.runtimeWorkSessionFactory;
        if (committingRootRevision != null
                && committingRootRevision.longValue() < 0L) {
            throw new IllegalArgumentException(
                    "committingRootRevision must be non-negative");
        }
    }

    /**
     * Creates a builder for one tentative subscription-surface transition.
     *
     * @param inputRoot exact selected Root before the transition
     * @param tentativeRoot exact selected Root after tentative changes
     * @param changedPaths changed absolute pointers
     * @param gasSchedule admission gas schedule
     * @return new validation-context builder
     */
    public static Builder builder(Node inputRoot,
                                  Node tentativeRoot,
                                  Set<String> changedPaths,
                                  GasSchedule gasSchedule) {
        return new Builder(
                inputRoot, tentativeRoot, changedPaths, gasSchedule);
    }

    /**
     * Returns the exact input Root retained by this context.
     *
     * @return caller-supplied mutable input Root reference
     */
    public Node inputRoot() {
        return inputRoot;
    }

    /**
     * Returns the tentative Root retained by this context.
     *
     * @return caller-supplied mutable tentative Root reference
     */
    public Node tentativeRoot() {
        return tentativeRoot;
    }

    /**
     * Returns the optional resolved input companion.
     *
     * @return immutable input snapshot, or {@code null}
     */
    public ResolvedSnapshot inputSnapshot() {
        return inputSnapshot;
    }

    /**
     * Returns the optional resolved tentative companion.
     *
     * @return immutable tentative snapshot, or {@code null}
     */
    public ResolvedSnapshot tentativeSnapshot() {
        return tentativeSnapshot;
    }

    /**
     * Returns changed paths captured when the context was built.
     *
     * @return immutable insertion-ordered path set
     */
    public Set<String> changedPaths() {
        return changedPaths;
    }

    /**
     * Exact active interval records retained by the authoritative subscription
     * index at the input Root revision.
     *
     * <p>The collection is the complete retained surface when supplied. It is
     * not inferred from the event's preselected delivery subset. The validator
     * reuses these identities for unchanged branches and closes the exact prior
     * interval on removal or replacement.</p>
     *
     * @return immutable retained active interval list
     */
    public List<SubscriptionDelta.Entry> activeSubscriptionIntervals() {
        return activeSubscriptionIntervals;
    }

    /**
     * Reports whether the complete active interval surface was supplied.
     *
     * @return {@code true} for supplied evidence, including an empty surface
     */
    public boolean hasActiveSubscriptionIntervals() {
        return activeSubscriptionIntervalsSupplied;
    }

    /**
     * Returns the schedule used for admission/runtime validation work.
     *
     * @return immutable gas schedule
     */
    public GasSchedule gasSchedule() {
        return gasSchedule;
    }

    /**
     * Returns the event position closing/opening subscription intervals.
     *
     * @return immutable event order key, or {@code null}
     */
    public ExternalOrderKey currentEventOrderKey() {
        return currentEventOrderKey;
    }

    /**
     * Returns the Root revision produced by the committing transition.
     *
     * @return non-negative revision, or {@code null}
     */
    public Long committingRootRevision() {
        return committingRootRevision;
    }

    RuntimeWorkSession newRuntimeWorkSession() {
        if (runtimeWorkSessionFactory != null) {
            return Objects.requireNonNull(
                    runtimeWorkSessionFactory.open(),
                    "runtimeWorkSession");
        }
        return new RuntimeWorkSession(
                new GasMeter(gasSchedule),
                RuntimeWorkSession.Mode.ADMISSION);
    }

    /** Factory for admission-scoped runtime work sessions. */
    interface RuntimeWorkSessionFactory {

        /**
         * Opens a fresh admission session.
         *
         * @return non-null runtime work session
         */
        RuntimeWorkSession open();
    }

    /** Mutable accumulator for an immutable validation context. */
    public static final class Builder {
        private final Node inputRoot;
        private final Node tentativeRoot;
        private final Set<String> changedPaths;
        private final GasSchedule gasSchedule;
        private final List<SubscriptionDelta.Entry>
                activeSubscriptionIntervals = new ArrayList<>();
        private boolean activeSubscriptionIntervalsSupplied;
        private ResolvedSnapshot inputSnapshot;
        private ResolvedSnapshot tentativeSnapshot;
        private ExternalOrderKey currentEventOrderKey;
        private Long committingRootRevision;
        private RuntimeWorkSessionFactory
                runtimeWorkSessionFactory;

        private Builder(Node inputRoot,
                        Node tentativeRoot,
                        Set<String> changedPaths,
                        GasSchedule gasSchedule) {
            this.inputRoot = inputRoot;
            this.tentativeRoot = tentativeRoot;
            this.changedPaths = changedPaths;
            this.gasSchedule = gasSchedule;
        }

        /**
         * Attaches optional resolved snapshot companions.
         *
         * @param input resolved input snapshot, or {@code null}
         * @param tentative resolved tentative snapshot, or {@code null}
         * @return this builder
         */
        public Builder snapshots(ResolvedSnapshot input,
                                 ResolvedSnapshot tentative) {
            this.inputSnapshot = input;
            this.tentativeSnapshot = tentative;
            return this;
        }

        /**
         * Supplies the complete active subscription-index surface retained at
         * the input Root revision.
         *
         * @param intervals complete retained interval surface
         * @return this builder
         * @throws NullPointerException if {@code intervals} or an entry is
         *         {@code null}
         */
        public Builder activeSubscriptionIntervals(
                Iterable<SubscriptionDelta.Entry> intervals) {
            Objects.requireNonNull(intervals, "intervals");
            this.activeSubscriptionIntervals.clear();
            this.activeSubscriptionIntervalsSupplied = true;
            for (SubscriptionDelta.Entry interval : intervals) {
                this.activeSubscriptionIntervals.add(
                        Objects.requireNonNull(
                                interval, "active subscription interval"));
            }
            return this;
        }

        /**
         * Binds the committing event position and resulting Root revision.
         *
         * @param eventOrderKey non-null event order key
         * @param rootRevision resulting non-negative Root revision
         * @return this builder
         * @throws NullPointerException if {@code eventOrderKey} is
         *         {@code null}
         */
        public Builder committingInterval(
                ExternalOrderKey eventOrderKey,
                long rootRevision) {
            this.currentEventOrderKey =
                    Objects.requireNonNull(eventOrderKey, "eventOrderKey");
            this.committingRootRevision = rootRevision;
            return this;
        }

        Builder runtimeWorkSessions(
                RuntimeWorkSessionFactory factory) {
            this.runtimeWorkSessionFactory =
                    Objects.requireNonNull(
                            factory,
                            "runtimeWorkSessionFactory");
            return this;
        }

        /**
         * Validates and freezes the accumulated context.
         *
         * @return immutable validation context
         * @throws NullPointerException if a required Root, changed-path set,
         *         or gas schedule is absent
         * @throws IllegalArgumentException for a negative committing revision
         *         or invalid retained interval surface
         */
        public SubscriptionSurfaceValidationContext build() {
            return new SubscriptionSurfaceValidationContext(this);
        }
    }

    private static List<SubscriptionDelta.Entry> immutableActiveIntervals(
            List<SubscriptionDelta.Entry> source) {
        List<SubscriptionDelta.Entry> copy =
                new ArrayList<>(Objects.requireNonNull(source, "source"));
        Set<String> occurrences = new LinkedHashSet<>();
        for (SubscriptionDelta.Entry entry : copy) {
            Objects.requireNonNull(entry, "active subscription interval");
            if (!entry.isActiveInterval()) {
                throw new IllegalArgumentException(
                        "Retained subscription interval is already retired: "
                                + entry.scopePath() + "/" + entry.channelKey());
            }
            String occurrence =
                    entry.scopePath()
                            + ProcessorIdentityConstants
                                    .SELECTOR_COMPONENT_DELIMITER
                            + entry.channelKey();
            if (!occurrences.add(occurrence)) {
                throw new IllegalArgumentException(
                        "Duplicate retained subscription occurrence: "
                                + entry.scopePath() + "/" + entry.channelKey());
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
