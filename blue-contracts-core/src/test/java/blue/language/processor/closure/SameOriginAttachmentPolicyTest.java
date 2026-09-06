package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Boundary-value tests; these snapshots do not purport to prove a business execution. */
class SameOriginAttachmentPolicyTest {
    private static final DocumentId A = new DocumentId("A"), B = new DocumentId("B"), C = new DocumentId("C");
    private static final String POLICY = hash('a');

    @Test
    void policyIsCanonicalImmutableAndOwnedOnlyByItsCreator() {
        ManagedDocumentSnapshot b = document(B, "source");
        ManagedOccurrenceBinding ab = occurrence(A, "/b", B, b.blueId(), false, null);
        ManagedOccurrenceBinding cb = occurrence(C, "/b", B, b.blueId(), false, null);
        SameOriginAttachmentPolicy.Selection first = selection(ab, b.blueId(), SameOriginAttachmentPolicy.Mode.FROM_NOW);
        SameOriginAttachmentPolicy.Selection second = selection(cb, b.blueId(), SameOriginAttachmentPolicy.Mode.FULL_HISTORY);
        List<SameOriginAttachmentPolicy.Selection> supplied = new ArrayList<>(Arrays.asList(second, first));
        SameOriginAttachmentPolicy policy = new SameOriginAttachmentPolicy(supplied);
        supplied.clear();
        assertEquals(new SameOriginAttachmentPolicy(Arrays.asList(first, second)).entries(), policy.entries());
        assertSame(first, policy.selection(ab.occurrenceIdentity()).orElseThrow(AssertionError::new));
        assertEquals(Collections.singletonList(first), policy.ownedBy(Collections.singleton(A)).entries());
        assertEquals(Collections.singletonList(second), policy.ownedBy(Collections.singleton(C)).entries());
        assertSame(SameOriginAttachmentPolicy.empty(), policy.ownedBy(Collections.singleton(B)));
        assertFalse(policy.selection(hash('f')).isPresent());
        assertTrue(SameOriginAttachmentPolicy.empty().entries().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> policy.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.identityValue().put("host", "row-1"));
        assertThrows(IllegalArgumentException.class, () -> new SameOriginAttachmentPolicy(Arrays.asList(first, first)));
        assertThrows(IllegalArgumentException.class, () -> policy.selection("worker-row"));
    }

    @Test
    void basisAcceptsOnlyOriginalProspectiveOccurrenceAndExactKnownLineageReference() {
        ManagedDocumentSnapshot b = document(B, "current source");
        ManagedOccurrenceBinding binding = occurrence(A, "/b", B, b.blueId(), false, null);
        SameOriginAttachmentPolicy.Selection selected = selection(binding, b.blueId(), SameOriginAttachmentPolicy.Mode.FROM_NOW);
        SameOriginAttachmentPolicy policy = new SameOriginAttachmentPolicy(Collections.singleton(selected));
        assertDoesNotThrow(() -> policy.verifyBasis(snapshot(b, binding, Collections.emptyList())));

        Node oldBody = new Node().name("historical source");
        ManagedReadPin old = ManagedReadPin.fromExactEvidence(B, blueId(oldBody), oldBody, null);
        SameOriginAttachmentPolicy historicalRef = new SameOriginAttachmentPolicy(Collections.singleton(selection(binding, old.blueId(), SameOriginAttachmentPolicy.Mode.FROM_NOW)));
        assertDoesNotThrow(() -> historicalRef.verifyBasis(snapshot(b, binding, Collections.singletonList(old))));
        assertThrows(IllegalArgumentException.class, () -> historicalRef.verifyBasis(snapshot(b, binding, Collections.emptyList())));
        assertThrows(IllegalArgumentException.class, () -> policy.verifyBasis(snapshot(b,
                occurrence(A, "/other", B, b.blueId(), false, null), Collections.emptyList())));
        assertThrows(IllegalArgumentException.class, () -> policy.verifyBasis(snapshot(b,
                occurrence(A, "/b", B, b.blueId(), true, null), Collections.emptyList())));
        assertThrows(IllegalArgumentException.class, () -> policy.verifyBasis(snapshot(b,
                occurrence(A, "/b", B, b.blueId(), false, 0L), Collections.emptyList())));
    }

    @Test
    void basisRejectsForeignCreatorOrTargetEvenWhenOccurrenceHashOrContentMatches() {
        ManagedDocumentSnapshot b = document(B, "source");
        ManagedOccurrenceBinding binding = occurrence(A, "/b", B, b.blueId(), false, null);
        AffectedClosureSnapshot snapshot = snapshot(b, binding, Collections.emptyList());
        SameOriginAttachmentPolicy.Selection wrongCreator = new SameOriginAttachmentPolicy.Selection(
                SameOriginAttachmentPolicy.Mode.FROM_NOW, C, binding.occurrenceIdentity(), B, b.blueId());
        SameOriginAttachmentPolicy.Selection wrongTarget = new SameOriginAttachmentPolicy.Selection(
                SameOriginAttachmentPolicy.Mode.FROM_NOW, A, binding.occurrenceIdentity(), C, b.blueId());
        assertThrows(IllegalArgumentException.class, () -> new SameOriginAttachmentPolicy(Collections.singleton(wrongCreator)).verifyBasis(snapshot));
        assertThrows(IllegalArgumentException.class, () -> new SameOriginAttachmentPolicy(Collections.singleton(wrongTarget)).verifyBasis(snapshot));
        Node oldBody = new Node().name("historical source");
        ManagedReadPin foreign = ManagedReadPin.fromExactEvidence(A, blueId(oldBody), oldBody, null);
        SameOriginAttachmentPolicy wrongPin = new SameOriginAttachmentPolicy(Collections.singleton(selection(binding, foreign.blueId(), SameOriginAttachmentPolicy.Mode.FROM_NOW)));
        assertThrows(IllegalArgumentException.class, () -> wrongPin.verifyBasis(snapshot(b, binding, Collections.singletonList(foreign))));
    }

    @Test
    void selectionIdentityHasIndependentClosedPreimageAndBindsEverySemanticField() {
        String occurrence = hash('b');
        SameOriginAttachmentPolicy.Selection selected = new SameOriginAttachmentPolicy.Selection(
                SameOriginAttachmentPolicy.Mode.FROM_NOW, A, occurrence, B, "exact-source");
        String canonical = "{\"domain\":\"blue-same-origin-attachment-selection-poc/1\",\"value\":{\"creatorLineage\":\"A\",\"frontier\":null,\"mode\":\"FROM_NOW\",\"occurrenceIdentity\":\""
                + occurrence + "\",\"suppliedExactRefBlueId\":\"exact-source\",\"targetLineage\":\"B\"}}";
        assertEquals("sha256:" + FrozenNodeEvidenceCodec.digest(canonical.getBytes(StandardCharsets.UTF_8)), selected.identity());
        assertNotEquals(selected.identity(), new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FULL_HISTORY, A, occurrence, B, "exact-source").identity());
        assertNotEquals(selected.identity(), new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, C, occurrence, B, "exact-source").identity());
        assertNotEquals(selected.identity(), new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, A, hash('c'), B, "exact-source").identity());
        assertNotEquals(selected.identity(), new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, A, occurrence, C, "exact-source").identity());
        assertNotEquals(selected.identity(), new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, A, occurrence, B, "another-source-view").identity());
        Map<String, Object> fields = new LinkedHashMap<>(selected.identityValue());
        fields.put("hostSequence", 1L);
        assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_ATTACHMENT_SELECTION, fields));
        fields.remove("hostSequence"); fields.put("mode", "FROM_FRONTIER");
        assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_ATTACHMENT_SELECTION, fields));
        fields.put("mode", "from_now");
        assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_ATTACHMENT_SELECTION, fields));
        assertThrows(IllegalArgumentException.class, () -> new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW, A, "row-17", B, "source"));
    }

    private static SameOriginAttachmentPolicy.Selection selection(ManagedOccurrenceBinding binding, String supplied, SameOriginAttachmentPolicy.Mode mode) {
        return new SameOriginAttachmentPolicy.Selection(mode, binding.sourceDocumentId(), binding.occurrenceIdentity(), binding.targetDocumentId(), supplied);
    }
    private static ManagedOccurrenceBinding occurrence(DocumentId creator, String path, DocumentId target, String blueId, boolean active, Long historical) {
        return ManagedOccurrenceBinding.derived(POLICY, creator, ScopeAddress.embedded(path, 1), target, blueId, active, historical);
    }
    private static ManagedDocumentSnapshot document(DocumentId id, String name) {
        Node body = new Node().name(name);
        return new ManagedDocumentSnapshot(id, blueId(body), body, true, false, true, 0, 0);
    }
    private static AffectedClosureSnapshot snapshot(ManagedDocumentSnapshot b, ManagedOccurrenceBinding binding, List<ManagedReadPin> pins) {
        ManagedDocumentSnapshot a = document(A, "creator");
        return ClosureEvidenceFactory.affectedClosure(0, Arrays.asList(a, b), Collections.singletonList(binding),
                Arrays.asList(ClosureEvidenceFactory.acyclicComponent(a), ClosureEvidenceFactory.acyclicComponent(b)), Arrays.asList(A, B), pins);
    }
    private static String blueId(Node body) { return DirectBlueIdCalculator.calculateBlueId(body); }
    private static String hash(char value) { char[] chars = new char[64]; Arrays.fill(chars, value); return "sha256:" + new String(chars); }
}
