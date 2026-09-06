package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SameOriginSeedIdentityTest {
    private static final DocumentId A = new DocumentId("A"), B = new DocumentId("B"), R = new DocumentId("R");
    private static final String SHA_A = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @Test
    void driverIndexProducesTheSameSeedForEveryComponentAndCanReconstructWithoutRescanningOtherOwners() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureInvocationInput input = input(processor, 7, true, true, 1, 91, true);
            SameOriginSeedIdentity.Factory factory = new SameOriginSeedIdentity.Factory(input, SameOriginAttachmentPolicy.empty());
            for (ComponentSnapshot component : input.snapshot().components()) {
                List<DocumentId> members = component.orderedMemberDocumentIds();
                String expected = SameOriginSeedIdentity.of(input, members, Collections.emptyMap()).identity();
                assertEquals(expected, factory.of(members).identity());
                assertEquals(expected, factory.of(members).identity(), "Reconstruction does not mutate the index or seed basis");
            }
            assertThrows(IllegalArgumentException.class, () -> factory.of(Arrays.asList(A, B)));
            assertThrows(IllegalArgumentException.class, () -> factory.of(Collections.emptyList()));
        }
    }

    @Test
    void attachmentPoliciesBindOnlyTheirCreatorsSeedAndEmptyLegacyMapIsEquivalent() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureInvocationInput base = input(processor, 0, true, true, 1, 0, false);
            ManagedOccurrenceBinding own = ManagedOccurrenceBinding.derived(base.environment().managedBindingPolicyIdentity(),
                    A, ScopeAddress.embedded("/future-b", 1), B, base.snapshot().managedDocument(B).blueId(), false, null);
            ManagedOccurrenceBinding observer = ManagedOccurrenceBinding.derived(base.environment().managedBindingPolicyIdentity(),
                    R, ScopeAddress.embedded("/future-a", 1), A, base.snapshot().managedDocument(A).blueId(), false, null);
            List<ManagedOccurrenceBinding> rows = new ArrayList<>(base.snapshot().occurrences()); rows.add(own); rows.add(observer);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(base.snapshot().graphGeneration(),
                    base.snapshot().managedDocuments(), rows, base.snapshot().components(), base.snapshot().publicRootDocumentIds());
            ClosureInvocationInput input = ClosureEvidenceFactory.processClosure(snapshot, base.cause(), base.directDeliveries(),
                    base.executionPolicy(), base.environment());
            SameOriginAttachmentPolicy.Selection ownNow = new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW,
                    A, own.occurrenceIdentity(), B, own.expectedTargetBlueId());
            SameOriginAttachmentPolicy.Selection ownHistory = new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FULL_HISTORY,
                    A, own.occurrenceIdentity(), B, own.expectedTargetBlueId());
            SameOriginAttachmentPolicy.Selection observerNow = new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_NOW,
                    R, observer.occurrenceIdentity(), A, observer.expectedTargetBlueId());
            SameOriginAttachmentPolicy onlyOwn = new SameOriginAttachmentPolicy(Collections.singleton(ownNow));
            SameOriginAttachmentPolicy expanded = new SameOriginAttachmentPolicy(Arrays.asList(observerNow, ownNow));
            expanded.verifyBasis(snapshot);
            String emptyA = SameOriginSeedIdentity.of(input, Collections.singletonList(A), SameOriginAttachmentPolicy.empty()).identity();
            assertEquals(emptyA, SameOriginSeedIdentity.of(input, Collections.singletonList(A), Collections.<DocumentId, String>emptyMap()).identity());
            String fromNowA = SameOriginSeedIdentity.of(input, Collections.singletonList(A), onlyOwn).identity();
            assertNotEquals(emptyA, fromNowA);
            assertNotEquals(fromNowA, SameOriginSeedIdentity.of(input, Collections.singletonList(A),
                    new SameOriginAttachmentPolicy(Collections.singleton(ownHistory))).identity());
            assertEquals(fromNowA, SameOriginSeedIdentity.of(input, Collections.singletonList(A), expanded).identity(),
                    "A reverse observer's attachment declaration cannot change the source's seed");
            assertEquals(SameOriginSeedIdentity.of(input, Collections.singletonList(B), SameOriginAttachmentPolicy.empty()).identity(),
                    SameOriginSeedIdentity.of(input, Collections.singletonList(B), expanded).identity(),
                    "Being the target of an attachment declaration is not owning that declaration");
            assertNotEquals(SameOriginSeedIdentity.of(input, Collections.singletonList(R), onlyOwn).identity(),
                    SameOriginSeedIdentity.of(input, Collections.singletonList(R), expanded).identity());
        }
    }

    @Test
    void reverseObserversPhysicalGenerationsAndGlobalRawOrdinalsDoNotChangeSeedOrEmission() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureInvocationInput small = input(processor, 0, false, false, 1, 0, false);
            ClosureInvocationInput expanded = input(processor, 27, true, true, 1, 91, false);
            assertNotEquals(small.invocationIdentity(), expanded.invocationIdentity());
            SameOriginSeedIdentity first = seed(small), second = seed(expanded);
            assertEquals(first.identity(), second.identity());
            DirectLogicalDelivery d1 = ownDelivery(small), d2 = ownDelivery(expanded);
            assertNotEquals(d1.rawOccurrenceOrder(), d2.rawOccurrenceOrder());
            assertEquals(first.directDeliveryIdentity(d1), second.directDeliveryIdentity(d2));
            String work1 = first.workIdentity(0, WorkKind.EXTERNAL_DELIVERY, ManagedScopeKey.root(A), first.directDeliveryIdentity(d1));
            String work2 = second.workIdentity(0, WorkKind.EXTERNAL_DELIVERY, ManagedScopeKey.root(A), second.directDeliveryIdentity(d2));
            assertEquals(work1, work2);
            String event = DirectBlueIdCalculator.calculateBlueId(new Node().value("same emission"));
            assertEquals(first.eventIdentity(0, A, work1, event), second.eventIdentity(0, A, work2, event));
            assertEquals(first.transitionIdentity(0, A, small.snapshot().managedDocument(A).blueId(), work1),
                    second.transitionIdentity(0, A, expanded.snapshot().managedDocument(A).blueId(), work2));
        }
    }

    @Test
    void physicalReadPinPresenceAndUnusedCachedHistoricalViewsCannotReanchorSeed() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureInvocationInput plain = input(processor, 0, false, true, 1, 0, false);
            ClosureInvocationInput warmed = input(processor, 0, false, true, 1, 0, true);
            assertTrue(plain.snapshot().readPins().isEmpty()); assertEquals(2, warmed.snapshot().readPins().size());
            assertEquals(seed(plain).identity(), seed(warmed).identity());
        }
    }

    @Test
    void ownedPredecessorChangesSeedButConsumedIndependentDispositionsBelongToSettlement() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureInvocationInput original = input(processor, 0, false, true, 1, 0, false);
            ClosureInvocationInput afterA = ClosureEvidenceFactory.withSemanticPredecessors(original, Collections.singletonMap(A, SHA_A));
            ClosureInvocationInput afterB = ClosureEvidenceFactory.withSemanticPredecessors(original, Collections.singletonMap(A, SHA_B));
            assertNotEquals(seed(afterA).identity(), seed(afterB).identity());
            ClosureInvocationInput withUnrelated = ClosureEvidenceFactory.withSemanticPredecessors(original,
                    new LinkedHashMap<DocumentId, String>() {{ put(A, SHA_A); put(B, SHA_B); }});
            assertEquals(seed(afterA).identity(), seed(withUnrelated).identity(), "unconsumed other lineage predecessor is not this seed's predecessor");
            assertThrows(IllegalArgumentException.class, () -> SameOriginSeedIdentity.of(original, Collections.singletonList(A), Collections.singletonMap(B, SHA_A)));
            assertThrows(IllegalArgumentException.class, () -> SameOriginSeedIdentity.of(original, Collections.singletonList(A), Collections.singletonMap(A, SHA_A)));
        }
    }

    @Test
    void actualOwnedViewOrActivationChangesSeed() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureInvocationInput original = input(processor, 0, false, true, 1, 0, false);
            ClosureInvocationInput reattached = input(processor, 0, false, true, 2, 0, false);
            assertNotEquals(seed(original).identity(), seed(reattached).identity());
            SameOriginSeedIdentity a = seed(original);
            SameOriginSeedIdentity b = SameOriginSeedIdentity.of(original, Collections.singletonList(B), Collections.<DocumentId, String>emptyMap());
            assertNotEquals(a.identity(), b.identity());
        }
    }

    @Test
    void partialOrCombinedPrecutComponentsCannotBeUsedAsOneOriginalSeed() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureInvocationInput original = input(processor, 0, false, true, 1, 0, false);
            assertThrows(IllegalArgumentException.class, () -> SameOriginSeedIdentity.of(original, Arrays.asList(A, B), Collections.<DocumentId, String>emptyMap()));
            assertThrows(IllegalArgumentException.class, () -> SameOriginSeedIdentity.of(original, Arrays.asList(A, A), Collections.<DocumentId, String>emptyMap()));
            assertThrows(IllegalArgumentException.class, () -> SameOriginSeedIdentity.of(original, Collections.<DocumentId>emptyList(), Collections.<DocumentId, String>emptyMap()));
        }
    }

    @Test
    void workAndEventsBindLocalContinuationAndRejectForeignOwnersOrNonportableOrdinals() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureInvocationInput input = input(processor, 0, false, true, 1, 0, false);
            SameOriginSeedIdentity seed = seed(input);
            String source = seed.directDeliveryIdentity(ownDelivery(input));
            String w0 = seed.workIdentity(0, WorkKind.EXTERNAL_DELIVERY, ManagedScopeKey.root(A), source);
            String w1 = seed.workIdentity(1, WorkKind.EXTERNAL_DELIVERY, ManagedScopeKey.root(A), source);
            String event = DirectBlueIdCalculator.calculateBlueId(new Node().value("event"));
            assertNotEquals(w0, w1);
            assertNotEquals(seed.eventIdentity(0, A, w0, event), seed.eventIdentity(0, A, w1, event));
            assertNotEquals(seed.eventIdentity(0, A, w0, event), seed.eventIdentity(1, A, w0, event));
            assertThrows(IllegalArgumentException.class, () -> seed.eventIdentity(0, B, w0, event));
            assertThrows(IllegalArgumentException.class, () -> seed.workIdentity(0, WorkKind.EXTERNAL_DELIVERY, ManagedScopeKey.root(B), source));
            assertThrows(IllegalArgumentException.class, () -> seed.workIdentity(-1, WorkKind.EXTERNAL_DELIVERY, ManagedScopeKey.root(A), source));
            assertThrows(IllegalArgumentException.class, () -> seed.workIdentity(Long.MAX_VALUE, WorkKind.EXTERNAL_DELIVERY, ManagedScopeKey.root(A), source));
            assertThrows(IllegalArgumentException.class, () -> seed.eventIdentity(0, A, "host-row", event));
        }
    }

    private static SameOriginSeedIdentity seed(ClosureInvocationInput input) {
        return SameOriginSeedIdentity.of(input, Collections.singletonList(A), Collections.<DocumentId, String>emptyMap());
    }
    private static DirectLogicalDelivery ownDelivery(ClosureInvocationInput input) {
        for (DirectLogicalDelivery delivery : input.directDeliveries()) if (delivery.targetDocumentId().equals(A)) return delivery;
        throw new AssertionError("Missing A seed");
    }
    private static ClosureInvocationInput input(DocumentProcessor processor, long generation, boolean observer,
                                                boolean publicRoots, long activation, long rawOrdinal, boolean cachedPins) {
        ClosureEnvironment environment = ClosureEvidenceFactory.environment(processor, SHA_A, SHA_B,
                "source-lineage", "bindings", "provider", "micros-entry", "limits", GasSchedule.contracts10().portableLimits());
        Node bodyB = new Node().name("B").properties("value", new Node().value(1));
        String blueB = DirectBlueIdCalculator.calculateBlueId(bodyB);
        Node bodyA = new Node().name("A").properties("child", new Node().blueId(blueB));
        ManagedDocumentSnapshot b = document(B, bodyB, generation, publicRoots), a = document(A, bodyA, generation, publicRoots);
        List<ManagedDocumentSnapshot> documents = new ArrayList<>(Arrays.asList(a, b));
        List<ComponentSnapshot> components = new ArrayList<>(Arrays.asList(ClosureEvidenceFactory.acyclicComponent(b), ClosureEvidenceFactory.acyclicComponent(a)));
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
        bindings.add(ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), A,
                ScopeAddress.embedded("/child", activation), B, b.blueId(), true, null));
        List<DocumentId> roots = new ArrayList<>(); if (publicRoots) { roots.add(A); roots.add(B); }
        List<DirectLogicalDelivery> deliveries = new ArrayList<>();
        if (rawOrdinal > 0) deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(B), "other", "other-input", 0));
        deliveries.add(new DirectLogicalDelivery(ManagedScopeKey.root(A), "ingress", "own-input", rawOrdinal));
        if (observer) {
            ManagedDocumentSnapshot r = document(R, new Node().name("R").properties("a", new Node().blueId(a.blueId())), generation, publicRoots);
            documents.add(r); components.add(ClosureEvidenceFactory.acyclicComponent(r));
            if (publicRoots) roots.add(R);
            bindings.add(ManagedOccurrenceBinding.derived(environment.managedBindingPolicyIdentity(), R,
                    ScopeAddress.embedded("/a", 1L), A, a.blueId(), true, null));
        }
        List<ManagedReadPin> pins = new ArrayList<>();
        if (cachedPins) {
            pins.add(ManagedReadPin.fromExactEvidence(B, b.blueId(), bodyB, null));
            Node unused = new Node().name("old-unused-B");
            pins.add(ManagedReadPin.fromExactEvidence(B, DirectBlueIdCalculator.calculateBlueId(unused), unused, null));
        }
        AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(generation, documents, bindings, components, roots, pins);
        Node event = new Node().value("origin");
        ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, DirectBlueIdCalculator.calculateBlueId(event),
                ExternalOrderKey.of(Arrays.asList(15L, "entry")), environment.externalOrderPolicyIdentity());
        return ClosureEvidenceFactory.processClosure(snapshot, cause, deliveries,
                ClosureEvidenceFactory.executionPolicy(100_000L, Collections.<DocumentId, Long>emptyMap(), "fixed-policy"), environment);
    }
    private static ManagedDocumentSnapshot document(DocumentId id, Node body, long generation, boolean publicRoot) {
        return new ManagedDocumentSnapshot(id, DirectBlueIdCalculator.calculateBlueId(body), body, true, false, publicRoot, 1L, generation);
    }
}
