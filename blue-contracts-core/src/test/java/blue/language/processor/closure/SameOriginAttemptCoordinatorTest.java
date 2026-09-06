package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasMeter;
import blue.language.processor.ManagedDocumentStepContinuation;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Attempt lifetime tests with actual isolated runtime ledgers; not interpreter conformance fixtures. */
final class SameOriginAttemptCoordinatorTest {
    private static final DocumentId A = new DocumentId("A"), B = new DocumentId("B"), C = new DocumentId("C"), D = new DocumentId("D");
    private static final String SITE = "sha256:" + String.join("", Collections.nCopies(64, "a"));
    private static final ManagedDocumentStepContinuation UNUSED = new ManagedDocumentStepContinuation() {
        @Override public void afterPatch(String scope, Node body, FrozenJsonPatch patch, List<DocumentUpdateOccurrence> updates) { fail("No application work in a lifetime unit test"); }
        @Override public void onApplicationEvent(String scope, String key, Node event, String id) { fail("No application work in a lifetime unit test"); }
        @Override public void onTerminationRequested(String scope, String cause, String reason) { fail("No application work in a lifetime unit test"); }
    };

    @Test void rejectedProducerInvalidatesEntireConditionalAdmissionAndReconstructsFreshOwnAttempts() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build(); SameOriginAttemptCoordinator groups = groups(owner)) {
            SameOriginAttemptCoordinator.Attempt originalA = groups.attempt(A);
            groups.observes(A, B, SITE);
            charge(groups, B, 10);
            charge(groups, A, 60);
            charge(groups, A, 1);
            assertTrue(groups.admitFeedback(A, Arrays.asList(A, C), SITE).joined());
            assertEquals(set(A, C), originalA.members());
            charge(groups, B, 40);
            charge(groups, B, 1);
            GasMeter.MultiGroupJoinResult rejected = groups.admitFeedback(B, Arrays.asList(A, B, C), SITE);
            assertFalse(rejected.joined());
            assertEquals(112L, rejected.contributions().stream().mapToLong(c -> c.admitted() + c.reserved()).sum());
            assertEquals(set(B), groups.attempt(B).members(), "Rejected union has no pairwise accepted prefix");
            List<SameOriginAttemptCoordinator.Invalidation> discarded = groups.failed(B, ProcessorStatus.GAS_LIMIT_EXCEEDED, diagnostic(), SITE);
            assertEquals(1, discarded.size());
            assertEquals(set(A, C), discarded.get(0).reconstructOwnSeeds);
            assertEquals(SameOriginAttemptCoordinator.State.INVALIDATED, originalA.state());
            assertEquals(51, groups.attempt(B).failure().admittedGas, "Original producer prefix is frozen before cleanup");
            assertNotSame(originalA, groups.attempt(A));
            assertNotSame(groups.attempt(A), groups.attempt(C), "Third-party membership belongs only to the discarded attempt");
            charge(groups, A, 5); charge(groups, C, 5);
            assertEquals(5, groups.runtime(A).totalGas()); assertEquals(5, groups.runtime(C).totalGas());
            groups.successful(A); groups.successful(C);
            assertEquals(3, groups.settle().size());
            assertEquals(51, groups.attempt(B).failure().admittedGas, "Cleanup never re-evaluates the cheaper producer join");
        }
    }

    @Test void failedConditionalCandidateAndItsThirdPartyMembershipAreDiscardedTogether() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build(); SameOriginAttemptCoordinator groups = groups(owner)) {
            groups.observes(A, B, SITE);
            charge(groups, A, 61);
            assertTrue(groups.admitFeedback(A, Arrays.asList(A, C), SITE).joined());
            SameOriginAttemptCoordinator.Attempt candidate = groups.attempt(A);
            groups.failed(A, ProcessorStatus.GAS_LIMIT_EXCEEDED, diagnostic(), SITE);
            assertEquals(SameOriginAttemptCoordinator.State.FAILURE_CANDIDATE, candidate.state());
            charge(groups, B, 51);
            groups.failed(B, ProcessorStatus.RUNTIME_FATAL, diagnostic(), SITE);
            assertEquals(SameOriginAttemptCoordinator.State.INVALIDATED, candidate.state());
            assertEquals(set(A, C), groups.invalidations().get(0).reconstructOwnSeeds);
            assertNull(groups.attempt(A).failure(), "The discarded candidate failure is not a new attempt's outcome");
            assertEquals(0, groups.runtime(A).totalGas());
            assertTrue(groups.attempt(A).admissions().isEmpty());
        }
    }

    @Test void failureAfterAnAcceptedFeedbackJoinRollsBackTheWholeAlreadyJoinedScope() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build(); SameOriginAttemptCoordinator groups = groups(owner)) {
            groups.observes(A, B, SITE);
            charge(groups, A, 60); charge(groups, B, 31);
            assertTrue(groups.admitFeedback(B, Arrays.asList(A, B, C), SITE).joined());
            assertSame(groups.attempt(A), groups.attempt(B)); assertSame(groups.attempt(B), groups.attempt(C));
            List<SameOriginAttemptCoordinator.Invalidation> discarded = groups.failed(B, ProcessorStatus.RUNTIME_FATAL, diagnostic(), SITE);
            assertTrue(discarded.isEmpty(), "Same-group members are one rollback, not conditional retries");
            assertEquals(set(A, B, C), groups.attempt(B).failure().members);
            assertEquals(91, groups.attempt(B).failure().admittedGas);
            assertEquals(3, groups.attempt(B).failure().memberTraces.size(), "Distinct original runtimes remain distinct trace owners");
            assertEquals(1, groups.settle().size());
        }
    }

    @Test void invalidationIsTransitiveAndCannotRevokeASettledOperation() {
        try (DocumentProcessor owner = DocumentProcessor.builder().build(); SameOriginAttemptCoordinator groups = groups(owner)) {
            groups.observes(A, B, SITE); groups.observes(D, A, SITE);
            groups.successful(A); groups.successful(D);
            assertThrows(IllegalStateException.class, groups::settle, "B is unfinished");
            List<SameOriginAttemptCoordinator.Invalidation> discarded = groups.failed(B, ProcessorStatus.RUNTIME_FATAL, diagnostic(), SITE);
            assertEquals(2, discarded.size());
            assertEquals(set(A), discarded.get(0).reconstructOwnSeeds);
            assertEquals(set(D), discarded.get(1).reconstructOwnSeeds);
            groups.successful(A); groups.successful(D);
            groups.settle();
            assertThrows(IllegalStateException.class, () -> groups.failed(B, ProcessorStatus.RUNTIME_FATAL, diagnostic(), SITE));
            assertThrows(IllegalStateException.class, () -> groups.admitFeedback(A, Arrays.asList(A, B), SITE));
        }
    }

    private static SameOriginAttemptCoordinator groups(DocumentProcessor owner) {
        List<ComponentSnapshot> components = new ArrayList<ComponentSnapshot>();
        for (DocumentId document : Arrays.asList(A, B, C, D)) {
            Node body = new Node().name(document.value());
            ManagedDocumentSnapshot state = new ManagedDocumentSnapshot(document, DirectBlueIdCalculator.calculateBlueId(body), body, false, false, true, 0, 0);
            components.add(ClosureEvidenceFactory.acyclicComponent(state));
        }
        return new SameOriginAttemptCoordinator(owner, new ExecutionPolicy(SITE, 100, Collections.emptyMap(), "lifetime test"), UNUSED, components);
    }
    private static void charge(SameOriginAttemptCoordinator groups, DocumentId document, long units) {
        groups.runtime(document).charge("processor", "pointerSegmentTraversed", units,
                GasChargeContext.closure(document.value(), "/", 0L, 0L, null, null, SITE, "lifetime prefix"));
    }
    private static ProcessorDiagnostic diagnostic() { return ProcessorDiagnostic.of(ProcessorErrorCategory.RuntimeExecutionFailure, "recognized test failure"); }
    private static TreeSet<DocumentId> set(DocumentId... documents) { return new TreeSet<DocumentId>(Arrays.asList(documents)); }
}
