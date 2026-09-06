package blue.language.processor.closure;

import blue.language.processor.DocumentProcessor;
import blue.language.processor.GasSchedule;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class SourceExecutionBasisTest {
    @Test void everyEnvironmentFieldAndProducerPolicyAreBoundIndependentlyOfConsumerBudget() {
        try (DocumentProcessor processor = DocumentProcessor.builder().build()) {
            ClosureEnvironment original = environment(processor, "original"), alternate = environment(processor, "alternate");
            ExecutionPolicy source = ClosureEvidenceFactory.executionPolicy(17L, Collections.emptyMap(), "source");
            ExecutionPolicy consumer = ClosureEvidenceFactory.executionPolicy(9000L, Collections.emptyMap(), "consumer");
            DocumentId owner = new DocumentId("source");
            String basis = SourceExecutionBasis.identity(owner, original, source);
            SourceExecutionBasis.requireCompatibleEnvironment(original, original);
            SourceExecutionBasis.requireProducerBasis(basis, owner, original, source);
            assertNotEquals(basis, SourceExecutionBasis.identity(owner, original, consumer));
            assertThrows(IllegalArgumentException.class, () -> SourceExecutionBasis.requireProducerBasis(basis, owner, original, consumer));
            for (int field = 0; field < 11; field++) {
                ClosureEnvironment changed = changed(original, alternate, field);
                assertThrows(IllegalArgumentException.class, () -> SourceExecutionBasis.requireCompatibleEnvironment(original, changed), "field " + field);
                assertNotEquals(basis, SourceExecutionBasis.identity(owner, changed, source), "basis field " + field);
            }
            ExecutionPolicy forged = new ExecutionPolicy(source.identity(), source.sharedLimit() + 1, source.localLimits(), source.label());
            assertThrows(IllegalArgumentException.class, () -> SourceExecutionBasis.requireProducerBasis(basis, owner, original, forged));
        }
    }

    private static ClosureEnvironment environment(DocumentProcessor processor, String prefix) {
        return ClosureEvidenceFactory.environment(processor, hash('a'), hash('b'), prefix + "-identity", prefix + "-binding",
                prefix + "-provider", prefix + "-order", prefix + "-limits", GasSchedule.contracts10().portableLimits());
    }
    private static ClosureEnvironment changed(ClosureEnvironment e, ClosureEnvironment other, int field) {
        return new ClosureEnvironment(field == 0 ? hash('f') : e.blueLanguageSpecificationIdentity(),
                field == 1 ? hash('f') : e.contractsSpecificationIdentity(), field == 2 ? hash('f') : e.runtimeRegistryIdentity(),
                field == 3 ? hash('f') : e.gasManifestIdentity(), field == 4 ? other.managedDocumentIdentityPolicy() : e.managedDocumentIdentityPolicy(),
                field == 5 ? other.managedBindingPolicy() : e.managedBindingPolicy(), field == 6 ? other.exactNodeProviderDomain() : e.exactNodeProviderDomain(),
                field == 7 ? other.externalOrderPolicy() : e.externalOrderPolicy(), field == 8 ? other.portableLimitPolicy() : e.portableLimitPolicy(),
                field == 9 ? hash('f') : e.cyclicFinalizerIdentity(), field == 10 ? hash('f') : e.cyclicProofVerifierIdentity());
    }
    private static String hash(char value) { return "sha256:" + String.join("", Collections.nCopies(64, String.valueOf(value))); }
}
