package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Transport-only package-private fixtures: these programs do not attest business execution or
 * admission semantics. The real admission/interpreter tests establish that authority separately.
 */
class SourceObservationProgramDagCodecTest {
    private static final Limits LIMITS = new Limits(1024 * 1024, 32L * 1024 * 1024, 256, 10000);
    private static final ClosureEnvironment ENVIRONMENT = new ClosureEnvironment(
            hash(1000), hash(1001), hash(1002), hash(1003), label(1004), label(1005), label(1006), label(1007),
            new ClosureEnvironment.PortableLimitPolicyEvidence(hash(1008), "transport-limits", Collections.emptyMap()),
            hash(1009), hash(1010));
    private static final ExecutionPolicy POLICY = new ExecutionPolicy(hash(1011), 100000L, Collections.emptyMap(), "transport-policy");

    @Test
    void acceptedViewRetainsItsOriginalSitesAcrossColdBorrowedProgramRoundTrip() {
        SourceObservationProgram source = program(1);
        DocumentId creator = source.ownedDocumentIds().iterator().next(), target = new DocumentId("attached-source");
        ManagedReadPin pin = exactPin(target, new Node().name("selected intermediate state"));
        SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(
                SameOriginAttachmentPolicy.Mode.FROM_NOW, creator, hash(3000), target, pin.blueId());
        AcceptedAttachmentView view = AcceptedAttachmentView.fromCanonicalSite(selection, hash(3001), hash(3002),
                hash(3003), hash(3004), pin);
        SourceObservationProgram root = program(2, withViews(source, Collections.singletonList(view)));
        Map<String, byte[]> store = new LinkedHashMap<>();
        String key = SourceObservationProgramCodec.encode(root, store::put, LIMITS);
        SourceObservationProgram restored = SourceObservationProgramCodec.decode(key, store::get, LIMITS);
        AcceptedAttachmentView cold = restored.borrowedPrograms().get(0).acceptedViews().get(0);
        assertNotSame(view, cold);
        assertEquals(view.identity(), cold.identity());
        assertEquals(view.selection().identity(), cold.selection().identity());
        assertEquals(view.creatorSeedIdentity(), cold.creatorSeedIdentity());
        assertEquals(view.creatorPatchSite(), cold.creatorPatchSite());
        assertEquals(view.sourceSeedIdentity(), cold.sourceSeedIdentity());
        assertEquals(view.sourceSiteIdentity(), cold.sourceSiteIdentity());
        assertEquals(NodeWireForm.get(pin.document()), NodeWireForm.get(cold.selectedView().document()));
        assertEquals(key, SourceObservationProgramCodec.encode(restored, (fragment, bytes) -> {}, LIMITS));
        AcceptedAttachmentView laterSameBody = AcceptedAttachmentView.fromCanonicalSite(selection, hash(3001), hash(3002),
                hash(3003), hash(3005), pin);
        String laterKey = SourceObservationProgramCodec.encode(program(2,
                withViews(source, Collections.singletonList(laterSameBody))), (fragment, bytes) -> {}, LIMITS);
        assertNotEquals(key, laterKey, "Equal bodies do not erase a different canonical source prefix");
        long exactBytes = store.values().stream().mapToLong(bytes -> bytes.length).sum();
        Limits exact = new Limits(LIMITS.fragmentBytes, exactBytes, LIMITS.depth, store.size());
        assertEquals(key, SourceObservationProgramCodec.encode(restored, (fragment, bytes) -> {}, exact));
        assertNotNull(SourceObservationProgramCodec.decode(key, store::get, exact));
        assertThrows(CapacityExceeded.class, () -> SourceObservationProgramCodec.decode(key, store::get,
                new Limits(LIMITS.fragmentBytes, exactBytes - 1, LIMITS.depth, store.size())));
    }

    @Test
    void retainedViewsRejectDuplicateSitesForeignCreatorAndTamperedIdentity() {
        SourceObservationProgram source = program(1);
        DocumentId creator = source.ownedDocumentIds().iterator().next(), target = new DocumentId("attached-source");
        ManagedReadPin pin = exactPin(target, new Node().name("selected state"));
        SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(
                SameOriginAttachmentPolicy.Mode.FROM_NOW, creator, hash(3000), target, pin.blueId());
        AcceptedAttachmentView view = AcceptedAttachmentView.fromCanonicalSite(selection, hash(3001), hash(3002),
                hash(3003), hash(3004), pin);
        assertThrows(IllegalArgumentException.class, () -> withViews(source, Arrays.asList(view, view)));
        assertThrows(IllegalArgumentException.class, () -> withViews(program(2), Collections.singletonList(view)));
        Map<String, byte[]> store = new LinkedHashMap<>();
        String key = SourceObservationProgramCodec.encode(withViews(source, Collections.singletonList(view)), store::put, LIMITS);
        ObjectNode root = (ObjectNode) json(store.get(key));
        ((ObjectNode) root.get("acceptedViews").get(0)).put("sourceSite", hash(3005));
        byte[] changed = bytes(root); String changedKey = digest(changed); store.put(changedKey, changed);
        assertThrows(InvalidExecutionEvidenceException.class,
                () -> SourceObservationProgramCodec.decode(changedKey, store::get, LIMITS));
    }

    private static SourceObservationProgram withViews(SourceObservationProgram source, List<AcceptedAttachmentView> views) {
        return new SourceObservationProgram(source.invocationIdentity(), source.causeKind(), source.causeIdentity(),
                source.externalCause(), source.environment(), source.executionPolicy(), source.sourcePredecessors(),
                source.sourceResults(), source.ownedDocumentIds(), source.steps(), source.referenceProjections(),
                source.sourceBeforeBindings(), source.sourceAfterBindings(), source.sourceBeforeComponents(),
                source.sourceAfterComponents(), source.borrowedPrograms(), source.sourceReadPins(), views);
    }

    @Test
    void coldDiamondPreservesSharedProgramObjectAndReadsEveryFragmentOnce() {
        SourceObservationProgram leaf = program(1);
        SourceObservationProgram left = program(2, leaf), right = program(3, leaf);
        SourceObservationProgram root = program(4, right, left);
        Map<String, byte[]> store = new LinkedHashMap<>();
        String rootKey = SourceObservationProgramCodec.encode(root, store::put, LIMITS);
        String leafKey = SourceObservationProgramCodec.encode(leaf, (key, value) -> { }, LIMITS);
        Map<String, Integer> reads = new HashMap<>();

        SourceObservationProgram restored = SourceObservationProgramCodec.decode(rootKey, key -> {
            reads.merge(key, 1, Integer::sum);
            return store.get(key);
        }, LIMITS);

        assertNotSame(root, restored);
        assertEquals(Arrays.asList(hash(2), hash(3)), Arrays.asList(
                restored.borrowedPrograms().get(0).invocationIdentity(), restored.borrowedPrograms().get(1).invocationIdentity()));
        SourceObservationProgram coldLeaf = restored.borrowedPrograms().get(0).borrowedPrograms().get(0);
        assertSame(coldLeaf, restored.borrowedPrograms().get(1).borrowedPrograms().get(0));
        assertNotSame(leaf, coldLeaf);
        assertEquals(hash(1), coldLeaf.invocationIdentity());
        assertEquals(Integer.valueOf(1), reads.get(leafKey));
        assertEquals(store.keySet(), reads.keySet());
        assertTrue(reads.values().stream().allMatch(count -> count == 1));
        assertEquals(rootKey, SourceObservationProgramCodec.encode(restored, (key, value) -> { }, LIMITS));
        assertEquals(rootKey, SourceObservationProgramCodec.encode(program(4, left, right), (key, value) -> { }, LIMITS),
                "Caller list order is not a second producing-program identity");
    }

    @Test
    void encodingRejectsConflictingPayloadsForOneInvocationAcrossDiamondBranches() {
        SourceObservationProgram first = program(1), conflicting = programWithValue(1, "changed body", Collections.emptyList(), Collections.emptyList());
        SourceObservationProgram root = program(4, program(2, first), program(3, conflicting));
        InvalidExecutionEvidenceException failure = assertThrows(InvalidExecutionEvidenceException.class,
                () -> SourceObservationProgramCodec.encode(root, (key, value) -> { }, LIMITS));
        assertTrue(failure.getMessage().contains("conflicting program contents"));
    }

    @Test
    void decodingRejectsSeparatelyWellHashedConflictingInvocationPayloads() {
        Map<String, byte[]> store = new LinkedHashMap<>();
        String left = SourceObservationProgramCodec.encode(program(2, program(1)), store::put, LIMITS);
        String right = SourceObservationProgramCodec.encode(program(3,
                programWithValue(1, "changed body", Collections.emptyList(), Collections.emptyList())), store::put, LIMITS);
        String root = SourceObservationProgramCodec.encode(program(4), store::put, LIMITS);
        // Independently supplied fragments can each be well hashed yet disagree on one operation.
        String conflictingRoot = withBorrowedReferences(root, Arrays.asList(left, right), store);
        InvalidExecutionEvidenceException failure = assertThrows(InvalidExecutionEvidenceException.class,
                () -> SourceObservationProgramCodec.decode(conflictingRoot, store::get, LIMITS));
        assertTrue(failure.getMessage().contains("conflicting program contents"));
    }

    @Test
    void configuredDepthAcceptsItsExactBoundaryAndRejectsTheNextBorrowedEdge() {
        Limits low = new Limits(LIMITS.fragmentBytes, LIMITS.totalBytes, 4, LIMITS.nodes);
        SourceObservationProgram atBoundary = chain(4), tooDeep = chain(5);
        Map<String, byte[]> store = new LinkedHashMap<>();
        String accepted = SourceObservationProgramCodec.encode(atBoundary, store::put, low);
        assertEquals(4, chainHeight(SourceObservationProgramCodec.decode(accepted, store::get, low)));
        assertBorrowedDepthFailure(() -> SourceObservationProgramCodec.encode(tooDeep, store::put, low));
        String deeperRoot = SourceObservationProgramCodec.encode(tooDeep, store::put, LIMITS);
        assertBorrowedDepthFailure(() -> SourceObservationProgramCodec.decode(deeperRoot, store::get, low));
    }

    @Test
    void memoizedSubtreeCannotHideAnOverdepthPath() {
        SourceObservationProgram shared = program(1, program(0));
        // The shared subtree is completed at depth 1 first, then reached at depth 3 with height 1.
        SourceObservationProgram root = program(9, shared, program(2, program(3, shared)));
        Limits low = new Limits(LIMITS.fragmentBytes, LIMITS.totalBytes, 3, LIMITS.nodes);
        Map<String, byte[]> store = new LinkedHashMap<>();
        String rootKey = SourceObservationProgramCodec.encode(root, store::put, LIMITS);
        String sharedKey = SourceObservationProgramCodec.encode(shared, (key, value) -> { }, LIMITS);
        Map<String, Integer> writes = new HashMap<>();
        assertBorrowedDepthFailure(() -> SourceObservationProgramCodec.encode(root,
                (key, value) -> writes.merge(key, 1, Integer::sum), low));
        assertEquals(Integer.valueOf(1), writes.get(sharedKey), "The shallow visit was memoized before rejection");
        Map<String, Integer> reads = new HashMap<>();
        assertBorrowedDepthFailure(() -> SourceObservationProgramCodec.decode(rootKey, key -> {
            reads.merge(key, 1, Integer::sum);
            return store.get(key);
        }, low));
        assertEquals(Integer.valueOf(1), reads.get(sharedKey), "Cached height is checked without fetching the shared fragment again");
        assertEquals(4, chainHeight(SourceObservationProgramCodec.decode(rootKey, store::get, LIMITS)
                .borrowedPrograms().get(1)) + 1);
    }

    @Test
    void stackSafetyCeilingAppliesEvenWhenCallerAllowsMoreThan256Edges() {
        Limits generousDepth = new Limits(LIMITS.fragmentBytes, LIMITS.totalBytes, 1024, LIMITS.nodes);
        SourceObservationProgram deepestAllowed = chain(256), tooDeep = program(258, deepestAllowed);
        Map<String, byte[]> store = new LinkedHashMap<>();
        String accepted = SourceObservationProgramCodec.encode(deepestAllowed, store::put, generousDepth);
        assertEquals(256, chainHeight(SourceObservationProgramCodec.decode(accepted, store::get, generousDepth)));
        assertBorrowedDepthFailure(() -> SourceObservationProgramCodec.encode(tooDeep, store::put, generousDepth));
        String wrapper = SourceObservationProgramCodec.encode(program(258), store::put, generousDepth);
        String tooDeepRoot = withBorrowedReferences(wrapper, Collections.singletonList(accepted), store);
        assertBorrowedDepthFailure(() -> SourceObservationProgramCodec.decode(tooDeepRoot, store::get, generousDepth));
    }

    @Test
    void borrowedFragmentsShareTheExactPhysicalAndEnclosingReceiptBudgets() {
        SourceObservationProgram leaf = program(1);
        SourceObservationProgram root = program(4, program(2, leaf), program(3, leaf));
        Map<String, byte[]> store = new LinkedHashMap<>();
        String rootKey = SourceObservationProgramCodec.encode(root, store::put, LIMITS);
        long exactBytes = store.values().stream().mapToLong(value -> value.length).sum();
        int exactFragments = store.size();
        Limits exact = new Limits(LIMITS.fragmentBytes, exactBytes, LIMITS.depth, exactFragments);
        assertEquals(rootKey, SourceObservationProgramCodec.encode(root, (key, value) -> { }, exact));
        assertNotNull(SourceObservationProgramCodec.decode(rootKey, store::get, exact));
        for (Limits insufficient : Arrays.asList(
                new Limits(LIMITS.fragmentBytes, exactBytes - 1, LIMITS.depth, exactFragments),
                new Limits(LIMITS.fragmentBytes, exactBytes, LIMITS.depth, exactFragments - 1))) {
            assertThrows(CapacityExceeded.class, () -> SourceObservationProgramCodec.encode(root, (key, value) -> { }, insufficient));
            assertThrows(CapacityExceeded.class, () -> SourceObservationProgramCodec.decode(rootKey, store::get, insufficient));
        }
        byte[] earlierReceiptFragment = "earlier receipt fragment".getBytes(StandardCharsets.UTF_8);
        Encoder sharedEncoder = new Encoder((key, value) -> { }, exact);
        sharedEncoder.blob(earlierReceiptFragment);
        assertThrows(CapacityExceeded.class, () -> SourceObservationProgramCodec.encode(root, sharedEncoder));
        String earlierKey = digest(earlierReceiptFragment);
        store.put(earlierKey, earlierReceiptFragment);
        Decoder sharedDecoder = new Decoder(store::get, exact);
        sharedDecoder.blob(earlierKey);
        assertThrows(CapacityExceeded.class, () -> SourceObservationProgramCodec.decode(rootKey, sharedDecoder));
    }

    @Test
    void exactHistoricalPinsAndCyclicProofRoundTripWithinBorrowedPrograms() {
        DocumentId history = new DocumentId("historical-source");
        ManagedReadPin older = exactPin(history, new Node().name("historical").properties("counter", new Node().value(1)));
        ManagedReadPin newer = exactPin(history, new Node().name("historical").properties("counter", new Node().value(2)));
        Node placeholder = new Node().name("cyclic pin").properties("self", new Node().blueId("this#0"));
        CyclicSetFinalization finalized = new CircularSetIdentityCalculator().finalizeCyclicSet(Collections.singletonList(placeholder));
        String cyclicBlueId = finalized.membersInCanonicalOrder().get(0).finalBlueId();
        Node body = finalized.membersInCanonicalOrder().get(0).canonicalMemberBody();
        body.getAsNode("/self").blueId(cyclicBlueId);
        ManagedReadPin cyclic = ManagedReadPin.fromExactEvidence(new DocumentId(cyclicBlueId), cyclicBlueId, body,
                CyclicSetProof.fromDeclaredPlaceholderSet(finalized.canonicalMemberBodies()));
        List<ManagedReadPin> pins = Arrays.asList(newer, cyclic, older);
        SourceObservationProgram root = program(2, programWithValue(1, "transport state", Collections.emptyList(), pins));
        Map<String, byte[]> store = new LinkedHashMap<>();
        String key = SourceObservationProgramCodec.encode(root, store::put, LIMITS);
        SourceObservationProgram restored = SourceObservationProgramCodec.decode(key, store::get, LIMITS);
        List<ManagedReadPin> decodedPins = restored.borrowedPrograms().get(0).sourceReadPins();
        assertEquals(3, decodedPins.size());
        for (int index = 0; index < pins.size(); index++) {
            ManagedReadPin expected = pins.get(index), actual = decodedPins.get(index);
            assertNotSame(expected, actual);
            assertEquals(expected.documentId(), actual.documentId());
            assertEquals(expected.blueId(), actual.blueId());
            assertEquals(NodeWireForm.get(expected.document()), NodeWireForm.get(actual.document()));
            assertEquals(expected.cyclicProof().isPresent(), actual.cyclicProof().isPresent());
            if (expected.cyclicProof().isPresent()) {
                List<Node> expectedProof = expected.cyclicProof().get().declaredPlaceholderSet();
                List<Node> actualProof = actual.cyclicProof().get().declaredPlaceholderSet();
                assertEquals(expectedProof.size(), actualProof.size());
                for (int member = 0; member < expectedProof.size(); member++)
                    assertEquals(NodeWireForm.get(expectedProof.get(member)), NodeWireForm.get(actualProof.get(member)));
            }
        }
        assertEquals(key, SourceObservationProgramCodec.encode(restored, (fragment, value) -> { }, LIMITS));
        assertThrows(UnsupportedOperationException.class, () -> decodedPins.clear());
    }

    private static SourceObservationProgram program(int identity, SourceObservationProgram... borrowed) {
        return programWithValue(identity, "transport state", Arrays.asList(borrowed), Collections.emptyList());
    }

    private static SourceObservationProgram programWithValue(int identity, String value,
                                                              List<SourceObservationProgram> borrowed, List<ManagedReadPin> pins) {
        DocumentId owner = new DocumentId("transport-owner-" + identity);
        FrozenNode body = FrozenNode.fromResolvedNode(new Node().name(value));
        SourceObservationProgram.SourceState state = new SourceObservationProgram.SourceState(owner,
                DirectBlueIdCalculator.calculateBlueId(body.toNode()), 0L, false, body);
        return new SourceObservationProgram(hash(identity), ProcessingCause.Kind.ADMISSION, hash(2000 + identity), null,
                ENVIRONMENT, POLICY, Collections.singletonList(state), Collections.singletonList(state), Collections.singleton(owner),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), borrowed, pins);
    }

    private static SourceObservationProgram chain(int edges) {
        SourceObservationProgram result = program(0);
        for (int index = 1; index <= edges; index++) result = program(index, result);
        return result;
    }

    private static int chainHeight(SourceObservationProgram program) {
        int height = 0;
        while (!program.borrowedPrograms().isEmpty()) {
            assertEquals(1, program.borrowedPrograms().size());
            program = program.borrowedPrograms().get(0);
            height++;
        }
        return height;
    }

    private static void assertBorrowedDepthFailure(org.junit.jupiter.api.function.Executable action) {
        CapacityExceeded failure = assertThrows(CapacityExceeded.class, action);
        assertTrue(failure.getMessage().contains("Borrowed program nesting"), failure.getMessage());
    }

    private static String withBorrowedReferences(String existingRoot, List<String> borrowed, Map<String, byte[]> store) {
        ObjectNode root = (ObjectNode) json(store.get(existingRoot));
        com.fasterxml.jackson.databind.node.ArrayNode references = root.putArray("borrowedPrograms");
        for (String reference : borrowed) references.add(reference);
        byte[] encoded = bytes(root);
        String key = digest(encoded);
        store.put(key, encoded);
        return key;
    }

    private static ManagedReadPin exactPin(DocumentId owner, Node body) {
        return ManagedReadPin.fromExactEvidence(owner, DirectBlueIdCalculator.calculateBlueId(body), body, null);
    }

    private static String hash(int value) { return "sha256:" + String.format(Locale.ROOT, "%064x", value); }
    private static ClosureEnvironment.LabeledIdentityEvidence label(int value) {
        return new ClosureEnvironment.LabeledIdentityEvidence(hash(value), "transport-" + value);
    }
}
