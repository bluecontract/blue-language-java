package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An actual FULL_HISTORY placement that synchronously reached canonical init0.
 * The runtime, not a host-selected head or a self-hash, establishes the creation
 * site's authority. The completed owning creator program retains this record;
 * its final operation identity is attached only when that operation settles.
 */
public final class AcceptedInitializationInstallation {
    private final SameOriginAttachmentPolicy.Selection selection;
    private final String creatorSeedIdentity, creatorPatchSite, sourceInitializationOperationIdentity;
    private final String sourceCompletionSiteIdentity, identity;
    private final ManagedReadPin selectedView;

    private AcceptedInitializationInstallation(SameOriginAttachmentPolicy.Selection selection, String creatorSeedIdentity,
            String creatorPatchSite, String sourceInitializationOperationIdentity, String sourceCompletionSiteIdentity,
            ManagedReadPin selectedView) {
        this.selection = Objects.requireNonNull(selection, "selection");
        if (selection.mode() != SameOriginAttachmentPolicy.Mode.FULL_HISTORY)
            throw new IllegalArgumentException("Canonical initialization installation requires FULL_HISTORY");
        this.creatorSeedIdentity = ClosureValueSupport.requireSha256Identity(creatorSeedIdentity, "creatorSeedIdentity");
        this.creatorPatchSite = ClosureValueSupport.requireSha256Identity(creatorPatchSite, "creatorPatchSite");
        this.sourceInitializationOperationIdentity = ClosureValueSupport.requireSha256Identity(sourceInitializationOperationIdentity, "sourceInitializationOperationIdentity");
        this.sourceCompletionSiteIdentity = ClosureValueSupport.requireSha256Identity(sourceCompletionSiteIdentity, "sourceCompletionSiteIdentity");
        if (!ClosureIdentityService.INSTANCE.observationInitializationCompletionSiteIdentity(sourceInitializationOperationIdentity).equals(sourceCompletionSiteIdentity))
            throw new IllegalArgumentException("Initialization completion site belongs to another producing operation");
        this.selectedView = Objects.requireNonNull(selectedView, "selectedView");
        if (!selection.targetLineage().equals(selectedView.documentId()))
            throw new IllegalArgumentException("Selected init0 belongs to another source lineage");
        identity = ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.ACCEPTED_INITIALIZATION_INSTALLATION, identityValue());
    }

    /** Session-only mint after authenticated initialization reached this exact occurrence's real creation site. */
    static AcceptedInitializationInstallation fromCanonicalSite(SameOriginAttachmentPolicy.Selection selection, String creatorSeedIdentity,
            String creatorPatchSite, String sourceInitializationOperationIdentity, String sourceCompletionSiteIdentity,
            ManagedReadPin selectedView) {
        return new AcceptedInitializationInstallation(selection, creatorSeedIdentity, creatorPatchSite,
                sourceInitializationOperationIdentity, sourceCompletionSiteIdentity, selectedView);
    }

    public SameOriginAttachmentPolicy.Selection selection() { return selection; }
    public String creatorSeedIdentity() { return creatorSeedIdentity; }
    public String creatorPatchSite() { return creatorPatchSite; }
    public String sourceInitializationOperationIdentity() { return sourceInitializationOperationIdentity; }
    public String sourceCompletionSiteIdentity() { return sourceCompletionSiteIdentity; }
    public ManagedReadPin selectedView() { return selectedView; }
    public String identity() { return identity; }

    /** Cross-checks the exact already authenticated successful source initialization, not its physical current head. */
    public void verifySourceInitialization(SourceInitialization initialization) {
        SourceObservationProgram source = Objects.requireNonNull(initialization, "initialization").program();
        if (!source.invocationIdentity().equals(sourceInitializationOperationIdentity)
                || !source.ownedDocumentIds().contains(selection.targetLineage()))
            throw new IllegalArgumentException("Installation refers to another source initialization");
        SourceObservationProgram.SourceState installed = null;
        for (SourceObservationProgram.SourceState result : source.sourceResults())
            if (result.documentId().equals(selection.targetLineage())) installed = result;
        if (installed == null || !installed.initialized() || installed.epoch() != 0
                || !installed.blueId().equals(selectedView.blueId()))
            throw new IllegalArgumentException("Installation did not select the canonical successful init0 view");
    }

    private Map<String, Object> identityValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("selectionIdentity", selection.identity()); value.put("creatorSeedIdentity", creatorSeedIdentity);
        value.put("creatorPatchSite", creatorPatchSite); value.put("sourceInitializationOperationIdentity", sourceInitializationOperationIdentity);
        value.put("sourceCompletionSiteIdentity", sourceCompletionSiteIdentity); value.put("selectedBlueId", selectedView.blueId());
        return value;
    }
}
