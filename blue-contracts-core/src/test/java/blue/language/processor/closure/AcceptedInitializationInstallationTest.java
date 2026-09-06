package blue.language.processor.closure;

import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.CyclicMemberFinalization;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorStatus;
import blue.language.provider.CyclicSetProof;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Capability/transport tests. Only the runtime's actual creation boundary mints execution authority. */
class AcceptedInitializationInstallationTest {
    private static final FrozenNodeEvidenceCodec.Limits LIMITS = FrozenNodeEvidenceCodec.Limits.defaults();
    private static final DocumentId CREATOR = new DocumentId("creator");

    @Test
    void actualCanonicalInit0BindsProducingOperationAndColdRestoresWithoutExecution() {
        SourceObservationProgram program = admitted("canonical source");
        SourceInitialization initialization = SourceInitialization.fromProgram(program);
        SourceObservationProgram.SourceState result = program.sourceResults().get(0);
        ManagedReadPin pin = ManagedReadPin.fromExactEvidence(result.documentId(), result.blueId(), result.document(), null);
        AcceptedInitializationInstallation accepted = accepted(selection(CREATOR, result.documentId(), hash('a'), SameOriginAttachmentPolicy.Mode.FULL_HISTORY),
                program.invocationIdentity(), pin, hash('b'));
        accepted.verifySourceInitialization(initialization);
        assertEquals(program.invocationIdentity(), accepted.sourceInitializationOperationIdentity());
        assertEquals(ClosureIdentityService.INSTANCE.observationInitializationCompletionSiteIdentity(program.invocationIdentity()), accepted.sourceCompletionSiteIdentity());
        Map<String, byte[]> blobs = new HashMap<>();
        String root = AcceptedInitializationInstallationCodec.encode(accepted, blobs::put, LIMITS);
        AcceptedInitializationInstallation restored = AcceptedInitializationInstallationCodec.decode(root, blobs::get, LIMITS);
        restored.verifySourceInitialization(initialization);
        assertEquals(accepted.identity(), restored.identity());
        assertEquals(pin.blueId(), restored.selectedView().blueId());
        assertEquals(root, AcceptedInitializationInstallationCodec.encode(restored, blobs::put, LIMITS));
        assertNotEquals(accepted.identity(), accepted(accepted.selection(), program.invocationIdentity(), pin, hash('c')).identity());
        assertThrows(IllegalArgumentException.class, () -> restored.verifySourceInitialization(SourceInitialization.fromProgram(admitted("another source"))));
    }

    @Test
    void wrongModeCompletionLineageOrNonInitializedSelectedViewFailsClosed() {
        SourceObservationProgram program = admitted("source validation");
        DocumentId source = program.ownedDocumentIds().iterator().next();
        SourceObservationProgram.SourceState result = program.sourceResults().get(0);
        ManagedReadPin pin = ManagedReadPin.fromExactEvidence(source, result.blueId(), result.document(), null);
        SameOriginAttachmentPolicy.Selection full = selection(CREATOR, source, hash('a'), SameOriginAttachmentPolicy.Mode.FULL_HISTORY);
        assertThrows(IllegalArgumentException.class, () -> accepted(selection(CREATOR, source, hash('a'), SameOriginAttachmentPolicy.Mode.FROM_NOW), program.invocationIdentity(), pin, hash('b')));
        assertThrows(IllegalArgumentException.class, () -> AcceptedInitializationInstallation.fromCanonicalSite(full, hash('b'), hash('c'), program.invocationIdentity(), hash('d'), pin));
        assertThrows(IllegalArgumentException.class, () -> accepted(full, program.invocationIdentity(), ManagedReadPin.fromExactEvidence(CREATOR, pin.blueId(), pin.document(), null), hash('b')));
        SourceObservationProgram.SourceState authored = program.sourcePredecessors().get(0);
        AcceptedInitializationInstallation wrong = accepted(full, program.invocationIdentity(), ManagedReadPin.fromExactEvidence(source, authored.blueId(), authored.document(), null), hash('b'));
        assertThrows(IllegalArgumentException.class, () -> wrong.verifySourceInitialization(SourceInitialization.fromProgram(program)));
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("selectionIdentity", full.identity()); fields.put("creatorSeedIdentity", hash('a'));
        fields.put("creatorPatchSite", hash('b')); fields.put("sourceInitializationOperationIdentity", program.invocationIdentity());
        fields.put("sourceCompletionSiteIdentity", ClosureIdentityService.INSTANCE.observationInitializationCompletionSiteIdentity(program.invocationIdentity()));
        fields.put("selectedBlueId", pin.blueId()); fields.put("physicalHeadEpoch", 99L);
        assertThrows(IllegalArgumentException.class, () -> ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.ACCEPTED_INITIALIZATION_INSTALLATION, fields));
    }

    @Test
    void cyclicSelectedViewKeepsExactProofUnderSharedColdFragmentBudget() {
        Node placeholder = new Node().name("cyclic init view").properties("self", new Node().blueId("this#0"));
        CyclicSetFinalization finalized = new CircularSetIdentityCalculator().finalizeCyclicSet(Collections.singletonList(placeholder));
        CyclicMemberFinalization member = finalized.membersInCanonicalOrder().get(0); DocumentId source = new DocumentId("cyclic source");
        Node body = member.canonicalMemberBody(); body.getAsNode("/self").blueId(member.finalBlueId());
        ManagedReadPin pin = ManagedReadPin.fromExactEvidence(source, member.finalBlueId(), body, CyclicSetProof.fromDeclaredPlaceholderSet(finalized.canonicalMemberBodies()));
        AcceptedInitializationInstallation accepted = accepted(selection(CREATOR, source, hash('a'), SameOriginAttachmentPolicy.Mode.FULL_HISTORY), hash('b'), pin, hash('c'));
        Map<String, byte[]> blobs = new HashMap<>();
        String root = AcceptedInitializationInstallationCodec.encode(accepted, blobs::put, LIMITS);
        AcceptedInitializationInstallation cold = AcceptedInitializationInstallationCodec.decode(root, blobs::get, LIMITS);
        assertEquals(accepted.identity(), cold.identity()); assertTrue(cold.selectedView().cyclicProof().isPresent());
        body.name("caller mutation"); cold.selectedView().document().name("accessor mutation");
        assertEquals("cyclic init view", cold.selectedView().document().getName());
        assertThrows(IllegalArgumentException.class, () -> ManagedReadPin.fromExactEvidence(source, member.finalBlueId(), pin.document(), null));
    }

    @Test
    void recorderDropsInvalidatedInstallationAndKeepsCanonicalOwnedEncounterOrder() {
        SourceObservationProgram init = admitted("recorder source"); SourceObservationProgram.SourceState result = init.sourceResults().get(0);
        ManagedReadPin pin = ManagedReadPin.fromExactEvidence(result.documentId(), result.blueId(), result.document(), null);
        AcceptedInitializationInstallation a = accepted(selection(CREATOR, result.documentId(), hash('a'), SameOriginAttachmentPolicy.Mode.FULL_HISTORY), init.invocationIdentity(), pin, hash('b'));
        AcceptedInitializationInstallation b = accepted(selection(new DocumentId("other creator"), result.documentId(), hash('c'), SameOriginAttachmentPolicy.Mode.FULL_HISTORY), init.invocationIdentity(), pin, hash('d'));
        AcceptedInitializationInstallation c = accepted(selection(CREATOR, result.documentId(), hash('e'), SameOriginAttachmentPolicy.Mode.FULL_HISTORY), init.invocationIdentity(), pin, hash('f'));
        SourceObservationRecorder recorder = new SourceObservationRecorder();
        Object invalidated = new Object(), first = new Object(), second = new Object();
        recorder.acceptedInitialization(a, invalidated); recorder.acceptedInitialization(b, first); recorder.acceptedInitialization(c, second);
        Set<Object> valid = Collections.newSetFromMap(new IdentityHashMap<>()); valid.add(second); valid.add(first);
        assertEquals(Arrays.asList(b, c), recorder.captureOwned(new HashSet<>(Arrays.asList(CREATOR, b.selection().creatorLineage())), valid).acceptedInitializations());
        List<AcceptedInitializationInstallation> owned = recorder.captureOwned(Collections.singleton(CREATOR), valid).acceptedInitializations();
        assertEquals(Collections.singletonList(c), owned); assertThrows(UnsupportedOperationException.class, owned::clear);
    }

    @Test
    void retainedProgramCodecBindsItsOwnedInitializationInstallations() {
        SourceObservationProgram creator = admitted("transport-only creator"), source = admitted("transport-only initialized source");
        DocumentId creatorId = creator.ownedDocumentIds().iterator().next(); SourceObservationProgram.SourceState initialized = source.sourceResults().get(0);
        ManagedReadPin pin = ManagedReadPin.fromExactEvidence(initialized.documentId(), initialized.blueId(), initialized.document(), null);
        AcceptedInitializationInstallation accepted = accepted(selection(creatorId, initialized.documentId(), hash('a'), SameOriginAttachmentPolicy.Mode.FULL_HISTORY), source.invocationIdentity(), pin, hash('b'));
        // Data-only construction: this tests retention/ownership, not an actual dynamic creation workflow.
        SourceObservationProgram retained = retaining(creator, source, Collections.singletonList(accepted));
        Map<String, byte[]> blobs = new HashMap<>();
        String root = SourceObservationProgramCodec.encode(retained, blobs::put, LIMITS);
        SourceObservationProgram cold = SourceObservationProgramCodec.decode(root, blobs::get, LIMITS);
        assertEquals(accepted.identity(), cold.acceptedInitializations().get(0).identity());
        cold.acceptedInitializations().get(0).verifySourceInitialization(SourceInitialization.fromProgram(cold.borrowedPrograms().get(0)));
        assertEquals(root, SourceObservationProgramCodec.encode(cold, blobs::put, LIMITS));
        assertThrows(IllegalArgumentException.class, () -> retaining(creator, source, Arrays.asList(accepted, accepted)));
        AcceptedInitializationInstallation foreign = accepted(selection(CREATOR, initialized.documentId(), hash('a'), SameOriginAttachmentPolicy.Mode.FULL_HISTORY), source.invocationIdentity(), pin, hash('b'));
        assertThrows(IllegalArgumentException.class, () -> retaining(creator, source, Collections.singletonList(foreign)));
    }

    private static SourceObservationProgram retaining(SourceObservationProgram creator, SourceObservationProgram source, List<AcceptedInitializationInstallation> installations) {
        return new SourceObservationProgram(creator.invocationIdentity(), creator.causeKind(), creator.causeIdentity(), creator.externalCause(),
                creator.environment(), creator.executionPolicy(), creator.sourcePredecessors(), creator.sourceResults(), creator.ownedDocumentIds(), creator.steps(),
                creator.referenceProjections(), creator.sourceBeforeBindings(), creator.sourceAfterBindings(), creator.sourceBeforeComponents(), creator.sourceAfterComponents(),
                Collections.singletonList(source), creator.sourceReadPins(), creator.acceptedViews(), null, installations);
    }
    private static AcceptedInitializationInstallation accepted(SameOriginAttachmentPolicy.Selection selection, String initialization, ManagedReadPin pin, String site) {
        return AcceptedInitializationInstallation.fromCanonicalSite(selection, hash('f'), site, initialization,
                ClosureIdentityService.INSTANCE.observationInitializationCompletionSiteIdentity(initialization), pin);
    }
    private static SameOriginAttachmentPolicy.Selection selection(DocumentId creator, DocumentId source, String occurrence, SameOriginAttachmentPolicy.Mode mode) {
        return new SameOriginAttachmentPolicy.Selection(mode, creator, occurrence, source, source.value());
    }
    private static SourceObservationProgram admitted(String label) {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            Node authored = new Node().name(label).contracts(new Node());
            DocumentId source = new DocumentId(DirectBlueIdCalculator.calculateBlueId(authored));
            ManagedDocumentSnapshot initial = new ManagedDocumentSnapshot(source, source.value(), authored, false, false, true, 0, 0);
            AffectedClosureSnapshot snapshot = ClosureEvidenceFactory.affectedClosure(0, Collections.singletonList(initial), Collections.emptyList(),
                    Collections.singletonList(ClosureEvidenceFactory.acyclicComponent(initial)), Collections.singletonList(source));
            ClosureEnvironment environment = ClosureEvidenceFactory.environment(processor, hash('a'), hash('b'), "identity", "binding", "provider", "order", "limits", GasSchedule.contracts10().portableLimits());
            ClosureInvocationInput invocation = ClosureEvidenceFactory.admitClosure(snapshot, ClosureEvidenceFactory.admissionCause(AdmissionKind.TOP_LEVEL_ADMISSION,
                    "canonical-source:" + source.value(), null, null, "FULL_HISTORY"), null, ClosureEvidenceFactory.executionPolicy(100000, Collections.emptyMap(), "fixed"), environment);
            final SourceObservationProgram[] program = new SourceObservationProgram[1];
            try (BlueClosureContracts contracts = new BlueClosureContracts(processor, new ClosureExecutionObserver() {
                @Override public boolean capturesSourceObservationProgram() { return true; }
                @Override public void onSourceObservationProgram(SourceObservationProgram value) { program[0] = value; }
                @Override public void onExecutionEvidence(ClosureImplementationEvidence evidence) { }
            })) {
                ClosureAttemptResult attempt = contracts.admitExternalScope(invocation, Collections.singleton(source));
                assertTrue(attempt.isComplete()); assertEquals(ProcessorStatus.SUCCESS, attempt.processResult().status());
                return Objects.requireNonNull(program[0]);
            }
        }
    }
    private static String hash(char value) { char[] chars = new char[64]; Arrays.fill(chars, value); return "sha256:" + new String(chars); }
}
