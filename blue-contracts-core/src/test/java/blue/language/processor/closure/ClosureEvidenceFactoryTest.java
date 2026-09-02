package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class ClosureEvidenceFactoryTest {

    private static final String SHA_A =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String SHA_B =
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String REGISTRY =
            "sha256:46a7744c1cbfa4b00e1d8a99f6ca3f0089ef697de968fee08547894ab02b0ca1";
    private static final String BLUE_A =
            "8XQVkfrtGJ5kK3UBM7yR33SBME13vkumTvXo7kRJe3p8";
    private static final String BLUE_B =
            "6ZEqCbcDrgozabAdvxqbUVsG8z8NGxFv86ot2Go55xRZ";

    @Test
    void shouldDeriveACompleteHostInvocationWithoutCallerHashing() {
        Node document = new Node().name("a");
        String documentBlueId = DirectBlueIdCalculator.calculateBlueId(
                document);
        ManagedDocumentSnapshot managedDocument =
                new ManagedDocumentSnapshot(
                        new DocumentId("a"),
                        documentBlueId,
                        document,
                        false,
                        false,
                        true,
                        7L,
                        1L);
        ComponentSnapshot component =
                ClosureEvidenceFactory.acyclicComponent(managedDocument);
        AffectedClosureSnapshot snapshot =
                ClosureEvidenceFactory.affectedClosure(
                        3L,
                        Collections.singletonList(managedDocument),
                        Collections.<ManagedOccurrenceBinding>emptyList(),
                        Collections.singletonList(component),
                        Collections.singletonList(new DocumentId("a")));

        Node event = new Node().value("event");
        String eventBlueId = DirectBlueIdCalculator.calculateBlueId(event);
        ExternalOrderKey sourceOrder = ExternalOrderKey.of(Arrays.asList(
                BigInteger.ONE, "timeline", BigInteger.TEN));

        Map<String, Long> portableLimits = new LinkedHashMap<String, Long>();
        portableLimits.putAll(GasSchedule.contracts10().portableLimits());
        try (DocumentProcessor processor = DocumentProcessor.builder()
                .runtimeRegistryIdentity(REGISTRY)
                .build()) {
            ClosureEnvironment environment =
                    ClosureEvidenceFactory.environment(
                            processor,
                            SHA_A,
                            SHA_B,
                            "nfc-document-lineage-v1",
                            "exact-document-lineage",
                            "fixture-exact-node-provider-v1",
                            "canonical-source-order-v1",
                            "test-portable-limits",
                            portableLimits);
            ExternalEventCause cause = ClosureEvidenceFactory.externalCause(
                    event,
                    eventBlueId,
                    sourceOrder,
                    environment.externalOrderPolicyIdentity());
            ExecutionPolicy policy = ClosureEvidenceFactory.executionPolicy(
                    100_000L,
                    Collections.<DocumentId, Long>emptyMap(),
                    "test-policy");
            DirectLogicalDelivery delivery = new DirectLogicalDelivery(
                    ManagedScopeKey.root(new DocumentId("a")),
                    "source",
                    "event",
                    0L);
            ClosureInvocationInput invocation =
                    ClosureEvidenceFactory.processClosure(
                            snapshot,
                            cause,
                            Collections.singletonList(delivery),
                            policy,
                            environment);

            ClosureInvocationVerifier.Verification verified =
                    ClosureInvocationVerifier.verify(
                            invocation, null);
            assertEquals(invocation.invocationIdentity(),
                    verified.invocationIdentity());
            assertEquals(snapshot.closureIdentity(),
                    ClosureIdentityService.INSTANCE
                            .affectedClosureIdentity(snapshot));
            assertEquals(policy.identity(),
                    ClosureIdentityService.INSTANCE
                            .executionPolicyIdentity(policy));
            assertNotEquals(SHA_A, invocation.invocationIdentity());
        }
    }

    @Test
    void shouldDeriveManagedRevisionReceiptAndCauseTogether() {
        Node after = new Node().name("after");
        ManagedRevisionCause cause =
                ClosureEvidenceFactory.managedRevisionCause(
                        SHA_A,
                        new DocumentId("child"),
                        4L,
                        5L,
                        BLUE_A,
                        BLUE_B,
                        after,
                        SHA_B);

        assertEquals(
                ClosureIdentityService.INSTANCE.sourceRevisionReceiptIdentity(
                        new DocumentId("child"),
                        4L,
                        5L,
                        BLUE_A,
                        BLUE_B,
                        SHA_B),
                cause.sourceRevisionReceiptIdentity());
        assertEquals(
                ClosureIdentityService.INSTANCE.managedRevisionCauseIdentity(
                        SHA_A,
                        new DocumentId("child"),
                        4L,
                        5L,
                        BLUE_A,
                        BLUE_B,
                        SHA_B,
                        cause.sourceRevisionReceiptIdentity()),
                cause.causeIdentity());
    }
}
