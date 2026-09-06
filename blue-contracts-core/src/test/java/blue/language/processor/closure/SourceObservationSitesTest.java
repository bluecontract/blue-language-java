package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SourceObservationSitesTest {
    @Test
    void terminationCompletionBindsItsOriginalRequestInAClosedIndependentDomain() {
        ClosureIdentityService ids = ClosureIdentityService.INSTANCE;
        String work = "sha256:" + repeat('a');
        String site = ids.observationTerminationCompletionSiteIdentity(work);
        String canonical = "{\"domain\":\"blue-source-observation-termination-completion-poc/1\",\"value\":{\"requestWorkIdentity\":\""
                + work + "\"}}";
        assertEquals("sha256:" + FrozenNodeEvidenceCodec.digest(canonical.getBytes(StandardCharsets.UTF_8)), site);
        assertNotEquals(site, ids.observationEntrySiteIdentity(work));
        assertNotEquals(site, ids.observationInitializationCompletionSiteIdentity(work));
        assertNotEquals(site, ids.observationTerminationCompletionSiteIdentity("sha256:" + repeat('b')));
        assertThrows(IllegalArgumentException.class, () -> ids.observationTerminationCompletionSiteIdentity("worker-1"));
        assertThrows(IllegalArgumentException.class, () -> ids.observationTerminationCompletionSiteIdentity(null));
        Map<String, Object> open = new LinkedHashMap<>();
        open.put("requestWorkIdentity", work); open.put("workerOrdinal", 1L);
        assertThrows(IllegalArgumentException.class,
                () -> ids.identity(ClosureIdentityService.Constructor.SOURCE_OBSERVATION_TERMINATION_COMPLETION, open));
    }

    @Test
    void initializationObservationBindsTheActualCreatorAndOccurrenceButPreservesItsOriginalSourceSite() {
        ClosureIdentityService ids = ClosureIdentityService.INSTANCE;
        String source = "sha256:" + repeat('a'), seed = "sha256:" + repeat('b');
        String creator = "sha256:" + repeat('c'), occurrence = "sha256:" + repeat('d');
        String observed = ids.observationInitializationPlacementSiteIdentity(source, seed, creator, occurrence);
        String canonical = "{\"domain\":\"blue-source-observation-initialization-placement-poc/1\",\"value\":{\"creatorPatchSite\":\""
                + creator + "\",\"creatorSeedIdentity\":\"" + seed + "\",\"occurrenceIdentity\":\"" + occurrence
                + "\",\"originalSourceSiteIdentity\":\"" + source + "\"}}";
        assertEquals("sha256:" + FrozenNodeEvidenceCodec.digest(canonical.getBytes(StandardCharsets.UTF_8)), observed);
        assertEquals(observed, ids.observationInitializationPlacementSiteIdentity(source, seed, creator, occurrence));
        assertNotEquals(source, observed);
        assertNotEquals(ids.observationPlacementSiteIdentity(source, Collections.singletonList(occurrence)), observed);
        assertNotEquals(observed, ids.observationInitializationPlacementSiteIdentity(seed, seed, creator, occurrence));
        assertNotEquals(observed, ids.observationInitializationPlacementSiteIdentity(source, source, creator, occurrence));
        assertNotEquals(observed, ids.observationInitializationPlacementSiteIdentity(source, seed, source, occurrence));
        assertNotEquals(observed, ids.observationInitializationPlacementSiteIdentity(source, seed, creator, source));
    }

    @Test
    void initializationObservationHasClosedFieldsAndRejectsHostOrMalformedCoordinates() {
        ClosureIdentityService ids = ClosureIdentityService.INSTANCE;
        String exact = "sha256:" + repeat('a');
        for (String invalid : Arrays.asList(null, "", "worker-7", "sha256:" + repeat('A'))) {
            assertThrows(IllegalArgumentException.class, () -> ids.observationInitializationPlacementSiteIdentity(invalid, exact, exact, exact));
            assertThrows(IllegalArgumentException.class, () -> ids.observationInitializationPlacementSiteIdentity(exact, invalid, exact, exact));
            assertThrows(IllegalArgumentException.class, () -> ids.observationInitializationPlacementSiteIdentity(exact, exact, invalid, exact));
            assertThrows(IllegalArgumentException.class, () -> ids.observationInitializationPlacementSiteIdentity(exact, exact, exact, invalid));
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("originalSourceSiteIdentity", exact); value.put("creatorSeedIdentity", exact);
        value.put("creatorPatchSite", exact); value.put("occurrenceIdentity", exact);
        value.put("workerSequence", 1L);
        assertThrows(IllegalArgumentException.class, () -> ids.identity(ClosureIdentityService.Constructor.SOURCE_OBSERVATION_INITIALIZATION_PLACEMENT, value));
        value.remove("workerSequence"); value.remove("occurrenceIdentity");
        assertThrows(IllegalArgumentException.class, () -> ids.identity(ClosureIdentityService.Constructor.SOURCE_OBSERVATION_INITIALIZATION_PLACEMENT, value));
    }

    @Test
    void sourceSitesAreDomainSeparatedAndPatchOrdinalsStartAtOne() {
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        String work = "sha256:" + repeat('a');
        String entry = identities.observationEntrySiteIdentity(work);
        assertEquals(entry, identities.observationEntrySiteIdentity(work));
        assertNotEquals(work, entry);
        assertNotEquals(entry, identities.observationPatchSiteIdentity(entry, 1));
        assertNotEquals(identities.observationPatchSiteIdentity(entry, 1), identities.observationPatchSiteIdentity(entry, 2));
        assertThrows(IllegalArgumentException.class, () -> identities.observationPatchSiteIdentity(entry, 0));
        assertThrows(IllegalArgumentException.class, () -> identities.observationEntrySiteIdentity("worker-row"));
    }

    @Test
    void initializationCompletionBindsOnlyItsOriginalInvocationInAClosedSeparateDomain() {
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        String invocation = "sha256:" + repeat('a');
        String completion = identities.observationInitializationCompletionSiteIdentity(invocation);
        String canonical = "{\"domain\":\"blue-source-observation-initialization-completion-poc/1\",\"value\":{\"sourceInitializationInvocationIdentity\":\""
                + invocation + "\"}}";
        assertEquals("sha256:" + FrozenNodeEvidenceCodec.digest(canonical.getBytes(StandardCharsets.UTF_8)), completion);
        assertEquals(completion, identities.observationInitializationCompletionSiteIdentity(invocation));
        assertNotEquals(completion, identities.observationInitializationCompletionSiteIdentity("sha256:" + repeat('b')));
        assertNotEquals(invocation, completion);
        assertNotEquals(identities.observationEntrySiteIdentity(invocation), completion);
        assertNotEquals(identities.observationPatchSiteIdentity(invocation, 1L), completion);
        assertThrows(IllegalArgumentException.class, () -> identities.observationInitializationCompletionSiteIdentity(null));
        assertThrows(IllegalArgumentException.class, () -> identities.observationInitializationCompletionSiteIdentity("worker-17"));
        assertThrows(IllegalArgumentException.class, () -> identities.observationInitializationCompletionSiteIdentity("sha256:" + repeat('A')));
        Map<String, Object> open = new LinkedHashMap<String, Object>();
        open.put("sourceInitializationInvocationIdentity", invocation);
        open.put("consumerInvocationIdentity", "sha256:" + repeat('b'));
        assertThrows(IllegalArgumentException.class, () -> identities.identity(
                ClosureIdentityService.Constructor.SOURCE_OBSERVATION_INITIALIZATION_COMPLETION, open));
    }

    @Test
    void placementSiteBindsOriginalSiteAndOrderedRouteWithoutSorting() throws Exception {
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        String original = "sha256:" + repeat('a');
        String first = "sha256:" + repeat('b');
        String second = "sha256:" + repeat('c');
        String placed = identities.observationPlacementSiteIdentity(original, Arrays.asList(first, second));
        String canonical = "{\"domain\":\"blue-source-observation-placement-poc/1\",\"value\":{\"orderedOccurrenceIdentities\":[\""
                + first + "\",\"" + second + "\"],\"originalSiteIdentity\":\"" + original + "\"}}";
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder expected = new StringBuilder("sha256:");
        for (byte value : digest) expected.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        assertEquals(expected.toString(), placed, "Closed preimage has an independently written canonical encoding");
        assertNotEquals(original, placed);
        assertNotEquals(identities.observationEntrySiteIdentity(original), placed);
        assertNotEquals(identities.observationPatchSiteIdentity(original, 1), placed);
        assertNotEquals(placed, identities.observationPlacementSiteIdentity(original, Arrays.asList(second, first)));
        assertNotEquals(placed, identities.observationPlacementSiteIdentity(original, Collections.singletonList(first)));
        assertNotEquals(placed, identities.observationPlacementSiteIdentity(second, Arrays.asList(first, second)));
        assertEquals(placed, identities.observationPlacementSiteIdentity(original, Arrays.asList(first, second)));
    }

    @Test
    void authoredAliasesAndActivationGenerationsRemainDistinctAtOnePatch() {
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        String site = identities.observationPatchSiteIdentity(identities.observationEntrySiteIdentity("sha256:" + repeat('a')), 1);
        String policy = "sha256:" + repeat('b');
        DocumentId parent = new DocumentId("parent"), child = new DocumentId("child");
        String left = ManagedOccurrenceBinding.derived(policy, parent, ScopeAddress.embedded("/left", 1), child, "child-state", true, null).occurrenceIdentity();
        String right = ManagedOccurrenceBinding.derived(policy, parent, ScopeAddress.embedded("/right", 1), child, "child-state", true, null).occurrenceIdentity();
        String nextActivation = ManagedOccurrenceBinding.derived(policy, parent, ScopeAddress.embedded("/left", 2), child, "child-state", true, null).occurrenceIdentity();
        assertNotEquals(identities.observationPlacementSiteIdentity(site, Collections.singletonList(left)),
                identities.observationPlacementSiteIdentity(site, Collections.singletonList(right)));
        assertNotEquals(identities.observationPlacementSiteIdentity(site, Collections.singletonList(left)),
                identities.observationPlacementSiteIdentity(site, Collections.singletonList(nextActivation)));
    }

    @Test
    void placementRejectsEmptyRepeatedOrNoncanonicalRouteAndOpenConstructorFields() {
        ClosureIdentityService identities = ClosureIdentityService.INSTANCE;
        String original = "sha256:" + repeat('a'), occurrence = "sha256:" + repeat('b');
        assertThrows(IllegalArgumentException.class, () -> identities.observationPlacementSiteIdentity("worker-17", Collections.singletonList(occurrence)));
        assertThrows(IllegalArgumentException.class, () -> identities.observationPlacementSiteIdentity(null, Collections.singletonList(occurrence)));
        assertThrows(IllegalArgumentException.class, () -> identities.observationPlacementSiteIdentity(original, null));
        assertThrows(IllegalArgumentException.class, () -> identities.observationPlacementSiteIdentity(original, Collections.<String>emptyList()));
        assertThrows(IllegalArgumentException.class, () -> identities.observationPlacementSiteIdentity(original, Arrays.asList(occurrence, occurrence)));
        assertThrows(IllegalArgumentException.class, () -> identities.observationPlacementSiteIdentity(original, Collections.singletonList("sha256:" + repeat('B'))));
        assertThrows(IllegalArgumentException.class, () -> identities.observationPlacementSiteIdentity(original, Collections.singletonList("page-1")));
        assertThrows(IllegalArgumentException.class, () -> identities.observationPlacementSiteIdentity(original, Collections.<String>singletonList(null)));
        Map<String, Object> open = new LinkedHashMap<String, Object>();
        open.put("originalSiteIdentity", original);
        open.put("orderedOccurrenceIdentities", Collections.singletonList(occurrence));
        open.put("workerSequence", 1L);
        assertThrows(IllegalArgumentException.class, () -> identities.identity(ClosureIdentityService.Constructor.SOURCE_OBSERVATION_PLACEMENT, open));
    }

    @Test
    void projectionRequiresOrderedPureReferenceChanges() {
        FrozenNode before = FrozenNode.fromResolvedNode(new Node().blueId("before"));
        FrozenNode after = FrozenNode.fromResolvedNode(new Node().blueId("after"));
        assertThrows(IllegalArgumentException.class, () -> new SourceObservationProgram.ReferenceChange("/", before, after));
        assertThrows(IllegalArgumentException.class, () -> new SourceObservationProgram.ReferenceChange("/child", before, before));
        assertThrows(IllegalArgumentException.class, () -> new SourceObservationProgram.ReferenceChange("/child", before,
                FrozenNode.fromResolvedNode(new Node().value(1))));
        assertThrows(IllegalArgumentException.class, () -> new SourceObservationProgram.ReferenceProjection(
                "sha256:" + repeat('b'), new DocumentId("source"), new DocumentId("parent"), "before", "after", before, after,
                Arrays.asList(new SourceObservationProgram.ReferenceChange("/z", before, after),
                        new SourceObservationProgram.ReferenceChange("/a", before, after))));
    }

    private static String repeat(char value) { char[] chars = new char[64]; Arrays.fill(chars, value); return new String(chars); }
}
