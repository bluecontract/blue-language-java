package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.ExternalOrderKey;
import java.util.*;

/**
 * A selected exact observer view narrowed from already authenticated source receipts.
 * This value proves neither Timeline completeness nor that its terminal operation is
 * the last one through F. Coordination must establish those facts from its closed
 * canonical source boundary before invoking Language. The Session subsequently
 * establishes the actual creator-site installation; a host timestamp/body is not
 * an accepted placement.
 */
public final class SourceFrontierView {
    private final SameOriginAttachmentPolicy.Selection selection;
    private final String successfulOperationIdentity, terminalOperationIdentity, identity;
    private final ManagedReadPin selectedView;
    private final long successfulEpoch;
    private final ExternalOrderKey productionOrder;
    private final ClosureEnvironment environment;
    private final ExecutionPolicy executionPolicy;

    SourceFrontierView(SameOriginAttachmentPolicy.Selection selection, String successfulOperationIdentity,
            String terminalOperationIdentity, ManagedReadPin selectedView, long successfulEpoch,
            ExternalOrderKey productionOrder, ClosureEnvironment environment, ExecutionPolicy executionPolicy) {
        this.selection = Objects.requireNonNull(selection, "selection");
        if (selection.mode() != SameOriginAttachmentPolicy.Mode.FROM_FRONTIER)
            throw new IllegalArgumentException("A selected frontier view requires FROM_FRONTIER");
        this.successfulOperationIdentity = ClosureValueSupport.requireSha256Identity(successfulOperationIdentity, "successfulOperationIdentity");
        this.terminalOperationIdentity = ClosureValueSupport.requireSha256Identity(terminalOperationIdentity, "terminalOperationIdentity");
        this.selectedView = Objects.requireNonNull(selectedView, "selectedView");
        if (!selection.targetLineage().equals(selectedView.documentId()))
            throw new IllegalArgumentException("Selected frontier view belongs to another source");
        this.successfulEpoch = ClosureValueSupport.requireSafeInteger(successfulEpoch, "successfulEpoch");
        if (productionOrder != null) {
            SameOriginAttachmentPolicy.requireFrontier(productionOrder);
            if (productionOrder.compareTo(selection.frontier().get()) > 0)
                throw new IllegalArgumentException("Source operation was produced after the requested frontier");
        }
        this.productionOrder = productionOrder;
        this.environment = Objects.requireNonNull(environment, "environment");
        this.executionPolicy = Objects.requireNonNull(executionPolicy, "executionPolicy");
        identity = ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.SELECTED_SOURCE_FRONTIER_VIEW, identityValue());
    }

    /** Validates retained source execution evidence, not the completeness of its prefix through F. */
    public static SourceFrontierView fromRetainedEvidence(SameOriginAttachmentPolicy.Selection selection,
            SourceObservationProgram successfulProgram, Optional<SourceOperationFailure> terminalFailure, ManagedReadPin exactPin) {
        Objects.requireNonNull(successfulProgram, "successfulProgram"); Objects.requireNonNull(terminalFailure, "terminalFailure");
        DocumentId source = Objects.requireNonNull(selection, "selection").targetLineage();
        if (!successfulProgram.ownedDocumentIds().contains(source))
            throw new IllegalArgumentException("Selected successful operation does not own the source");
        SourceObservationProgram.SourceState successful = state(successfulProgram.sourceResults(), source);
        if (!successful.initialized() || !successful.blueId().equals(exactPin.blueId())
                || !successful.documentId().equals(exactPin.documentId())
                || !successful.frozenDocument().blueId().equals(exactPin.frozenDocument().blueId()))
            throw new IllegalArgumentException("Selected frontier pin differs from the exact successful source result");
        ExternalOrderKey successfulOrder = producingOrder(successfulProgram.externalCause(), successfulProgram.managedReaction());
        if (successfulOrder != null && successfulOrder.compareTo(selection.frontier().orElseThrow(() ->
                new IllegalArgumentException("Missing frontier"))) > 0)
            throw new IllegalArgumentException("Successful source view was produced after the requested frontier");
        String terminal = successfulProgram.invocationIdentity(); ExternalOrderKey production = successfulOrder;
        if (terminalFailure.isPresent()) {
            SourceOperationFailure failure = terminalFailure.get();
            if (!failure.ownedDocumentIds().contains(source)) throw new IllegalArgumentException("Terminal failure does not own the selected source");
            requireEnvironment(successfulProgram.environment(), successfulProgram.executionPolicy(), failure.environment(), failure.executionPolicy());
            SourceObservationProgram.SourceState rollback = state(failure.sourcePredecessors(), source);
            if (!rollback.initialized() || !rollback.blueId().equals(successful.blueId()) || rollback.epoch() != successful.epoch()
                    || !rollback.frozenDocument().blueId().equals(successful.frozenDocument().blueId()))
                throw new IllegalArgumentException("Terminal source failure does not retain the selected successful view");
            terminal = failure.invocationIdentity(); production = producingOrder(failure.externalCause(), failure.managedReaction());
            if (production == null || successfulOrder != null && production.compareTo(successfulOrder) < 0)
                throw new IllegalArgumentException("Terminal source failure precedes the selected successful operation");
        }
        return new SourceFrontierView(selection, successfulProgram.invocationIdentity(), terminal, exactPin, successful.epoch(),
                production, successfulProgram.environment(), successfulProgram.executionPolicy());
    }

    public SameOriginAttachmentPolicy.Selection selection() { return selection; }
    public ManagedReadPin selectedView() { return selectedView; }
    public long successfulEpoch() { return successfulEpoch; }
    public String successfulOperationIdentity() { return successfulOperationIdentity; }
    public String terminalOperationIdentity() { return terminalOperationIdentity; }
    public Optional<ExternalOrderKey> productionOrder() { return Optional.ofNullable(productionOrder); }
    public ClosureEnvironment environment() { return environment; }
    public ExecutionPolicy executionPolicy() { return executionPolicy; }
    public String identity() { return identity; }
    public void verifyInvocation(ClosureInvocationInput input) {
        verifyInvocation(input, SourceExecutionBasis.fixedPolicyBases(Collections.singleton(selection.targetLineage()),
                input.environment(), input.executionPolicy()));
    }

    /** Verifies the selected source's own basis, not the creator's independent gas budget. */
    public void verifyInvocation(ClosureInvocationInput input, Map<DocumentId, String> expectedSourceBases) {
        SourceExecutionBasis.requireProducerBases(Collections.singleton(selection.targetLineage()), input.environment(),
                environment, executionPolicy, expectedSourceBases);
        ExternalOrderKey creator = producingOrder(input.cause() instanceof ExternalEventCause ? (ExternalEventCause) input.cause() : null, input.managedReaction());
        if (creator == null || selection.frontier().get().compareTo(creator) > 0)
            throw new IllegalArgumentException("Frontier is after the actual creator producing position");
    }

    private static SourceObservationProgram.SourceState state(List<SourceObservationProgram.SourceState> states, DocumentId source) {
        for (SourceObservationProgram.SourceState state : states) if (state.documentId().equals(source)) return state;
        throw new IllegalArgumentException("Source receipt omits its selected owned state");
    }
    private static ExternalOrderKey producingOrder(ExternalEventCause cause, Optional<ManagedReactionContext> context) {
        return context.isPresent() ? context.get().activationCut() : cause == null ? null : cause.sourceOrder();
    }
    static void requireEnvironment(ClosureEnvironment expected, ExecutionPolicy policy, ClosureEnvironment actual, ExecutionPolicy actualPolicy) {
        if (!SourceObservationProgramCodec.environment(expected).equals(SourceObservationProgramCodec.environment(actual))
                || !SourceObservationProgramCodec.policy(policy).equals(SourceObservationProgramCodec.policy(actualPolicy)))
            throw new IllegalArgumentException("Selected frontier belongs to another execution environment or fixed policy");
    }
    private Map<String, Object> identityValue() {
        Map<String, Object> value = new TreeMap<>();
        value.put("selection", selection.identity()); value.put("successfulOperation", successfulOperationIdentity);
        value.put("terminalOperation", terminalOperationIdentity); value.put(BlueLanguageConstants.OBJECT_BLUE_ID, selectedView.blueId()); value.put("epoch", successfulEpoch);
        value.put("productionOrder", productionOrder == null ? null : productionOrder.components());
        value.put("environment", SourceObservationProgramCodec.environment(environment)); value.put("policy", SourceObservationProgramCodec.policy(executionPolicy));
        return value;
    }

    static void validateConstructor(Map<String, Object> value) {
        for (String key : Arrays.asList("selection", "successfulOperation", "terminalOperation"))
            ClosureValueSupport.requireSha256Identity((String) value.get(key), key);
        ClosureValueSupport.requireBlueId((String) value.get(BlueLanguageConstants.OBJECT_BLUE_ID), "selected frontier BlueId");
        Object epoch = value.get("epoch");
        if (!(epoch instanceof Number)) throw new IllegalArgumentException("Expected exact source epoch");
        java.math.BigInteger integer;
        try { integer = new java.math.BigInteger(epoch.toString()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Expected integer source epoch", invalid); }
        ClosureValueSupport.requireSafeInteger(integer.longValueExact(), "source epoch");
        Object order = value.get("productionOrder");
        if (order != null) {
            if (!(order instanceof List)) throw new IllegalArgumentException("Expected exact production order");
            SameOriginAttachmentPolicy.requireFrontier(ExternalOrderKey.of((List<?>) order));
        }
        if (!(value.get("environment") instanceof Map) || !(value.get("policy") instanceof Map))
            throw new IllegalArgumentException("Expected complete execution identities");
    }
}
