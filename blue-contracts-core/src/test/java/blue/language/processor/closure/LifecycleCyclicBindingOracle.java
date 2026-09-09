package blue.language.processor.closure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Exact reviewed cyclic binding migration; frozen semantic/gas projections remain authoritative. */
final class LifecycleCyclicBindingOracle {
    private static final String OLD_FINALIZER = "sha256:0ea9ccf1f8da23be8f70368565c3322c1d25aa88ba711fa06456aed2a842942c";
    private static final String OLD_VERIFIER = "sha256:581619ff2a6909590c8740887d80d0d24659673e5af2de713a181c6664709c84";
    private static final String REVIEWED_FINALIZER = "sha256:f8e41baf14343d05b065745c3d1c569dd15e3fbae09331757efd2f7ffbf6f52b";
    private static final String REVIEWED_VERIFIER = "sha256:0d1a9ab0ee17712521cccb8d988d838c5b7de949b1d168a1c204ac2538e71834";
    private LifecycleCyclicBindingOracle() { }

    static String duplicate(String frozen, ClosureInvocationInput input) {
        String[] old = frozen.split("\\|", -1); assertEquals(4,old.length);
        String invocation = invocation(input,old[0]);
        String first = event(invocation,0L,old[1]), second = event(invocation,1L,old[1]);
        assertEquals(old[2],event(old[0],0L,old[1])); assertEquals(old[3],event(old[0],1L,old[1]));
        return invocation + "|" + old[1] + "|" + first + "|" + second;
    }

    static String gas(String frozen, ClosureInvocationInput input, ClosureProcessResult result,
            ClosureImplementationEvidence evidence) {
        String[] old = frozen.split("\\|", -1);
        String invocation = invocation(input,old[0]); assertEquals(invocation,result.invocationIdentity());
        Map<String,String> currentToOld = new LinkedHashMap<>();
        currentToOld.put(invocation,old[0]);
        for (ClosureWorkOccurrence work : evidence.workTrace()) {
            String historicalSource = work.sourceOccurrenceIdentity();
            if (work.kind() == WorkKind.EMBEDDED_EVENT) {
                assertNotNull(work.eventBlueId()); assertNotNull(work.occurrenceOrdinal());
                assertEquals(work.sourceOccurrenceIdentity(),event(invocation,work.occurrenceOrdinal(),work.eventBlueId()));
                historicalSource=event(old[0],work.occurrenceOrdinal(),work.eventBlueId());
            } else {
                // The original admission executes the initialized lifecycle handler as
                // a distinct work occurrence with the same exact admission cause.
                assertTrue(work.kind() == WorkKind.INITIALIZATION || work.kind() == WorkKind.LIFECYCLE,
                        "This fixed loop oracle permits only initialization and its lifecycle handler");
                assertEquals(input.cause().causeIdentity(),historicalSource,"Initialization retains its exact original cause");
            }
            String currentWork=work(invocation,work,work.sourceOccurrenceIdentity());
            assertEquals(work.workIdentity(),currentWork,"Independent current work constructor");
            String oldWork=work(old[0],work,historicalSource);
            assertNull(currentToOld.put(currentWork,oldWork),"Work identity occurs once");
        }
        List<Object> currentTrace=new ArrayList<>(),historicalTrace=new ArrayList<>();
        for (GasTraceEntry charge : result.gasTrace()) {
            Map<String,Object> current=charge(charge),historical=new LinkedHashMap<>(current);
            if (charge.workOccurrenceId()!=null) {
                String oldWork=currentToOld.get(charge.workOccurrenceId());assertNotNull(oldWork,"Meter owns a real captured work");
                historical.put("workOccurrenceId",oldWork);
            }
            currentTrace.add(current);historicalTrace.add(historical);
        }
        String currentGas=identity("blue-contracts-gas-trace/1.0",currentTrace);
        String historicalGas=identity("blue-contracts-gas-trace/1.0",historicalTrace);
        assertEquals(result.gasTraceIdentity(),currentGas,"Independent complete current trace");
        assertEquals(old[3],historicalGas,"Every historical charge, weight, quantity, owner and order is frozen");
        currentToOld.put(currentGas,historicalGas);
        RejectedCharge rejected=result.rejectedCharge();
        assertEquals(RejectedCharge.ApplicableCap.Kind.SHARED,rejected.applicableCap().kind());
        assertEquals(RejectedCharge.Owner.Kind.WORK,rejected.owner().kind());
        String rejectedWork=rejected.owner().workOccurrenceIdentity();
        assertNotNull(currentToOld.get(rejectedWork),"Rejected charge binds a real captured work");
        String currentRejected=identity("blue-contracts-rejected-charge/1.0",rejected(rejected,rejectedWork));
        String historicalRejected=identity("blue-contracts-rejected-charge/1.0",rejected(rejected,currentToOld.get(rejectedWork)));
        assertEquals(rejected.rejectedChargeIdentity(),currentRejected);
        currentToOld.put(currentRejected,historicalRejected);
        String rebound=frozen;
        // Each replacement is an independently derived old/new identity, never an XML actual-value copy.
        for (Map.Entry<String,String> binding : currentToOld.entrySet())
            rebound=rebound.replace(binding.getValue(),binding.getKey());
        return rebound;
    }

    private static String invocation(ClosureInvocationInput input,String oldInvocation) {
        ClosureEnvironment e=input.environment();
        assertEquals(REVIEWED_FINALIZER,e.cyclicFinalizerIdentity(),"Future cyclic changes require their own reviewed binding");
        assertEquals(REVIEWED_VERIFIER,e.cyclicProofVerifierIdentity(),"Future cyclic changes require their own reviewed binding");
        List<Object> documents=new ArrayList<>();
        for (ManagedDocumentSnapshot d : input.snapshot().managedDocuments()) documents.add(object(
                "documentId",d.documentId().value(),"blueId",d.blueId(),"initialized",d.initialized(),
                "terminated",d.terminated(),"publicRoot",d.publicRoot(),"epoch",d.epoch(),"componentGeneration",d.componentGeneration()));
        Map<String,Object> value=object("operation",input.operation().wireValue(),"causeIdentity",input.cause().causeIdentity(),
                "admissionCandidateIdentity",input.admissionCandidateIdentity(),"inputGraphGeneration",input.snapshot().graphGeneration(),
                "inputClosureIdentity",input.snapshot().closureIdentity(),"documents",documents,
                "directDeliverySnapshotIdentity",input.directDeliverySnapshotIdentity(),"occurrenceBindingSetIdentity",input.snapshot().occurrenceBindingSetIdentity(),
                "runtimeRegistryIdentity",e.runtimeRegistryIdentity(),"gasPolicyIdentity",input.executionPolicy().identity(),
                "cyclicFinalizerIdentity",e.cyclicFinalizerIdentity(),"cyclicProofVerifierIdentity",e.cyclicProofVerifierIdentity(),
                "blueLanguageSpecificationIdentity",e.blueLanguageSpecificationIdentity(),"contractsSpecificationIdentity",e.contractsSpecificationIdentity(),
                "managedDocumentIdentityPolicyIdentity",e.managedDocumentIdentityPolicyIdentity(),"managedBindingPolicyIdentity",e.managedBindingPolicyIdentity(),
                "exactNodeProviderDomainIdentity",e.exactNodeProviderDomainIdentity(),"externalOrderPolicyIdentity",e.externalOrderPolicyIdentity(),
                "gasManifestIdentity",e.gasManifestIdentity(),"portableLimitPolicyIdentity",e.portableLimitPolicyIdentity());
        String current=identity("blue-contracts-invocation/1.0",value);assertEquals(input.invocationIdentity(),current);
        value.put("cyclicFinalizerIdentity",OLD_FINALIZER);value.put("cyclicProofVerifierIdentity",OLD_VERIFIER);
        assertEquals(oldInvocation,identity("blue-contracts-invocation/1.0",value),"No other fixture input or environment binding may drift");
        return current;
    }
    private static String event(String invocation,long ordinal,String blueId) {
        return identity("blue-contracts-event-occurrence/1.0",object("invocationIdentity",invocation,"eventOccurrenceOrdinal",ordinal,"eventBlueId",blueId));
    }
    private static String work(String invocation,ClosureWorkOccurrence work,String source) {
        return identity("blue-contracts-work-occurrence/1.0",object("invocationIdentity",invocation,"workOrdinal",work.ordinal(),
                "workKind",work.kind().name(),"targetManagedScopeIdentity",work.targetManagedScopeIdentity(),"sourceOccurrenceIdentity",source));
    }
    private static Map<String,Object> charge(GasTraceEntry c) {
        Map<String,Object> out=object("sequence",c.sequence(),"namespace",c.namespace().wireValue(),"counter",c.counter(),
                "quantity",c.quantity(),"weight",c.weight(),"subtotal",c.subtotal());
        optional(out,"documentId",c.documentId()==null?null:c.documentId().value());optional(out,"scopePath",c.scopePath());
        optional(out,"activationGeneration",c.activationGeneration());optional(out,"componentGeneration",c.componentGeneration());
        optional(out,"contractKey",c.contractKey());optional(out,"logicalPath",c.logicalPath());optional(out,"workOccurrenceId",c.workOccurrenceId());
        return out;
    }
    private static Map<String,Object> rejected(RejectedCharge c,String work) {
        return object("namespace",c.namespace().wireValue(),"counter",c.counter(),"quantity",c.quantity(),"weight",c.weight(),
                "subtotal",c.subtotal(),"applicableCap",object("kind","SHARED"),"remainingBeforeCharge",c.remainingBeforeCharge(),
                "owner",object("kind","WORK","workOccurrenceIdentity",work));
    }
    private static void optional(Map<String,Object> map,String key,Object value) { if(value!=null)map.put(key,value); }
    private static Map<String,Object> object(Object... entries) {
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<entries.length;i+=2)out.put((String)entries[i],entries[i+1]);
        return out;
    }
    private static String identity(String domain,Object value) {
        try {
            byte[] bytes=new JsonCanonicalizer(new ObjectMapper().writeValueAsString(object("domain",domain,"value",value))).getEncodedUTF8();
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder hex=new StringBuilder("sha256:");
            for(byte b:digest){hex.append(Character.forDigit((b&255)>>>4,16));hex.append(Character.forDigit(b&15,16));}
            return hex.toString();
        } catch(Exception failure) { throw new AssertionError("Independent canonical identity constructor failed",failure); }
    }
}
