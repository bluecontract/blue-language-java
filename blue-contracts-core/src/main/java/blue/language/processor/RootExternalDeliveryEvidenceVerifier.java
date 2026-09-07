package blue.language.processor;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.runtime.LanguageRuntimeAccess;

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
                    null,
                    GasSchedule.contracts10(),
                    GasSchedule.contracts10().maxProcessGas(),
                    ExternalDeliveryPlanDeriver.unavailable());

    private final ExternalDeliveryPlanDeriver planDeriver;
    private final ExternalPreselectionVerifier preselectionVerifier;
    private final ExternalDeliveryPlanVerifier planVerifier;
    private final ProcessingSnapshotManager snapshotManager;
    private final LanguageRuntimeAccess languageRuntimeAccess;
    private final GasSchedule gasSchedule;
    private final long gasLimit;

    private RootExternalDeliveryEvidenceVerifier(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            LanguageRuntimeAccess languageRuntimeAccess,
            GasSchedule gasSchedule,
            long gasLimit,
            ExternalDeliveryPlanDeriver planDeriver) {
        this.planDeriver = Objects.requireNonNull(
                planDeriver, "planDeriver");
        this.preselectionVerifier = new ExternalPreselectionVerifier(
                contractLoader, snapshotManager, registry, converter);
        this.planVerifier = new ExternalDeliveryPlanVerifier(
                preselectionVerifier);
        this.snapshotManager = snapshotManager;
        this.languageRuntimeAccess = languageRuntimeAccess;
        this.gasSchedule = Objects.requireNonNull(
                gasSchedule, "gasSchedule");
        if (gasLimit < 0L
                || gasLimit > gasSchedule.maxProcessGas()) {
            throw new IllegalArgumentException(
                    "gasLimit must be within the configured schedule");
        }
        this.gasLimit = gasLimit;
    }

    static RootExternalDeliveryEvidenceVerifier configured(
            ContractLoader contractLoader,
            ProcessingSnapshotManager snapshotManager,
            ContractProcessorRegistry registry,
            NodeToObjectConverter converter,
            LanguageRuntimeAccess languageRuntimeAccess,
            GasSchedule gasSchedule,
            long gasLimit,
            ExternalDeliveryPlanDeriver planDeriver) {
        return new RootExternalDeliveryEvidenceVerifier(
                Objects.requireNonNull(contractLoader, "contractLoader"),
                snapshotManager,
                Objects.requireNonNull(registry, "registry"),
                Objects.requireNonNull(converter, "converter"),
                languageRuntimeAccess,
                Objects.requireNonNull(gasSchedule, "gasSchedule"),
                gasLimit,
                Objects.requireNonNull(planDeriver, "planDeriver"));
    }

    VerifiedExecutionEvidence deriveAndVerify(
            Node root,
            Node event,
            String rootBlueId,
            String eventBlueId,
            String runtimeRegistryIdentity) {
        ExternalDeliveryPlan plan = derivePlan(root, event);
        VerifiedExecutionEvidence evidence =
                plan.bind(
                        rootBlueId,
                        eventBlueId,
                        runtimeRegistryIdentity);
        evidence.revalidateDerived(
                root,
                event,
                rootBlueId,
                eventBlueId,
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
        planVerifier.verify(
                root,
                event,
                evidence,
                derivePlan(root, event),
                runtimeWorkSessions(event, evidence));
    }

    @Override
    public void verifyDerived(
            Node root,
            Node event,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan derivedPlan) {
        planVerifier.verify(
                root,
                event,
                evidence,
                derivedPlan,
                runtimeWorkSessions(event, evidence));
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

    private ExternalPreselectionVerifier.RuntimeWorkSessionFactory
    runtimeWorkSessions(
            Node exactEvent,
            VerifiedExecutionEvidence evidence) {
        return ProcessorInvocationServices
                .externalPlanVerificationSessions(
                        exactEvent,
                        Objects.requireNonNull(
                                evidence,
                                "evidence").eventBlueId(),
                        languageRuntimeAccess,
                        snapshotManager,
                        gasSchedule,
                        gasLimit);
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
        return SubscriptionSurfaceTypeInspector.contributes(
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
                ? SubscriptionSurfaceTypeInspector.contributes(
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
