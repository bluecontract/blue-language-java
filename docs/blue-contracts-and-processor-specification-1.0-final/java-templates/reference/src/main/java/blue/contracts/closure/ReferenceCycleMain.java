package blue.contracts.closure;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Dependency-free smoke proof for ordering, SCCs, one shared gas meter and rollback boundary. */
public final class ReferenceCycleMain {
    public static void main(String[] args) {
        DocumentId a=new DocumentId("a"); DocumentId b=new DocumentId("b");
        Map<DocumentId,List<DocumentId>> graph=new HashMap<DocumentId,List<DocumentId>>();
        graph.put(a,Collections.singletonList(b)); graph.put(b,Collections.singletonList(a));
        List<List<DocumentId>> scc=new SccPartitioner().partition(graph);
        require(scc.size()==1 && scc.get(0).equals(Arrays.asList(a,b)),"SCC");
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

        ManagedRevisionEvidence e56=new ManagedRevisionEvidence(
                a,5,6,"A5","A6",new Object(),"T56",Arrays.asList(1,2,3));
        ManagedRevisionEvidence e67=new ManagedRevisionEvidence(a,6,7,"A6","A7",new Object(),"T67");
        new RevisionReconciliationPlan(a,5,7,Arrays.asList(e56,e67));
        require(e56.sourceOrder().equals(Arrays.<Object>asList(1,2,3)),
                "historical source order");

        boolean invalidEpoch=false;
        try {
            new ManagedRevisionEvidence(
                    a,
                    CanonicalOrders.MAX_SAFE_INTEGER,
                    CanonicalOrders.MAX_SAFE_INTEGER,
                    "before",
                    "after",
                    new Object(),
                    "transition");
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
        System.out.println("BLUE_CONTRACTS_CLOSURE_REFERENCE_OK");
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
