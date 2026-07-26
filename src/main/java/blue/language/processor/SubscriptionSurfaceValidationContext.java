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
        if (committingRootRevision != null
                && committingRootRevision.longValue() < 0L) {
            throw new IllegalArgumentException(
                    "committingRootRevision must be non-negative");
        }
    }

    public static Builder builder(Node inputRoot,
                                  Node tentativeRoot,
                                  Set<String> changedPaths,
                                  GasSchedule gasSchedule) {
        return new Builder(
                inputRoot, tentativeRoot, changedPaths, gasSchedule);
    }

    public Node inputRoot() {
        return inputRoot;
    }

    public Node tentativeRoot() {
        return tentativeRoot;
    }

    public ResolvedSnapshot inputSnapshot() {
        return inputSnapshot;
    }

    public ResolvedSnapshot tentativeSnapshot() {
        return tentativeSnapshot;
    }

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
     */
    public List<SubscriptionDelta.Entry> activeSubscriptionIntervals() {
        return activeSubscriptionIntervals;
    }

    public boolean hasActiveSubscriptionIntervals() {
        return activeSubscriptionIntervalsSupplied;
    }

    public GasSchedule gasSchedule() {
        return gasSchedule;
    }

    public ExternalOrderKey currentEventOrderKey() {
        return currentEventOrderKey;
    }

    public Long committingRootRevision() {
        return committingRootRevision;
    }

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

        private Builder(Node inputRoot,
                        Node tentativeRoot,
                        Set<String> changedPaths,
                        GasSchedule gasSchedule) {
            this.inputRoot = inputRoot;
            this.tentativeRoot = tentativeRoot;
            this.changedPaths = changedPaths;
            this.gasSchedule = gasSchedule;
        }

        public Builder snapshots(ResolvedSnapshot input,
                                 ResolvedSnapshot tentative) {
            this.inputSnapshot = input;
            this.tentativeSnapshot = tentative;
            return this;
        }

        /**
         * Supplies the complete active subscription-index surface retained at
         * the input Root revision.
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

        public Builder committingInterval(
                ExternalOrderKey eventOrderKey,
                long rootRevision) {
            this.currentEventOrderKey =
                    Objects.requireNonNull(eventOrderKey, "eventOrderKey");
            this.committingRootRevision = rootRevision;
            return this;
        }

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
                    entry.scopePath() + "\u0000" + entry.channelKey();
            if (!occurrences.add(occurrence)) {
                throw new IllegalArgumentException(
                        "Duplicate retained subscription occurrence: "
                                + entry.scopePath() + "/" + entry.channelKey());
            }
        }
        return Collections.unmodifiableList(copy);
    }
}
