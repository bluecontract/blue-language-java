package blue.language.processor;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;

import java.util.Objects;
import java.util.Set;

/**
 * Complete core verifier for revision-bound External Channel preselection.
 *
 * <p>The environmental deriver establishes the exact occurrence set,
 * checkpoint subjects, and activation intervals. This verifier independently
 * resolves the effective Contracts surface and binds every occurrence to its
 * ordered Source contributions, type, order, subscription keys, and checkpoint
 * domain.</p>
 */
public final class RootExternalDeliveryEvidenceVerifier
        implements ExternalDeliveryEvidenceVerifier {

    /**
     * Standalone verification has no provider/resolver or environmental
     * subscription state and therefore fails closed.
     */
    public static final RootExternalDeliveryEvidenceVerifier INSTANCE =
            new RootExternalDeliveryEvidenceVerifier(
                    null,
                    null,
                    null,
                    null,
                    ExternalDeliveryPlanDeriver.unavailable());

    private final ExternalDeliveryPlanDeriver planDeriver;
    private final ExternalPreselectionVerifier preselectionVerifier;
    private final ExternalDeliveryPlanVerifier planVerifier;

    private RootExternalDeliveryEvidenceVerifier(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ExternalDeliveryPlanDeriver planDeriver) {
        this.planDeriver = Objects.requireNonNull(
                planDeriver, "planDeriver");
        this.preselectionVerifier = new ExternalPreselectionVerifier(
                contractLoader, snapshotManager, registry, converter);
        this.planVerifier = new ExternalDeliveryPlanVerifier(
                preselectionVerifier);
    }

    static RootExternalDeliveryEvidenceVerifier configured(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            ExternalDeliveryPlanDeriver planDeriver) {
        return new RootExternalDeliveryEvidenceVerifier(
                Objects.requireNonNull(contractLoader, "contractLoader"),
                snapshotManager,
                Objects.requireNonNull(registry, "registry"),
                Objects.requireNonNull(converter, "converter"),
                Objects.requireNonNull(planDeriver, "planDeriver"));
    }

    VerifiedExecutionEvidence deriveAndVerify(
            Node root,
            Node event,
            String runtimeRegistryIdentity) {
        ExternalDeliveryPlan plan = derivePlan(root, event);
        VerifiedExecutionEvidence evidence =
                plan.bind(root, event, runtimeRegistryIdentity);
        evidence.revalidateDerived(
                root,
                event,
                runtimeRegistryIdentity,
                this,
                plan);
        return evidence;
    }

    @Override
    public void verify(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence) {
        planVerifier.verify(root, event, evidence, derivePlan(root, event));
    }

    @Override
    public void verifyDerived(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan derivedPlan) {
        planVerifier.verify(root, event, evidence, derivedPlan);
    }

    /** Replays a supplied plan through one exact invocation environment. */
    void verifyDerived(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan derivedPlan,
            ExternalPreselectionVerifier.RuntimeWorkSessionFactory
                    runtimeWorkSessions) {
        planVerifier.verify(
                root,
                event,
                evidence,
                derivedPlan,
                runtimeWorkSessions);
    }

    ExternalDeliveryPlan derivePlan(Node root, Node event) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(event, "event");
        try {
            ExternalDeliveryPlan plan;
            if (planDeriver == ExternalDeliveryPlanDeriver.UNAVAILABLE) {
                plan = preselectionVerifier.deriveProvablyEmptyPlan(root);
            } else {
                plan = planDeriver.derive(root.clone(), event.clone());
            }
            if (plan == null) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "External delivery plan deriver returned no plan");
            }
            if (!plan.exactRuntimeState()) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "External delivery plan is not certified complete");
            }
            return plan;
        } catch (ExecutionEvidenceUnavailableException exception) {
            throw exception;
        } catch (InvalidExecutionEvidenceException exception) {
            throw exception;
        } catch (SubscriptionSurfaceInvalidException exception) {
            throw exception;
        } catch (PortableLimitExceededException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            if (BlueLanguageErrorClassifier.classify(exception)
                    == BlueLanguageErrorCategory.ProviderUnavailable) {
                throw ExternalEvidenceVerificationSupport.unavailable(
                        "External delivery plan acquisition failed: "
                                + ProcessorEngine.deterministicMessage(
                                exception, "provider unavailable"),
                        ExternalEvidenceVerificationSupport.referencedBlueIds(
                                root, event));
            }
            throw ExternalEvidenceVerificationSupport.invalid(
                    "External delivery plan derivation failed: "
                            + ProcessorEngine.deterministicMessage(
                            exception, "environmental state unavailable"));
        }
    }

    static boolean typeContributesToSubscriptionSurface(
            ProcessingSnapshotManager snapshotManager,
            Node declaredType,
            Set<String> requestedChannelKeys,
            boolean includeProcessEmbedded,
            Set<String> visited) {
        return ExternalSubscriptionProjectionBuilder
                .typeContributesToSubscriptionSurface(
                        snapshotManager,
                        declaredType,
                        requestedChannelKeys,
                        includeProcessEmbedded,
                        visited);
    }

    static boolean typeContributesToSubscriptionSurface(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            Node declaredType,
            Set<String> requestedChannelKeys,
            boolean includeProcessEmbedded,
            Set<String> visited) {
        return contractLoader != null
                ? ExternalSubscriptionProjectionBuilder
                .typeContributesToSubscriptionSurface(
                        contractLoader::materializeVerifiedReference,
                        declaredType,
                        requestedChannelKeys,
                        includeProcessEmbedded,
                        visited)
                : typeContributesToSubscriptionSurface(
                        snapshotManager,
                        declaredType,
                        requestedChannelKeys,
                        includeProcessEmbedded,
                        visited);
    }
}
