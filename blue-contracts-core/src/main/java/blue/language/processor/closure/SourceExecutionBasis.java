package blue.language.processor.closure;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Complete source execution compatibility, independent of its importing consumer's gas budget. */
public final class SourceExecutionBasis {
    private SourceExecutionBasis() { }

    /** Canonical intrinsic FULL_HISTORY source basis. This digest alone is not admission authority. */
    public static String identity(DocumentId source, ClosureEnvironment environment, ExecutionPolicy producerPolicy) {
        verifyConstructorEvidence(environment, producerPolicy);
        List<Object> values = new ArrayList<Object>();
        values.add("blue-canonical-source-basis-poc/1");
        values.add("blue-canonical-source-full-history-poc/1");
        values.add(Objects.requireNonNull(source, "source").value());
        values.addAll(environmentIdentities(environment));
        values.add(producerPolicy.identity());
        return FrozenNodeEvidenceCodec.digest(FrozenNodeEvidenceCodec.bytes(values));
    }

    /** All semantic environment identities must match; consumer ExecutionPolicy is deliberately absent. */
    public static void requireCompatibleEnvironment(ClosureEnvironment expected, ClosureEnvironment actual) {
        ClosureInvocationVerifier.verifyEnvironment(Objects.requireNonNull(actual, "actual"));
        if (!environmentIdentities(expected).equals(environmentIdentities(actual)))
            throw new IllegalArgumentException("Source belongs to another complete execution environment");
    }

    /** Expected basis must originate in trusted source admission/prefix authority, not the offered receipt. */
    public static void requireProducerBasis(String expectedBasis, DocumentId source,
                                           ClosureEnvironment actual, ExecutionPolicy producerPolicy) {
        if (!Objects.requireNonNull(expectedBasis, "expectedBasis").equals(identity(source, actual, producerPolicy)))
            throw new IllegalArgumentException("Source receipt differs from its expected producer execution basis");
    }

    static java.util.Map<DocumentId, String> fixedPolicyBases(java.util.Set<DocumentId> sources,
            ClosureEnvironment environment, ExecutionPolicy policy) {
        java.util.Map<DocumentId, String> bases = new java.util.LinkedHashMap<DocumentId, String>();
        for (DocumentId source : sources) bases.put(source, identity(source, environment, policy));
        return bases;
    }

    static void requireProducerBases(java.util.Set<DocumentId> sources, ClosureEnvironment expectedEnvironment,
            ClosureEnvironment actualEnvironment, ExecutionPolicy producerPolicy, java.util.Map<DocumentId, String> expectedBases) {
        requireCompatibleEnvironment(expectedEnvironment, actualEnvironment);
        Objects.requireNonNull(expectedBases, "expectedBases");
        for (DocumentId source : sources) {
            String expected = expectedBases.get(source);
            if (expected == null) throw new IllegalArgumentException("Missing expected source execution basis: " + source.value());
            requireProducerBasis(expected, source, actualEnvironment, producerPolicy);
        }
    }

    private static void verifyConstructorEvidence(ClosureEnvironment environment, ExecutionPolicy policy) {
        ClosureInvocationVerifier.verifyEnvironment(Objects.requireNonNull(environment, "environment"));
        if (!Objects.requireNonNull(policy, "policy").identity().equals(ClosureIdentityService.INSTANCE.executionPolicyIdentity(policy)))
            throw new IllegalArgumentException("Source execution policy identity does not bind its constructor evidence");
    }

    private static List<String> environmentIdentities(ClosureEnvironment environment) {
        ClosureEnvironment e = Objects.requireNonNull(environment, "environment");
        return Arrays.asList(e.blueLanguageSpecificationIdentity(), e.contractsSpecificationIdentity(), e.runtimeRegistryIdentity(),
                e.gasManifestIdentity(), e.managedDocumentIdentityPolicyIdentity(), e.managedBindingPolicyIdentity(),
                e.exactNodeProviderDomainIdentity(), e.externalOrderPolicyIdentity(), e.portableLimitPolicyIdentity(),
                e.cyclicFinalizerIdentity(), e.cyclicProofVerifierIdentity());
    }
}
