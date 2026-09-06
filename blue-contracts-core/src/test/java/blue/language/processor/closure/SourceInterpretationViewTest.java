package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Real retained initializer program; this helper never reexecutes a workflow or changes a host cell. */
final class SourceInterpretationViewTest {
    @Test void canonicalInitializerPrefixIsPrivateWhileAnOldAliasKeepsItsLaterExactSourceView() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            SourceInitialization initialization = fixture.initialization;
            DocumentId source = initialization.ownedDocumentIds().iterator().next();
            Node later = initialization.program().sourceResults().get(0).document()
                    .properties("counter", new Node().value(BigInteger.valueOf(99)));
            String laterId = DirectBlueIdCalculator.calculateBlueId(later);
            ManagedDocumentSnapshot globalEpochFive = new ManagedDocumentSnapshot(source, laterId, later, true, false, true, 5, 0);
            Node oldAlias = new Node().blueId(laterId);
            SourceInterpretationView local = SourceInterpretationView.from(initialization);
            assertEquals(source.value(), local.blueId(source));
            assertEquals(BigInteger.ZERO, local.document(source).getProperties().get("counter").getValue());
            boolean sawOriginalPatch = false;
            for (SourceObservationProgram.Step step : local.steps()) {
                local.enterStep(step);
                for (SourceObservationProgram.Action action : step.actions()) {
                    local.consumeAction(action);
                    if (action instanceof SourceObservationProgram.Patch) {
                        sawOriginalPatch = true;
                        assertEquals(NodeWireForm.get(((SourceObservationProgram.Patch) action).document()), NodeWireForm.get(local.document(source)));
                    }
                    assertEquals(laterId, oldAlias.getBlueId());
                    assertEquals(BigInteger.valueOf(99), globalEpochFive.document().getProperties().get("counter").getValue());
                    assertEquals(5, globalEpochFive.epoch());
                }
                local.finishStep(step);
            }
            assertTrue(sawOriginalPatch, "The real source initializer applied counter=5");
            local.complete();
            assertEquals(BigInteger.valueOf(5), local.document(source).getProperties().get("counter").getValue());
            assertNotEquals(globalEpochFive.blueId(), local.blueId(source));
            assertEquals(initialization.program().sourceResults().get(0).blueId(), local.selectedPin(source).blueId());
            assertEquals(1, fixture.initializerExecutions, "Interpretation must not invoke the producing handler again");
        }
    }

    @Test void coldProgramInterpretationRetainsTheSameOriginalSitesAndCompletedExactView() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            SourceInitialization hot = fixture.initialization;
            Map<String, byte[]> fragments = new HashMap<String, byte[]>();
            String trusted = SourceObservationProgramCodec.encode(hot.program(), fragments::put, FrozenNodeEvidenceCodec.Limits.defaults());
            SourceInitialization cold = SourceInitialization.fromProgram(SourceObservationProgramCodec.decode(trusted,
                    fragments::get, FrozenNodeEvidenceCodec.Limits.defaults()));
            SourceInterpretationView left = SourceInterpretationView.from(hot), right = SourceInterpretationView.from(cold);
            assertEquals(left.steps().size(), right.steps().size());
            for (int i = 0; i < left.steps().size(); i++) {
                assertEquals(left.steps().get(i).workIdentity(), right.steps().get(i).workIdentity());
                assertEquals(left.steps().get(i).entrySiteIdentity(), right.steps().get(i).entrySiteIdentity());
            }
            replay(left); replay(right);
            for (DocumentId source : hot.ownedDocumentIds()) {
                assertEquals(left.blueId(source), right.blueId(source));
                assertEquals(NodeWireForm.get(left.document(source)), NodeWireForm.get(right.document(source)));
            }
            assertEquals(1, fixture.initializerExecutions);
        }
    }

    @Test void interpreterRejectsSkippedRepeatedOrForeignOriginalActionsWithoutRunningThem() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            SourceInterpretationView view = SourceInterpretationView.from(fixture.initialization);
            assertFalse(view.steps().isEmpty());
            assertThrows(IllegalStateException.class, view::complete);
            int actionStep = 0;
            while (actionStep < view.steps().size() && view.steps().get(actionStep).actions().isEmpty()) {
                SourceObservationProgram.Step empty = view.steps().get(actionStep++);
                view.enterStep(empty); view.finishStep(empty);
            }
            assertTrue(actionStep < view.steps().size(), "The real initializer must retain its application patch");
            SourceObservationProgram.Step first = view.steps().get(actionStep);
            assertThrows(IllegalStateException.class, () -> view.consumeAction(first.actions().get(0)));
            view.enterStep(first);
            assertThrows(IllegalArgumentException.class, () -> view.enterStep(first));
            assertThrows(IllegalArgumentException.class, () -> view.finishStep(first));
            SourceObservationProgram.Action action = first.actions().get(0);
            view.consumeAction(action);
            assertThrows(IllegalArgumentException.class, () -> view.consumeAction(action));
            for (int i = 1; i < first.actions().size(); i++) view.consumeAction(first.actions().get(i));
            view.finishStep(first);
            for (int i = actionStep + 1; i < view.steps().size(); i++) {
                SourceObservationProgram.Step step = view.steps().get(i); view.enterStep(step);
                for (SourceObservationProgram.Action next : step.actions()) view.consumeAction(next);
                view.finishStep(step);
            }
            view.complete();
            assertThrows(IllegalStateException.class, view::complete);
            assertThrows(IllegalArgumentException.class, () -> view.document(new DocumentId("foreign-source")));
            assertEquals(1, fixture.initializerExecutions);
        }
    }

    @Test void returnedBodiesAndBindingListsCannotMutateThePrivateSelectedView() {
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            SourceInterpretationView view = SourceInterpretationView.from(fixture.initialization);
            DocumentId source = view.ownedDocumentIds().iterator().next();
            Node copy = view.document(source); copy.name("caller mutation");
            assertNotEquals("caller mutation", view.document(source).getName());
            assertThrows(UnsupportedOperationException.class, () -> view.bindings().add(null));
            ManagedOccurrenceBinding foreign = ManagedOccurrenceBinding.derived(fixture.initialization.program().environment().managedBindingPolicyIdentity(),
                    new DocumentId("foreign-source"), ScopeAddress.embedded("/child", 1), source, source.value(), false, null);
            assertThrows(IllegalArgumentException.class, () -> view.reconcileBindings(Collections.singletonList(foreign)));
            assertEquals(source.value(), view.blueId(source));
        }
    }

    @Test void oneRetainedPatchAndItsExactExternalRetargetRowsAreInterpretedAtomically() {
        // Transport-only state-machine witness: actual creation/workflow execution is covered
        // separately. This isolates the impossibility of validating either half against the old other half.
        try (SourceInitializationAttachmentTest.Fixture fixture = new SourceInitializationAttachmentTest.Fixture()) {
            Node oldTarget = new Node().name("old external target"), newTarget = new Node().name("new external target");
            DocumentId oldId = new DocumentId(id(oldTarget)), newId = new DocumentId(id(newTarget));
            Node before = new Node().name("transport-only retarget source").properties("child", new Node().blueId(oldId.value())).contracts(new Node());
            DocumentId source = new DocumentId(id(before));
            Node afterPatch = before.clone().properties("child", new Node().blueId(newId.value()));
            Node after = afterPatch.clone();
            after.getContracts().properties("initialized", new Node().type(new Node().blueId(RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER))
                    .properties("document", new Node().blueId(source.value())));
            String policy = fixture.initialization.program().environment().managedBindingPolicyIdentity();
            ManagedOccurrenceBinding oldRow = ManagedOccurrenceBinding.derived(policy, source, ScopeAddress.embedded("/child", 1),
                    oldId, oldId.value(), true, null);
            ManagedOccurrenceBinding newRow = ManagedOccurrenceBinding.derived(policy, source, ScopeAddress.embedded("/child", 2),
                    newId, newId.value(), true, null);
            SourceObservationProgram.Patch patch = new SourceObservationProgram.Patch(hash('b'), hash('c'), afterPatch,
                    FrozenJsonPatch.replace("/child", FrozenNode.fromNode(new Node().blueId(newId.value()))), Collections.emptyList())
                    .withResultingBindings(Collections.singletonList(newRow));
            SourceObservationProgram.Step step = new SourceObservationProgram.Step(hash('d'), hash('e'), source,
                    WorkKind.INITIALIZATION, "initialization", FrozenNode.fromResolvedNode(new Node().name("init")),
                    FrozenNode.fromResolvedNode(before), FrozenNode.fromResolvedNode(afterPatch), Collections.emptyList(),
                    Collections.singletonList(patch.patch()), true, Collections.singletonList(patch));
            SourceObservationProgram.SourceState oldState = state(oldId, oldTarget, false), newState = state(newId, newTarget, false);
            SourceObservationProgram program = new SourceObservationProgram(hash('f'), ProcessingCause.Kind.ADMISSION,
                    SourceInitialization.canonicalAdmissionCause(Collections.singleton(source)).causeIdentity(), null,
                    fixture.initialization.program().environment(), fixture.initialization.program().executionPolicy(),
                    Arrays.asList(state(source, before, false), oldState, newState), Arrays.asList(state(source, after, true), oldState, newState),
                    Collections.singleton(source), Collections.singletonList(step), Collections.emptyList(),
                    Collections.singletonList(oldRow), Collections.singletonList(newRow), Collections.singletonList(component(source, before, false)),
                    Collections.singletonList(component(source, after, true)));
            SourceInitialization initialization = SourceInitialization.fromProgram(program);
            SourceInterpretationView patchFirst = SourceInterpretationView.from(initialization); patchFirst.enterStep(step);
            assertThrows(IllegalArgumentException.class, () -> patchFirst.consumeAction(patch));
            SourceInterpretationView rowFirst = SourceInterpretationView.from(initialization); rowFirst.enterStep(step);
            assertThrows(IllegalArgumentException.class, () -> rowFirst.reconcileBindings(Collections.singletonList(newRow)));
            SourceInterpretationView atomic = SourceInterpretationView.from(initialization); atomic.enterStep(step);
            atomic.consumeAction(patch, Collections.singletonList(newRow)); atomic.finishStep(step); atomic.complete();
            assertEquals(newId.value(), atomic.document(source).getProperties().get("child").getBlueId());
            assertEquals(id(after), atomic.blueId(source));
        }
    }

    private static void replay(SourceInterpretationView view) {
        for (SourceObservationProgram.Step step : view.steps()) {
            view.enterStep(step);
            for (SourceObservationProgram.Action action : step.actions()) view.consumeAction(action);
            view.finishStep(step);
        }
        view.complete();
    }
    private static String id(Node node) { return DirectBlueIdCalculator.calculateBlueId(node); }
    private static String hash(char value) { char[] text = new char[64]; Arrays.fill(text, value); return "sha256:" + new String(text); }
    private static SourceObservationProgram.SourceState state(DocumentId source, Node body, boolean initialized) {
        return new SourceObservationProgram.SourceState(source, id(body), 0, initialized, FrozenNode.fromResolvedNode(body));
    }
    private static ComponentSnapshot component(DocumentId source, Node body, boolean initialized) {
        return ClosureEvidenceFactory.acyclicComponent(new ManagedDocumentSnapshot(source, id(body), body, initialized, false, true, 0, 0));
    }
}
