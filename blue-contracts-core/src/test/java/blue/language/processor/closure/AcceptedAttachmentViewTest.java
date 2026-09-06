package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Exact capability shape tests; only the owning Session can attest a canonical activation site. */
class AcceptedAttachmentViewTest {
    private static final DocumentId CREATOR = new DocumentId("creator"), SOURCE = new DocumentId("source");

    @Test
    void acceptedViewBindsSelectionOriginalSeedsAndBothSitesEvenWhenTheBodyIsUnchanged() {
        ManagedReadPin view = pin(SOURCE, new Node().name("selected source"));
        SameOriginAttachmentPolicy.Selection selection = selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, "old-exact-reference");
        AcceptedAttachmentView accepted = accepted(selection, view, hash('b'), hash('c'), hash('d'), hash('e'));
        assertSame(selection, accepted.selection()); assertSame(view, accepted.selectedView());
        assertEquals(hash('b'), accepted.creatorSeedIdentity()); assertEquals(hash('c'), accepted.creatorPatchSite());
        assertEquals(hash('d'), accepted.sourceSeedIdentity()); assertEquals(hash('e'), accepted.sourceSiteIdentity());
        String canonical = "{\"domain\":\"blue-accepted-attachment-view-poc/1\",\"value\":{\"creatorPatchSite\":\"" + hash('c')
                + "\",\"creatorSeedIdentity\":\"" + hash('b') + "\",\"frontierViewIdentity\":null,\"selectedBlueId\":\"" + view.blueId()
                + "\",\"selectionIdentity\":\"" + selection.identity() + "\",\"sourceSeedIdentity\":\"" + hash('d')
                + "\",\"sourceSiteIdentity\":\"" + hash('e') + "\"}}";
        assertEquals("sha256:" + FrozenNodeEvidenceCodec.digest(canonical.getBytes(StandardCharsets.UTF_8)), accepted.identity());
        assertEquals(accepted.identity(), accepted(selection, view, hash('b'), hash('c'), hash('d'), hash('e')).identity());
        assertNotEquals(accepted.identity(), accepted(selection, view, hash('f'), hash('c'), hash('d'), hash('e')).identity());
        assertNotEquals(accepted.identity(), accepted(selection, view, hash('b'), hash('f'), hash('d'), hash('e')).identity());
        assertNotEquals(accepted.identity(), accepted(selection, view, hash('b'), hash('c'), hash('f'), hash('e')).identity());
        assertNotEquals(accepted.identity(), accepted(selection, view, hash('b'), hash('c'), hash('d'), hash('f')).identity(),
                "Equal bodies at distinct source prefixes are not interchangeable activation authority");
        assertNotEquals(accepted.identity(), accepted(selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, "another-old-reference"), view,
                hash('b'), hash('c'), hash('d'), hash('e')).identity());
        assertNotEquals(accepted.identity(), accepted(selection, pin(SOURCE, new Node().name("another selected view")),
                hash('b'), hash('c'), hash('d'), hash('e')).identity());
    }

    @Test
    void exactCyclicViewRetainsItsProofAndCannotBeSubstitutedOrMutated() {
        Node placeholder = new Node().name("cyclic selected view").properties("self", new Node().blueId("this#0"));
        CyclicSetFinalization finalized = new CircularSetIdentityCalculator().finalizeCyclicSet(Collections.singletonList(placeholder));
        String blueId = finalized.membersInCanonicalOrder().get(0).finalBlueId();
        Node body = finalized.membersInCanonicalOrder().get(0).canonicalMemberBody();
        body.getAsNode("/self").blueId(blueId);
        ManagedReadPin selected = ManagedReadPin.fromExactEvidence(SOURCE, blueId, body,
                CyclicSetProof.fromDeclaredPlaceholderSet(finalized.canonicalMemberBodies()));
        AcceptedAttachmentView accepted = accepted(selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, "old-exact"), selected,
                hash('b'), hash('c'), hash('d'), hash('e'));
        assertTrue(accepted.selectedView().cyclicProof().isPresent());
        assertEquals(blueId, accepted.selectedView().blueId());
        body.name("mutated caller body"); accepted.selectedView().document().name("mutated accessor body");
        assertEquals("cyclic selected view", accepted.selectedView().document().getName());
        assertThrows(IllegalArgumentException.class, () -> ManagedReadPin.fromExactEvidence(SOURCE, blueId,
                new Node().name("unrelated body"), selected.cyclicProof().orElseThrow(AssertionError::new)));
        assertThrows(IllegalArgumentException.class, () -> ManagedReadPin.fromExactEvidence(SOURCE, blueId,
                selected.document(), null));
    }

    @Test
    void mintRejectsHistoricalSelectionForeignLineageAndUnstructuredSites() {
        ManagedReadPin view = pin(SOURCE, new Node().name("selected source"));
        assertThrows(IllegalArgumentException.class, () -> accepted(selection(SameOriginAttachmentPolicy.Mode.FULL_HISTORY, "old-exact"),
                view, hash('b'), hash('c'), hash('d'), hash('e')));
        SameOriginAttachmentPolicy.Selection selection = selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, "old-exact");
        assertThrows(IllegalArgumentException.class, () -> accepted(selection, pin(CREATOR, view.document()), hash('b'), hash('c'), hash('d'), hash('e')));
        assertThrows(IllegalArgumentException.class, () -> accepted(selection, view, "worker", hash('c'), hash('d'), hash('e')));
        assertThrows(IllegalArgumentException.class, () -> accepted(selection, view, hash('b'), "page", hash('d'), hash('e')));
        assertThrows(IllegalArgumentException.class, () -> accepted(selection, view, hash('b'), hash('c'), "epoch-1", hash('e')));
        assertThrows(IllegalArgumentException.class, () -> accepted(selection, view, hash('b'), hash('c'), hash('d'), "now"));
    }

    @Test
    void acceptedViewConstructorIsClosedAndCannotHashAnExtraHostEpoch() {
        SameOriginAttachmentPolicy.Selection selection = selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, "old-exact");
        ManagedReadPin view = pin(SOURCE, new Node().name("selected source"));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("selectionIdentity", selection.identity()); fields.put("creatorSeedIdentity", hash('b'));
        fields.put("creatorPatchSite", hash('c')); fields.put("sourceSeedIdentity", hash('d'));
        fields.put("sourceSiteIdentity", hash('e')); fields.put("selectedBlueId", view.blueId());
        fields.put("frontierViewIdentity", null);
        assertEquals(accepted(selection, view, hash('b'), hash('c'), hash('d'), hash('e')).identity(),
                ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.ACCEPTED_ATTACHMENT_VIEW, fields));
        fields.put("committedEpoch", 1L);
        assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.ACCEPTED_ATTACHMENT_VIEW, fields));
        fields.remove("committedEpoch"); fields.remove("sourceSiteIdentity");
        assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.ACCEPTED_ATTACHMENT_VIEW, fields));
    }

    @Test
    void predecessorSiteIsSeedLocalClosedAndNotAnEntryOrPatch() {
        ClosureIdentityService ids = ClosureIdentityService.INSTANCE;
        String seed = hash('b'), predecessor = ids.observationSourcePredecessorSiteIdentity(seed);
        String canonical = "{\"domain\":\"blue-source-observation-predecessor-poc/1\",\"value\":{\"executionSeedIdentity\":\"" + seed + "\"}}";
        assertEquals("sha256:" + FrozenNodeEvidenceCodec.digest(canonical.getBytes(StandardCharsets.UTF_8)), predecessor);
        assertEquals(predecessor, ids.observationSourcePredecessorSiteIdentity(seed));
        assertNotEquals(predecessor, ids.observationSourcePredecessorSiteIdentity(hash('c')));
        assertNotEquals(predecessor, ids.observationEntrySiteIdentity(seed));
        assertNotEquals(predecessor, ids.observationPatchSiteIdentity(seed, 1));
        assertThrows(IllegalArgumentException.class, () -> ids.observationSourcePredecessorSiteIdentity("physical-head"));
        Map<String, Object> fields = new LinkedHashMap<>(); fields.put("executionSeedIdentity", seed); fields.put("epoch", 1L);
        assertThrows(IllegalArgumentException.class, () -> ids.identity(ClosureIdentityService.Constructor.SOURCE_OBSERVATION_PREDECESSOR, fields));
    }

    private static SameOriginAttachmentPolicy.Selection selection(SameOriginAttachmentPolicy.Mode mode, String suppliedRef) {
        return new SameOriginAttachmentPolicy.Selection(mode, CREATOR, hash('a'), SOURCE, suppliedRef);
    }
    private static AcceptedAttachmentView accepted(SameOriginAttachmentPolicy.Selection policy, ManagedReadPin view,
            String creatorSeed, String creatorSite, String sourceSeed, String sourceSite) {
        return AcceptedAttachmentView.fromCanonicalSite(policy, creatorSeed, creatorSite, sourceSeed, sourceSite, view);
    }
    private static ManagedReadPin pin(DocumentId id, Node body) {
        return ManagedReadPin.fromExactEvidence(id, DirectBlueIdCalculator.calculateBlueId(body), body, null);
    }
    private static String hash(char value) { char[] chars = new char[64]; Arrays.fill(chars, value); return "sha256:" + new String(chars); }
}
