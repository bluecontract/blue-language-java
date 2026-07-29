package blue.language.processor;

import java.util.Objects;

/**
 * Revision-bound, non-semantic companion for one host platform commit.
 *
 * <p>This value is not a ProcessResult field and is not part of the Root
 * outbox. A host persists the semantic result together with this companion in
 * one compare-and-swap transaction. The subscription delta is the exact
 * immutable value produced by pre-commit validation.</p>
 */
public final class PlatformCommitCompanion {

    private final String expectedRootBlueId;
    private final String eventBlueId;
    private final long expectedRootRevision;
    private final long resultingRootRevision;
    private final ExternalOrderKey eventOrderKey;
    private final SubscriptionDelta subscriptionDelta;
    private final boolean rootAndOutboxCommit;

    private PlatformCommitCompanion(
            VerifiedExecutionEvidence evidence,
            DocumentProcessingResult result,
            SubscriptionDelta subscriptionDelta) {
        this.expectedRootBlueId = evidence.rootBlueId();
        this.eventBlueId = evidence.eventBlueId();
        this.expectedRootRevision =
                evidence.managedRootRevision();
        this.eventOrderKey = evidence.eventOrderKey();
        this.subscriptionDelta = Objects.requireNonNull(
                subscriptionDelta, "subscriptionDelta");
        this.rootAndOutboxCommit = result.commits();
        if (!rootAndOutboxCommit
                && !subscriptionDelta.isEmpty()) {
            throw new IllegalArgumentException(
                    "A progress-only platform commit cannot carry a "
                            + "subscription delta");
        }
        if (rootAndOutboxCommit) {
            if (expectedRootRevision == Long.MAX_VALUE) {
                throw new IllegalArgumentException(
                        "Committing Root revision overflows");
            }
            this.resultingRootRevision =
                    expectedRootRevision + 1L;
        } else {
            this.resultingRootRevision =
                    expectedRootRevision;
        }
    }

    static PlatformCommitCompanion of(
            VerifiedExecutionEvidence evidence,
            DocumentProcessingResult result,
            SubscriptionDelta subscriptionDelta) {
        return new PlatformCommitCompanion(
                Objects.requireNonNull(evidence, "evidence"),
                Objects.requireNonNull(result, "result"),
                subscriptionDelta);
    }

    /**
     * Returns the Root identity used for compare-and-swap.
     *
     * @return expected pre-commit Root BlueId
     */
    public String expectedRootBlueId() {
        return expectedRootBlueId;
    }

    /**
     * Returns the exact event identity advanced by the transaction.
     *
     * @return event BlueId
     */
    public String eventBlueId() {
        return eventBlueId;
    }

    /**
     * Returns the Root revision expected before the transaction.
     *
     * @return expected Root revision
     */
    public long expectedRootRevision() {
        return expectedRootRevision;
    }

    /**
     * Returns the Root revision after the transaction.
     *
     * @return incremented revision for a Root commit, otherwise the expected
     *         revision
     */
    public long resultingRootRevision() {
        return resultingRootRevision;
    }

    /**
     * Returns the total-order event position committed as progress.
     *
     * @return immutable event order key
     */
    public ExternalOrderKey eventOrderKey() {
        return eventOrderKey;
    }

    /**
     * Returns the exact subscription-index transition.
     *
     * @return immutable subscription delta
     */
    public SubscriptionDelta subscriptionDelta() {
        return subscriptionDelta;
    }

    /**
     * Whether the transaction installs the returned Root/outbox as well as
     * terminal delivery progress. Otherwise it is a revision-bound
     * progress-only transaction.
     *
     * @return {@code true} when Root and outbox are committed
     */
    public boolean commitsRootAndOutbox() {
        return rootAndOutboxCommit;
    }
}
