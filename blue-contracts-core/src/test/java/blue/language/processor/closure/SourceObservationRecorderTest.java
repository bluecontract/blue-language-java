package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Bookkeeping fixtures exercise recorder lifetime, not a proof of executing the synthetic steps. */
class SourceObservationRecorderTest {
    @Test
    void canonicalInitializationMayCoexistWithOneProcessingViewOfTheSameSource() {
        try (Fixture fixture = new Fixture()) {
            DocumentId source = new DocumentId("shared source");
            SourceObservationProgram initialization = fixture.borrowed(200, source, "init0");
            SourceObservationProgram processing = fixture.external(201, source, "current origin");
            Object token = new Object();
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            recorder.borrowedProgram(initialization, token);
            recorder.borrowedProgram(processing, token);
            List<SourceObservationProgram> captured = recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token)).borrowedPrograms();
            assertEquals(Arrays.asList(initialization, processing), captured);
            SourceObservationProgram root = fixture.withBorrowed(captured);
            assertEquals(2, root.borrowedPrograms().size());
            java.util.Map<String, byte[]> store = new java.util.HashMap<>();
            String key = SourceObservationProgramCodec.encode(root, store::put, FrozenNodeEvidenceCodec.Limits.defaults());
            assertEquals(2, SourceObservationProgramCodec.decode(key, store::get, FrozenNodeEvidenceCodec.Limits.defaults()).borrowedPrograms().size());

            SourceObservationProgram conflicting = fixture.external(202, source, "another processing operation");
            recorder.borrowedProgram(conflicting, token);
            assertThrows(IllegalArgumentException.class, () -> recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token)));
            assertThrows(IllegalArgumentException.class, () -> fixture.withBorrowed(Arrays.asList(initialization, processing, conflicting)));
            assertThrows(IllegalArgumentException.class, () -> fixture.withBorrowed(Arrays.asList(initialization, fixture.borrowed(203, source, "other init"), processing)));
        }
    }

    @Test
    void patchTopologyIsFrozenBeforeNestedCallbacksAndContainsOnlyThePatchedSource() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object token = new Object();
            DocumentStepInput outer = fixture.step(fixture.a, 0), nested = fixture.step(fixture.b, 1);
            recorder.enter(outer, hash(100), token);
            recorder.action(new SourceObservationProgram.Patch(hash(101), hash(102), new Node().name("after patch"),
                    FrozenJsonPatch.add("/value", FrozenNode.fromNode(new Node().value(1))), Collections.emptyList()));
            ManagedOccurrenceBinding binding = ManagedOccurrenceBinding.derived(hash(500), fixture.a, ScopeAddress.embedded("/child", 1L),
                    fixture.b, "selected-child", true, null);
            List<ManagedOccurrenceBinding> offered = new ArrayList<>(Collections.singletonList(binding));
            assertThrows(IllegalArgumentException.class, () -> recorder.completePatchBindings(Collections.singletonList(
                    ManagedOccurrenceBinding.derived(hash(500), fixture.b, ScopeAddress.embedded("/foreign", 1L), fixture.a, "foreign", true, null))));
            recorder.completePatchBindings(offered);
            offered.clear();
            assertThrows(IllegalStateException.class, () -> recorder.completePatchBindings(Collections.emptyList()));
            recorder.enter(nested, hash(103), token);
            recorder.action(new SourceObservationProgram.Patch(hash(104), hash(105), new Node().name("nested after patch"),
                    FrozenJsonPatch.add("/value", FrozenNode.fromNode(new Node().value(2))), Collections.emptyList()));
            recorder.completePatchBindings(Collections.emptyList());
            recorder.exit(result(nested));
            recorder.exit(result(outer));
            SourceObservationRecorder.Captured captured = recorder.captureOwned(fixture.owners, Collections.singleton(token));
            SourceObservationProgram.Patch first = (SourceObservationProgram.Patch) captured.steps().get(0).actions().get(0);
            SourceObservationProgram.Patch second = (SourceObservationProgram.Patch) captured.steps().get(1).actions().get(0);
            assertEquals(Collections.singletonList(binding), first.resultingBindings());
            assertTrue(second.resultingBindings().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> first.resultingBindings().clear());
            assertEquals(hash(101), first.siteIdentity());
            assertEquals(hash(102), first.transitionIdentity());
        }
    }

    @Test
    void patchTopologyCompletionRequiresAnExactCurrentPatchAndUniqueOccurrences() {
        SourceObservationRecorder recorder = new SourceObservationRecorder();
        assertThrows(IllegalStateException.class, () -> recorder.completePatchBindings(Collections.emptyList()));
        try (Fixture fixture = new Fixture()) {
            recorder.enter(fixture.step(fixture.a, 0), hash(100), new Object());
            recorder.action(event("not a patch", 0));
            assertThrows(IllegalStateException.class, () -> recorder.completePatchBindings(Collections.emptyList()));
            SourceObservationProgram.Patch patch = new SourceObservationProgram.Patch(new Node().name("after"),
                    FrozenJsonPatch.add("/x", FrozenNode.fromNode(new Node().value(1))), Collections.emptyList());
            ManagedOccurrenceBinding row = ManagedOccurrenceBinding.derived(hash(500), fixture.a, ScopeAddress.embedded("/child", 1L),
                    fixture.b, "selected-child", true, null);
            assertThrows(IllegalArgumentException.class, () -> patch.withResultingBindings(Arrays.asList(row, row)));
            assertNull(patch.resultingBindings(), "Completing topology returns a new immutable action");
            assertEquals(Collections.singletonList(row), patch.withResultingBindings(Collections.singletonList(row)).resultingBindings());
        }
    }

    @Test
    void acceptedViewsRetainOnlySurvivingCreatorAttemptsInCanonicalEncounterOrder() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object discarded = new EqualToken(), first = new EqualToken(), second = new EqualToken();
            AcceptedAttachmentView old = attachment(fixture.a, fixture.b, 100);
            AcceptedAttachmentView keptFirst = attachment(fixture.b, fixture.a, 101);
            AcceptedAttachmentView keptSecond = attachment(fixture.a, fixture.b, 102);
            recorder.acceptedView(old, discarded);
            recorder.acceptedView(keptFirst, first);
            recorder.acceptedView(keptSecond, second);
            Set<Object> tokens = Collections.newSetFromMap(new IdentityHashMap<>());
            tokens.add(second); tokens.add(first);
            SourceObservationRecorder.Captured both = recorder.captureOwned(fixture.owners, tokens);
            assertEquals(Arrays.asList(keptFirst, keptSecond), both.acceptedViews());
            assertEquals(Collections.singletonList(keptSecond),
                    recorder.captureOwned(Collections.singleton(fixture.a), tokens).acceptedViews(),
                    "The creator owns acceptance, not the source whose exact state it observes");
            assertThrows(UnsupportedOperationException.class, () -> both.acceptedViews().clear());
            recorder.acceptedView(attachment(fixture.a, fixture.b, 103), second);
            assertEquals(2, both.acceptedViews().size(), "Captured evidence is an immutable snapshot");
        }
    }

    private static AcceptedAttachmentView attachment(DocumentId creator, DocumentId target, int site) {
        Node body = new Node().name("selected source");
        String blueId = DirectBlueIdCalculator.calculateBlueId(body);
        SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(
                SameOriginAttachmentPolicy.Mode.FROM_NOW, creator, hash(900), target, blueId);
        return AcceptedAttachmentView.fromCanonicalSite(selection, hash(901), hash(site), hash(902), hash(site + 1000),
                ManagedReadPin.fromExactEvidence(target, blueId, body, null));
    }

    @Test
    void invalidationRemovesCompletedCallbacksProjectionsAndBorrowedEvidenceByTokenIdentity() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object invalidated = new EqualToken(), surviving = new EqualToken();
            assertEquals(invalidated, surviving, "Value equality must not merge physical attempt lifetimes");
            DocumentStepInput outer = fixture.step(fixture.a, 0), callback = fixture.step(fixture.b, 1);
            recorder.enter(outer, hash(100), invalidated);
            recorder.action(event("invalidated outer", 0));
            recorder.enter(callback, hash(101), invalidated);
            recorder.action(event("already completed callback", 1));
            recorder.exit(result(callback));
            recorder.exit(result(outer));
            recorder.referenceProjection(projection(fixture.b, fixture.a, 102), invalidated);
            DocumentId dependency = new DocumentId("borrowed source");
            recorder.borrowedProgram(fixture.borrowed(200, dependency, "invalidated state"), invalidated);

            recorder.enter(outer, hash(100), surviving);
            recorder.action(event("surviving outer", 2));
            recorder.exit(result(outer));
            SourceObservationProgram.ReferenceProjection keptProjection = projection(fixture.b, fixture.a, 103);
            SourceObservationProgram keptBorrowed = fixture.borrowed(201, dependency, "surviving state");
            recorder.referenceProjection(keptProjection, surviving);
            recorder.borrowedProgram(keptBorrowed, surviving);
            SourceObservationRecorder.Captured captured = recorder.captureOwned(
                    new HashSet<>(Arrays.asList(fixture.a, fixture.b)), Collections.singleton(surviving));

            assertEquals(1, captured.steps().size());
            assertEquals(hash(100), captured.steps().get(0).entrySiteIdentity());
            assertEquals("surviving outer", ((SourceObservationProgram.Enqueue) captured.steps().get(0).actions().get(0)).event().getName());
            assertEquals(Collections.singletonList(keptProjection), captured.projections());
            assertEquals(Collections.singletonList(keptBorrowed), captured.borrowedPrograms());
        }
    }

    @Test
    void ownedCaptureKeepsNestedActionBoundariesAndFiltersProjectionTargetNotSource() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object token = new Object();
            DocumentStepInput outer = fixture.step(fixture.a, 0), foreign = fixture.step(fixture.b, 1), nested = fixture.step(fixture.a, 2);
            recorder.enter(outer, hash(100), token);
            recorder.action(event("outer before", 0));
            recorder.enter(foreign, hash(101), token);
            recorder.action(event("foreign action", 1));
            recorder.enter(nested, hash(102), token);
            recorder.action(event("nested owned callback", 2));
            recorder.exit(result(nested));
            recorder.exit(result(foreign));
            recorder.action(event("outer after", 3));
            recorder.exit(result(outer));
            SourceObservationProgram.ReferenceProjection ownedTarget = projection(fixture.b, fixture.a, 103);
            recorder.referenceProjection(ownedTarget, token);
            recorder.referenceProjection(projection(fixture.a, fixture.b, 104), token);
            SourceObservationProgram external = fixture.borrowed(200, new DocumentId("external source"), "external");
            recorder.borrowedProgram(external, token);

            SourceObservationRecorder.Captured captured = recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token));
            assertEquals(Arrays.asList(hash(100), hash(102)), Arrays.asList(
                    captured.steps().get(0).entrySiteIdentity(), captured.steps().get(1).entrySiteIdentity()));
            assertEquals(Arrays.asList("outer before", "outer after"), eventNames(captured.steps().get(0)));
            assertEquals(Collections.singletonList("nested owned callback"), eventNames(captured.steps().get(1)));
            assertEquals(Collections.singletonList(ownedTarget), captured.projections());
            assertEquals(Collections.singletonList(external), captured.borrowedPrograms(),
                    "Borrowed source ownership is deliberately outside the selected consumer owners");
        }
    }

    @Test
    void nestedAbortRestoresParentContinuationAndSelectedFailedStepsCannotExport() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object parent = new Object(), aborted = new Object();
            DocumentStepInput outer = fixture.step(fixture.a, 0), nested = fixture.step(fixture.b, 1);
            recorder.enter(outer, hash(100), parent);
            recorder.action(event("before abort", 0));
            recorder.enter(nested, hash(101), aborted);
            recorder.action(event("discard partial", 1));
            recorder.abortStep();
            recorder.action(event("after abort", 2));
            recorder.exit(result(outer));

            SourceObservationRecorder.Captured captured = recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(parent));
            assertEquals(Arrays.asList("before abort", "after abort"), eventNames(captured.steps().get(0)));
            assertThrows(IllegalStateException.class,
                    () -> recorder.captureOwned(Collections.singleton(fixture.b), Collections.singleton(aborted)));
            assertThrows(IllegalStateException.class, () -> recorder.action(event("no active continuation", 3)));
        }
    }

    @Test
    void incompleteSelectedStepRejectsWithoutPreventingAnotherCompletedGroupCapture() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object complete = new Object(), incomplete = new Object();
            DocumentStepInput first = fixture.step(fixture.a, 0), second = fixture.step(fixture.b, 1);
            recorder.enter(first, hash(100), complete);
            recorder.exit(result(first));
            recorder.enter(second, hash(101), incomplete);

            assertEquals(1, recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(complete)).steps().size());
            assertThrows(IllegalStateException.class,
                    () -> recorder.captureOwned(Collections.singleton(fixture.b), Collections.singleton(incomplete)));
            recorder.exit(result(second));
            assertEquals(1, recorder.captureOwned(Collections.singleton(fixture.b), Collections.singleton(incomplete)).steps().size());
        }
    }

    @Test
    void acceptedGroupRetainsTheUnionOfOriginalTokensInOriginalEntryOrder() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object firstToken = new EqualToken(), secondToken = new EqualToken();
            DocumentStepInput first = fixture.step(fixture.a, 0), second = fixture.step(fixture.b, 1);
            recorder.enter(first, hash(100), firstToken);
            recorder.exit(result(first));
            recorder.enter(second, hash(101), secondToken);
            recorder.exit(result(second));
            Set<Object> originalTokens = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
            originalTokens.add(secondToken);
            originalTokens.add(firstToken);
            SourceObservationRecorder.Captured captured = recorder.captureOwned(fixture.owners, originalTokens);
            assertEquals(2, captured.steps().size());
            assertEquals(Arrays.asList(hash(100), hash(101)), Arrays.asList(
                    captured.steps().get(0).entrySiteIdentity(), captured.steps().get(1).entrySiteIdentity()));
            SourceObservationRecorder.Captured onlySecond = recorder.captureOwned(fixture.owners, Collections.singleton(secondToken));
            assertEquals(1, onlySecond.steps().size());
            assertEquals(fixture.b, onlySecond.steps().get(0).targetDocumentId());
        }
    }

    @Test
    void wrongStepResultCannotPopAnotherActiveContinuation() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object token = new Object();
            DocumentStepInput outer = fixture.step(fixture.a, 0), nested = fixture.step(fixture.b, 1);
            recorder.enter(outer, hash(100), token);
            recorder.enter(nested, hash(101), token);
            assertThrows(IllegalStateException.class, () -> recorder.exit(result(outer)));
            recorder.exit(result(nested));
            recorder.exit(result(outer));
            assertEquals(2, recorder.captureOwned(fixture.owners, Collections.singleton(token)).steps().size());
        }
    }

    @Test
    void retryUsesOriginalSemanticSiteAndRestartsPatchOrdinalAfterAbortedAttempt() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object discarded = new Object(), surviving = new Object();
            DocumentStepInput step = fixture.step(fixture.a, 0);
            recorder.enter(step, hash(100), discarded);
            recorder.action(patch(1));
            recorder.action(patch(2));
            recorder.abortStep();
            recorder.enter(step, hash(100), surviving);
            recorder.action(patch(1));
            recorder.exit(result(step));

            SourceObservationProgram.Step captured = recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(surviving)).steps().get(0);
            assertEquals(1, captured.actions().size());
            assertEquals(ClosureIdentityService.INSTANCE.observationPatchSiteIdentity(hash(100), 1L),
                    ((SourceObservationProgram.Patch) captured.actions().get(0)).siteIdentity());
            assertEquals(hash(100), captured.entrySiteIdentity());
        }
    }

    @Test
    void survivingBorrowedProgramsDeduplicateExactlyAndRejectConflictsOrConsumerOwnership() {
        try (Fixture fixture = new Fixture()) {
            Object token = new Object();
            DocumentId external = new DocumentId("external source");
            SourceObservationProgram first = fixture.borrowed(200, external, "same body");
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            recorder.borrowedProgram(first, token);
            recorder.borrowedProgram(first, token);
            recorder.borrowedProgram(fixture.borrowed(200, external, "same body"), token);
            assertEquals(Collections.singletonList(first), recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token)).borrowedPrograms());

            recorder.borrowedProgram(fixture.borrowed(200, external, "conflicting payload"), token);
            assertThrows(IllegalArgumentException.class, () -> recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token)));
            SourceObservationRecorder differentOperation = new SourceObservationRecorder();
            differentOperation.borrowedProgram(first, token);
            differentOperation.borrowedProgram(fixture.borrowed(201, external, "other operation"), token);
            assertThrows(IllegalArgumentException.class, () -> differentOperation.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token)));
            SourceObservationRecorder ownSource = new SourceObservationRecorder();
            ownSource.borrowedProgram(fixture.borrowed(202, fixture.a, "owned source"), token);
            assertThrows(IllegalArgumentException.class, () -> ownSource.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token)));
        }
    }

    @Test
    void capturedEvidenceIsAnImmutableSnapshotAndRequiresExplicitNonemptySelection() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            Object token = new Object();
            DocumentStepInput first = fixture.step(fixture.a, 0);
            recorder.enter(first, hash(100), token);
            recorder.exit(result(first));
            SourceObservationRecorder.Captured firstCapture = recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token));
            DocumentStepInput next = fixture.step(fixture.a, 1);
            recorder.enter(next, hash(101), token);
            recorder.exit(result(next));
            assertEquals(1, firstCapture.steps().size());
            assertEquals(2, recorder.captureOwned(Collections.singleton(fixture.a), Collections.singleton(token)).steps().size());
            assertThrows(UnsupportedOperationException.class, () -> firstCapture.steps().clear());
            assertThrows(UnsupportedOperationException.class, () -> firstCapture.projections().clear());
            assertThrows(UnsupportedOperationException.class, () -> firstCapture.borrowedPrograms().clear());
            assertThrows(IllegalArgumentException.class, () -> recorder.captureOwned(Collections.emptySet(), Collections.singleton(token)));
            assertThrows(IllegalArgumentException.class, () -> recorder.captureOwned(Collections.singleton(fixture.a), Collections.emptySet()));
        }
    }

    @Test
    void legacyEnterAndFinishRemainAvailableWithoutPhysicalTokensInTheProgram() {
        try (Fixture fixture = new Fixture()) {
            SourceObservationRecorder recorder = new SourceObservationRecorder();
            DocumentStepInput step = fixture.step(fixture.a, 0);
            recorder.enter(step);
            recorder.action(patch(1));
            recorder.exit(result(step));
            SourceObservationProgram program = recorder.finish(fixture.input, fixture.successfulAdmission());
            assertEquals(1, program.steps().size());
            String entry = ClosureIdentityService.INSTANCE.observationEntrySiteIdentity(step.work().workIdentity());
            assertEquals(entry, program.steps().get(0).entrySiteIdentity());
            assertEquals(step.work().workIdentity(), program.steps().get(0).workIdentity());
            assertEquals(ClosureIdentityService.INSTANCE.observationPatchSiteIdentity(entry, 1L),
                    ((SourceObservationProgram.Patch) program.steps().get(0).actions().get(0)).siteIdentity());
            assertEquals(hash(401), ((SourceObservationProgram.Patch) program.steps().get(0).actions().get(0)).transitionIdentity());
            assertEquals(fixture.owners, program.ownedDocumentIds());

            SourceObservationRecorder attemptOwned = new SourceObservationRecorder();
            attemptOwned.enter(step, entry, new Object());
            attemptOwned.exit(result(step));
            assertThrows(IllegalStateException.class, () -> attemptOwned.finish(fixture.input, fixture.successfulAdmission()));
            SourceObservationRecorder aborted = new SourceObservationRecorder();
            aborted.enter(step);
            aborted.abortStep();
            assertThrows(IllegalStateException.class, () -> aborted.finish(fixture.input, fixture.successfulAdmission()));
        }
    }

    private static SourceObservationProgram.Enqueue event(String label, int ordinal) {
        Node event = new Node().name(label);
        return new SourceObservationProgram.Enqueue("events", event, DirectBlueIdCalculator.calculateBlueId(event), hash(300 + ordinal));
    }

    private static SourceObservationProgram.Patch patch(int value) {
        FrozenNode scalar = FrozenNode.fromNode(new Node().value(value));
        return new SourceObservationProgram.Patch(null, hash(400 + value), new Node().properties("counter", new Node().value(value)),
                FrozenJsonPatch.replace("/counter", scalar), Collections.emptyList());
    }

    private static SourceObservationProgram.ReferenceProjection projection(DocumentId source, DocumentId target, int site) {
        FrozenNode before = FrozenNode.fromResolvedNode(new Node().blueId(
                DirectBlueIdCalculator.calculateBlueId(new Node().name("before-reference"))));
        FrozenNode after = FrozenNode.fromResolvedNode(new Node().blueId(
                DirectBlueIdCalculator.calculateBlueId(new Node().name("after-reference"))));
        FrozenNode beforeBody = FrozenNode.fromResolvedNode(new Node().properties("child", before.toNode()));
        FrozenNode afterBody = FrozenNode.fromResolvedNode(new Node().properties("child", after.toNode()));
        return new SourceObservationProgram.ReferenceProjection(hash(site), source, target,
                DirectBlueIdCalculator.calculateBlueId(beforeBody.toNode()), DirectBlueIdCalculator.calculateBlueId(afterBody.toNode()),
                beforeBody, afterBody, Collections.singletonList(new SourceObservationProgram.ReferenceChange("/child", before, after)));
    }

    private static LocalDocumentStepResult result(DocumentStepInput input) {
        return new LocalDocumentStepResult(input.work().targetDocumentId(), input.work().workIdentity(),
                input.targetDocument().blueId(), input.targetDocument().document(), Collections.emptyList(), 0L, 0L, false);
    }

    private static List<String> eventNames(SourceObservationProgram.Step step) {
        List<String> names = new ArrayList<>();
        for (SourceObservationProgram.Action action : step.actions()) names.add(((SourceObservationProgram.Enqueue) action).event().getName());
        return names;
    }

    private static String hash(int value) { return "sha256:" + String.format(Locale.ROOT, "%064x", value); }

    private static final class EqualToken {
        @Override public boolean equals(Object other) { return other instanceof EqualToken; }
        @Override public int hashCode() { return 1; }
    }

    private static final class Fixture implements AutoCloseable {
        final DocumentProcessor owner = DocumentProcessor.builder().build();
        final ManagedDocumentSnapshot documentA = document("recorder A"), documentB = document("recorder B");
        final DocumentId a = documentA.documentId(), b = documentB.documentId();
        final Set<DocumentId> owners = new HashSet<>(Arrays.asList(a, b));
        final AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(0L, Arrays.asList(documentA, documentB),
                Collections.emptyList(), Arrays.asList(ClosureEvidenceFactory.acyclicComponent(documentA), ClosureEvidenceFactory.acyclicComponent(documentB)),
                Arrays.asList(a, b));
        final ClosureEnvironment environment = ClosureEvidenceFactory.environment(owner, hash(1000), hash(1001),
                "recorder-identities", "recorder-bindings", "recorder-provider", "recorder-order", "recorder-limits", GasSchedule.contracts10().portableLimits());
        final ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(100000L, Collections.emptyMap(), "recorder-policy");
        final ClosureInvocationInput input = ClosureEvidenceFactory.admitClosure(snapshot,
                ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION, "recorder fixture", null, null, "FULL_HISTORY"),
                null, policy, environment);
        private ClosureProcessResult admitted;

        DocumentStepInput step(DocumentId target, int ordinal) {
            ClosureWorkOccurrence work = new ClosureWorkOccurrence(ordinal, WorkKind.INITIALIZATION, target, "", null, null,
                    ClosureIdentityService.INSTANCE.managedScopeKeyIdentity(ManagedScopeKey.root(target)), hash(400 + ordinal), hash(500 + ordinal));
            return new DocumentStepInput(ordinal, work, snapshot.managedDocument(target), new Node().name("recorder payload"),
                    TentativeResolutionContext.from(input, snapshot, target));
        }

        SourceObservationProgram borrowed(int invocation, DocumentId target, String label) {
            Node body = new Node().name(label);
            SourceObservationProgram.SourceState state = new SourceObservationProgram.SourceState(target,
                    DirectBlueIdCalculator.calculateBlueId(body), 0L, false, FrozenNode.fromResolvedNode(body));
            return new SourceObservationProgram(hash(invocation), ProcessingCause.Kind.ADMISSION, hash(invocation + 1000), null,
                    environment, policy, Collections.singletonList(state), Collections.singletonList(state), Collections.singleton(target), Collections.emptyList());
        }

        SourceObservationProgram external(int invocation, DocumentId target, String label) {
            SourceObservationProgram state = borrowed(invocation, target, label);
            Node event = new Node().name("original external cause");
            String eventId = DirectBlueIdCalculator.calculateBlueId(event);
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(event, eventId,
                    blue.language.processor.ExternalOrderKey.of(Arrays.asList(10L, eventId)), environment.externalOrderPolicyIdentity());
            return new SourceObservationProgram(hash(invocation), ProcessingCause.Kind.EXTERNAL, cause.causeIdentity(), cause,
                    environment, policy, state.sourcePredecessors(), state.sourceResults(), state.ownedDocumentIds(), Collections.emptyList());
        }

        SourceObservationProgram withBorrowed(List<SourceObservationProgram> dependencies) {
            SourceObservationProgram root = borrowed(300, a, "consumer bookkeeping");
            return new SourceObservationProgram(root.invocationIdentity(), root.causeKind(), root.causeIdentity(), null,
                    environment, policy, root.sourcePredecessors(), root.sourceResults(), root.ownedDocumentIds(), Collections.emptyList(),
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    dependencies, Collections.emptyList());
        }

        ClosureProcessResult successfulAdmission() {
            if (admitted == null) {
                try (BlueClosureContracts contracts = new BlueClosureContracts(owner)) {
                    ClosureAttemptResult result = contracts.admitExternalScope(input, owners);
                    assertTrue(result.isComplete());
                    assertEquals(ProcessorStatus.SUCCESS, result.processResult().status());
                    admitted = result.processResult();
                }
            }
            return admitted;
        }

        private static ManagedDocumentSnapshot document(String label) {
            Node body = new Node().name(label).properties("counter", new Node().value(0)).contracts(new Node());
            String blueId = DirectBlueIdCalculator.calculateBlueId(body);
            return new ManagedDocumentSnapshot(new DocumentId(blueId), blueId, body, false, false, true, 0L, 0L);
        }

        @Override public void close() { owner.close(); }
    }
}
