package blue.contracts.closure;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free template shape smoke; this is not implementation conformance. */
public final class ReferenceCycleMain {
    public static void main(String[] args) {
        DocumentId a=new DocumentId("a"); DocumentId b=new DocumentId("b");
        String bmpPrivateUse = "\uE000";
        String supplementaryEmoji = "\uD83D\uDE00";
        String decomposedARing = "A\u030A";
        require(CanonicalOrders.compareUnicodeScalars("B", "\u00C5") < 0,
                "NFC scalar order");
        boolean nonNfcComparatorTokenRejected=false;
        try {
            CanonicalOrders.compareUnicodeScalars(decomposedARing, "B");
        } catch (IllegalArgumentException expected) {
            nonNfcComparatorTokenRejected=true;
        }
        require(nonNfcComparatorTokenRejected,
                "portable comparator rejects non-NFC tokens");
        boolean nonNfcDocumentIdRejected=false;
        try {
            new DocumentId(decomposedARing);
        } catch (IllegalArgumentException expected) {
            nonNfcDocumentIdRejected=true;
        }
        require(nonNfcDocumentIdRejected, "DocumentId rejects non-NFC text");
        require(new DocumentId(bmpPrivateUse).compareTo(
                new DocumentId(supplementaryEmoji)) < 0,
                "DocumentId Unicode scalar order");
        require(new ManagedScopeKey(a, "/" + bmpPrivateUse, 1).compareTo(
                new ManagedScopeKey(a, "/" + supplementaryEmoji, 1)) < 0,
                "managed scope Unicode scalar order");
        boolean nonNfcManagedScopeRejected=false;
        try {
            new ManagedScopeKey(a, "/" + decomposedARing, 1);
        } catch (IllegalArgumentException expected) {
            nonNfcManagedScopeRejected=true;
        }
        require(nonNfcManagedScopeRejected,
                "managed scope rejects non-NFC path");
        boolean malformedManagedScopeRejected=false;
        try {
            new ManagedScopeKey(a, "/bad~2", 1);
        } catch (IllegalArgumentException expected) {
            malformedManagedScopeRejected=true;
        }
        require(malformedManagedScopeRejected,
                "managed scope rejects malformed RFC 6901 escape");
        boolean malformedCandidatePathRejected=false;
        try {
            new AdmissionCandidate.CandidateOccurrenceBinding(
                    "occurrence", "binding", "policy", a, "/bad~2", 1,
                    b, "target-blue-id", false);
        } catch (IllegalArgumentException expected) {
            malformedCandidatePathRejected=true;
        }
        require(malformedCandidatePathRejected,
                "candidate occurrence rejects malformed sourcePath");
        boolean zeroCandidateGenerationRejected=false;
        try {
            new AdmissionCandidate.CandidateOccurrenceBinding(
                    "occurrence", "binding", "policy", a, "/child", 0,
                    b, "target-blue-id", false);
        } catch (IllegalArgumentException expected) {
            zeroCandidateGenerationRejected=true;
        }
        require(zeroCandidateGenerationRejected,
                "candidate occurrence generation starts at one");
        boolean nonRootDirectDeliveryRejected=false;
        try {
            new DirectLogicalDelivery(
                    a, "/" + bmpPrivateUse, 1,
                    "channel", "delivery", 0);
        } catch (IllegalArgumentException expected) {
            nonRootDirectDeliveryRejected=true;
        }
        require(nonRootDirectDeliveryRejected,
                "Contracts 1.0 direct delivery is Root-scoped");
        require(new DirectLogicalDelivery(
                a, "/", 0, bmpPrivateUse, "delivery", 0)
                .compareTo(new DirectLogicalDelivery(
                        a, "/", 0, supplementaryEmoji,
                        "delivery", 0)) < 0,
                "direct delivery channel Unicode scalar order");
        require(new DirectLogicalDelivery(
                a, "/", 0, "channel", bmpPrivateUse, 0)
                .compareTo(new DirectLogicalDelivery(
                        a, "/", 0, "channel",
                        supplementaryEmoji, 0)) < 0,
                "direct delivery logical key Unicode scalar order");
        boolean nonNfcDirectChannelRejected=false;
        try {
            new DirectLogicalDelivery(
                    a, "/", 0, decomposedARing, "delivery", 0);
        } catch (IllegalArgumentException expected) {
            nonNfcDirectChannelRejected=true;
        }
        require(nonNfcDirectChannelRejected,
                "direct delivery rejects non-NFC channel key");
        boolean nonNfcLogicalDeliveryRejected=false;
        try {
            new DirectLogicalDelivery(
                    a, "/", 0, "channel", decomposedARing, 0);
        } catch (IllegalArgumentException expected) {
            nonNfcLogicalDeliveryRejected=true;
        }
        require(nonNfcLogicalDeliveryRejected,
                "direct delivery rejects non-NFC logical key");
        boolean nonNfcAdmissionLabelRejected=false;
        try {
            new AdmissionCause(
                    "cause", AdmissionKind.TOP_LEVEL_ADMISSION,
                    decomposedARing, null, null, "policy");
        } catch (IllegalArgumentException expected) {
            nonNfcAdmissionLabelRejected=true;
        }
        require(nonNfcAdmissionLabelRejected,
                "admission cause rejects non-NFC policy label");
        boolean nonNfcCheckpointDiscriminatorRejected=false;
        try {
            new CheckpointDomain(
                    "runtime-blue-id",
                    Collections.singletonList("source-blue-id"),
                    decomposedARing);
        } catch (IllegalArgumentException expected) {
            nonNfcCheckpointDiscriminatorRejected=true;
        }
        require(nonNfcCheckpointDiscriminatorRejected,
                "checkpoint domain rejects non-NFC discriminator");
        boolean nonRootChannelOccurrenceRejected=false;
        try {
            new ClosureProcessResult.ChannelOccurrence(
                    "occurrence", a, "/nested", 1,
                    "channel", "runtime", "header");
        } catch (IllegalArgumentException expected) {
            nonRootChannelOccurrenceRejected=true;
        }
        require(nonRootChannelOccurrenceRejected,
                "Contracts 1.0 ChannelOccurrence is Root-scoped");
        boolean nonRootWorkIdentityRejected=false;
        try {
            new WorkOccurrence(
                    0, WorkKind.EXTERNAL_DELIVERY, a, "channel", null, null,
                    "nested-scope", "source", "work",
                    managedScopeKey -> "root-scope");
        } catch (IllegalArgumentException expected) {
            nonRootWorkIdentityRejected=true;
        }
        require(nonRootWorkIdentityRejected,
                "Contracts 1.0 WorkOccurrence is Root-scoped");
        boolean nonRootGasContextRejected=false;
        try {
            new GasCharge.Context(
                    a, "/nested", Long.valueOf(1L), null,
                    null, null, "work", "synthetic");
        } catch (IllegalArgumentException expected) {
            nonRootGasContextRejected=true;
        }
        require(nonRootGasContextRejected,
                "Contracts 1.0 gas context is Root-scoped");
        Map<DocumentId,List<DocumentId>> graph=new HashMap<DocumentId,List<DocumentId>>();
        graph.put(a,Collections.singletonList(b)); graph.put(b,Collections.singletonList(a));
        List<List<DocumentId>> scc=new SccPartitioner().partition(graph);
        require(scc.size()==1 && scc.get(0).equals(Arrays.asList(a,b)),"SCC");
        DocumentId z=new DocumentId("z");
        Map<DocumentId,List<DocumentId>> dependencyGraph=
                new HashMap<DocumentId,List<DocumentId>>();
        dependencyGraph.put(a,Collections.singletonList(z));
        dependencyGraph.put(z,Collections.<DocumentId>emptyList());
        List<List<DocumentId>> dependencyOrder=
                new SccPartitioner().partition(dependencyGraph);
        require(dependencyOrder.equals(Arrays.asList(
                Collections.singletonList(z),Collections.singletonList(a))),
                "target SCC precedes embedding source SCC");
        boolean immutableScc=false;
        try {
            scc.get(0).add(a);
        } catch (UnsupportedOperationException expected) {
            immutableScc=true;
        }
        require(immutableScc,"immutable SCC result");

        boolean shortSourceOrderRejected=false;
        try {
            new ExternalEventCause(
                    "cause",
                    new Object(),
                    "event",
                    Arrays.asList(1,2),
                    "policy");
        } catch (IllegalArgumentException expected) {
            shortSourceOrderRejected=true;
        }
        require(shortSourceOrderRejected,"external sourceOrder minItems");

        Map<DocumentId,Long> local=new HashMap<DocumentId,Long>(); local.put(b,Long.valueOf(30));
        SharedGasMeter meter=new SharedGasMeter(
                new ExecutionPolicy("sha256:test",100,local,"reference-cycle"),
                (namespace,counter,quantity,weight,subtotal,cap,remaining,owner) ->
                        "sha256:rejected-charge");
        meter.charge(workContext(a, "work-a-0", "A external"),
                GasCharge.Namespace.PROCESSOR,"handlerCall",1,20,
                new SharedGasMeter.WorkOwner("work-a-0"));
        meter.charge(workContext(b, "work-b-0", "B X"),
                GasCharge.Namespace.PROCESSOR,"handlerCall",1,20,
                new SharedGasMeter.WorkOwner("work-b-0"));
        meter.charge(workContext(a, "work-a-1", "A Y"),
                GasCharge.Namespace.PROCESSOR,"handlerCall",1,20,
                new SharedGasMeter.WorkOwner("work-a-1"));
        require(meter.used()==60,"finite gas");
        require(meter.trace().get(0).namespace()==GasCharge.Namespace.PROCESSOR
                && "/".equals(meter.trace().get(0).scopePath())
                && "source".equals(meter.trace().get(0).contractKey()),
                "complete gas context");
        boolean failed=false;
        try {
            meter.charge(workContext(b, "work-b-1", "loop"),
                    GasCharge.Namespace.PROCESSOR,"handlerCall",1,20,
                    new SharedGasMeter.WorkOwner("work-b-1"));
        } catch (SharedGasMeter.GasLimitExceeded expected) {
            failed=true;
            require(expected.rejectedCharge().applicableCap()
                    instanceof SharedGasMeter.LocalCap,"local cap evidence");
            require(expected.rejectedCharge().owner()
                    instanceof SharedGasMeter.WorkOwner,"work owner evidence");
        }
        require(failed,"local/shared gas");
        require(meter.used()==60,"rejected charge absent");

        Map<DocumentId,String> memberBlueIds=new java.util.LinkedHashMap<DocumentId,String>();
        memberBlueIds.put(a,"MASTER#0"); memberBlueIds.put(b,"MASTER#1");
        TentativeFinalizationResult semanticFinalization=
                new TentativeFinalizationResult("MASTER",memberBlueIds,1);
        TentativeFinalizationResult.ProcessorEvidence initializationEvidence=
                new TentativeFinalizationResult.ProcessorEvidence(
                        0,
                        TentativeFinalizationResult.Boundary.initializationBatch(3),
                        semanticFinalization);
        require(initializationEvidence.boundary()
                instanceof TentativeFinalizationResult.InitializationBatchBoundary,
                "initialization boundary");
        require(((TentativeFinalizationResult.InitializationBatchBoundary)
                initializationEvidence.boundary()).afterWorkOrdinal()==3,
                "initialization boundary work ordinal");

        Object a6 = new Object();
        ManagedRevisionCause e56 = new ManagedRevisionCause(
                "C56",
                "b-a",
                a,
                5,
                6,
                "A5",
                "A6",
                a6,
                "SOURCE-CAUSE-6",
                "R56",
                exactNode -> exactNode == a6 ? "A6" : "unexpected",
                (child, from, to, before, after, sourceCause) -> "R56",
                (occurrence, child, from, to, before, after, sourceCause, receipt) ->
                        "C56");
        require(e56.kind().equals("managed-revision")
                        && e56.toEpoch() == 6
                        && e56.sourceRevisionReceiptIdentity().equals("R56"),
                "one managed revision cause");

        AdmissionCause nullableAdmission = new AdmissionCause(
                "admission-cause",
                AdmissionKind.TOP_LEVEL_ADMISSION,
                "top-level",
                null,
                null,
                "admission-policy");
        require(nullableAdmission.triggeringEventBlueId() == null
                        && nullableAdmission.parentTransitionIdentity() == null,
                "required nullable admission fields");

        boolean invalidEpoch=false;
        try {
            new ManagedRevisionCause(
                    "invalid-cause",
                    "b-a",
                    a,
                    CanonicalOrders.MAX_SAFE_INTEGER,
                    CanonicalOrders.MAX_SAFE_INTEGER,
                    "before",
                    "after",
                    new Object(),
                    "source-cause",
                    "receipt",
                    exactNode -> "after",
                    (child, from, to, before, after, sourceCause) -> "receipt",
                    (occurrence, child, from, to, before, after, sourceCause, receipt) ->
                            "invalid-cause");
        } catch (IllegalArgumentException expected) {
            invalidEpoch=true;
        }
        require(invalidEpoch,"safe historical epoch");

        boolean invalidNoOp=false;
        CheckpointDomain checkpointDomain = new CheckpointDomain(
                "type", Collections.singletonList("source"), null);
        try {
            new ClosureProcessResult.CheckpointWrite(
                    0,
                    "scope",
                    ManagedScopeKey.closureRoot(a),
                    managedScopeKey -> "scope",
                    "channel",
                    true,
                    "domain",
                    checkpointDomain,
                    "subject",
                    true,
                    "domain",
                    checkpointDomain,
                    "subject",
                    value -> "domain");
        } catch (IllegalArgumentException expected) {
            invalidNoOp=true;
        }
        require(invalidNoOp,"checkpoint no-op");
        System.out.println("BLUE_CONTRACTS_CLOSURE_TEMPLATE_SHAPE_SMOKE_OK");
    }
    private static GasCharge.Context workContext(
            DocumentId documentId, String workIdentity, String reason) {
        return new GasCharge.Context(
                documentId,
                "/",
                Long.valueOf(0L),
                Long.valueOf(1L),
                "source",
                "work/0",
                workIdentity,
                reason);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
