package blue.language.processor;

import blue.language.model.Node;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Verifies plan headers and the exact canonical delivery occurrence list. */
final class ExternalDeliveryPlanVerifier {

    private final ExternalPreselectionVerifier preselectionVerifier;

    ExternalDeliveryPlanVerifier(
            ExternalPreselectionVerifier preselectionVerifier) {
        this.preselectionVerifier = preselectionVerifier;
    }

    /** Verifies through an explicitly invocation-bound runtime session. */
    void verify(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan plan,
            ExternalPreselectionVerifier.RuntimeWorkSessionFactory
                    runtimeWorkSessions) {
        verifyHeadersAndDeliveries(evidence, plan);
        preselectionVerifier.verify(
                root,
                event,
                evidence,
                Objects.requireNonNull(
                        runtimeWorkSessions,
                        "runtimeWorkSessions"));
    }

    void verify(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan plan,
            ExternalPreselectionVerifier.EvaluationResult evaluated) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        verifyHeadersAndDeliveries(evidence, plan);
        preselectionVerifier.verify(evidence, evaluated);
    }

    private void verifyHeadersAndDeliveries(
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan plan) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(plan, "plan");
        if (!plan.exactRuntimeState()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery plan is not certified complete");
        }
        if (evidence.managedRootRevision()
                != plan.managedRootRevision()
                || evidence.indexedRootRevision()
                != plan.indexedRootRevision()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery plan revision mismatch");
        }
        if (!evidence.eventOrderKey().equals(plan.eventOrderKey())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery event order mismatch");
        }
        if (!evidence.availableExactNodeBlueIds().equals(
                plan.availableExactNodeBlueIds())
                || !evidence.requiredExactNodeBlueIds().equals(
                plan.requiredExactNodeBlueIds())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery resource closure mismatch");
        }
        if (evidence.hasActiveSubscriptionIntervals()
                != plan.hasActiveSubscriptionIntervals()
                || !evidence.activeSubscriptionIntervals().equals(
                plan.activeSubscriptionIntervals())) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery active subscription interval "
                            + "surface mismatch");
        }
        verifyExactDeliveries(evidence.deliveries(), plan.deliveries());
    }

    static void verifyExactDeliveries(
            List<ExternalDeliverySnapshot> actual,
            List<ExternalDeliverySnapshot> expected) {
        if (actual.size() != expected.size()) {
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery occurrence set is incomplete or has "
                            + "extra entries");
        }
        Set<String> occurrences = new LinkedHashSet<>();
        ExternalDeliverySnapshot previous = null;
        for (int index = 0; index < actual.size(); index++) {
            ExternalDeliverySnapshot delivery = actual.get(index);
            if (!sameDelivery(delivery, expected.get(index))) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "External delivery occurrence mismatch at index "
                                + index);
            }
            if (previous != null
                    && ExternalDeliverySnapshot.compareCanonical(
                    previous, delivery) > 0) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "External delivery snapshot is not in canonical order");
            }
            String occurrence =
                    ExternalEvidenceVerificationSupport.occurrenceKey(
                            delivery.scopePath(), delivery.channelKey());
            if (!occurrences.add(occurrence)) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Duplicate External Channel occurrence at "
                                + delivery.scopePath() + "/"
                                + delivery.channelKey());
            }
            previous = delivery;
        }
    }

    private static boolean sameDelivery(
            ExternalDeliverySnapshot left,
            ExternalDeliverySnapshot right) {
        return left.scopePath().equals(right.scopePath())
                && left.channelKey().equals(right.channelKey())
                && left.order() == right.order()
                && left.sourceContributionNodeBlueIds().equals(
                right.sourceContributionNodeBlueIds())
                && left.effectiveTypeBlueId().equals(
                right.effectiveTypeBlueId())
                && left.subscriptionKeys().equals(
                right.subscriptionKeys())
                && left.checkpointDomainBlueId().equals(
                right.checkpointDomainBlueId())
                && left.checkpointSubjectBlueId().equals(
                right.checkpointSubjectBlueId())
                && Objects.equals(
                left.activationStartExclusive(),
                right.activationStartExclusive())
                && Objects.equals(
                left.activationEndInclusive(),
                right.activationEndInclusive());
    }
}
