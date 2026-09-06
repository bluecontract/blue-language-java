package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact observer view accepted at an original canonical creation boundary.
 * There is deliberately no physical generation, host head, or invented committed
 * epoch: a source may be at an intermediate site of its still-tentative operation.
 *
 * Construction is owned by the canonical Session at that site, or by restoration
 * of its authenticated retained program. Field validation and a self-computed hash
 * do not establish execution, policy authorization, or canonical freshness.
 */
public final class AcceptedAttachmentView {
    private final SameOriginAttachmentPolicy.Selection selection;
    private final String creatorSeedIdentity, creatorPatchSite, sourceSeedIdentity, sourceSiteIdentity, identity;
    private final ManagedReadPin selectedView;
    private final SourceFrontierView frontierView;

    private AcceptedAttachmentView(SameOriginAttachmentPolicy.Selection selection, String creatorSeedIdentity,
            String creatorPatchSite, String sourceSeedIdentity, String sourceSiteIdentity, ManagedReadPin selectedView) {
        this(selection, creatorSeedIdentity, creatorPatchSite, sourceSeedIdentity, sourceSiteIdentity, selectedView, null);
    }

    private AcceptedAttachmentView(SameOriginAttachmentPolicy.Selection selection, String creatorSeedIdentity,
            String creatorPatchSite, String sourceSeedIdentity, String sourceSiteIdentity, ManagedReadPin selectedView, SourceFrontierView frontierView) {
        this.selection = Objects.requireNonNull(selection, "selection");
        if (selection.mode() != SameOriginAttachmentPolicy.Mode.FROM_NOW && selection.mode() != SameOriginAttachmentPolicy.Mode.FROM_FRONTIER)
            throw new IllegalArgumentException("Historical attachment requires its own initialization and history selection path");
        this.creatorSeedIdentity = ClosureValueSupport.requireSha256Identity(creatorSeedIdentity, "creatorSeedIdentity");
        this.creatorPatchSite = ClosureValueSupport.requireSha256Identity(creatorPatchSite, "creatorPatchSite");
        if ((selection.mode() == SameOriginAttachmentPolicy.Mode.FROM_FRONTIER) != (frontierView != null))
            throw new IllegalArgumentException("Frontier installation requires its exact selected receipt evidence");
        if (frontierView != null && (!frontierView.selection().identity().equals(selection.identity()) || sourceSeedIdentity != null || sourceSiteIdentity != null))
            throw new IllegalArgumentException("Frontier evidence differs from the frozen selection or fabricates a live source site");
        this.frontierView = frontierView;
        this.sourceSeedIdentity = frontierView == null ? ClosureValueSupport.requireSha256Identity(sourceSeedIdentity, "sourceSeedIdentity") : null;
        this.sourceSiteIdentity = frontierView == null ? ClosureValueSupport.requireSha256Identity(sourceSiteIdentity, "sourceSiteIdentity") : null;
        this.selectedView = Objects.requireNonNull(selectedView, "selectedView");
        if (!selection.targetLineage().equals(selectedView.documentId()))
            throw new IllegalArgumentException("Accepted view belongs to another target lineage");
        identity = ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.ACCEPTED_ATTACHMENT_VIEW, identityValue());
    }

    /** Session-only mint; its caller proves the frozen declaration and the actual canonical source site. */
    static AcceptedAttachmentView fromCanonicalSite(SameOriginAttachmentPolicy.Selection selection, String creatorSeedIdentity,
            String creatorPatchSite, String sourceSeedIdentity, String sourceSiteIdentity, ManagedReadPin selectedView) {
        return new AcceptedAttachmentView(selection, creatorSeedIdentity, creatorPatchSite, sourceSeedIdentity, sourceSiteIdentity, selectedView);
    }

    /** Session-only mint after the actual creator installs a separately selected receipt-backed frontier pin. */
    static AcceptedAttachmentView fromFrontierSite(SameOriginAttachmentPolicy.Selection selection, String creatorSeedIdentity,
            String creatorPatchSite, SourceFrontierView selected) {
        return new AcceptedAttachmentView(selection, creatorSeedIdentity, creatorPatchSite, null, null, selected.selectedView(), selected);
    }

    public SameOriginAttachmentPolicy.Selection selection() { return selection; }
    public String creatorSeedIdentity() { return creatorSeedIdentity; }
    public String creatorPatchSite() { return creatorPatchSite; }
    public String sourceSeedIdentity() { return sourceSeedIdentity; }
    public String sourceSiteIdentity() { return sourceSiteIdentity; }
    public ManagedReadPin selectedView() { return selectedView; }
    public String identity() { return identity; }
    public Optional<SourceFrontierView> frontierView() { return Optional.ofNullable(frontierView); }

    private Map<String, Object> identityValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("selectionIdentity", selection.identity());
        value.put("creatorSeedIdentity", creatorSeedIdentity); value.put("creatorPatchSite", creatorPatchSite);
        value.put("sourceSeedIdentity", sourceSeedIdentity); value.put("sourceSiteIdentity", sourceSiteIdentity);
        value.put("selectedBlueId", selectedView.blueId());
        value.put("frontierViewIdentity", frontierView == null ? null : frontierView.identity());
        return value;
    }
}
