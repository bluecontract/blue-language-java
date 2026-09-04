package blue.language.conformance;

import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.NodeProviderOutcome;
import blue.language.codec.BlueFormat;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class InheritedScalarConformanceTest {

    @Test
    void shouldPreserveFooDefinitionAndIntegerInstanceSemantics() {
        // Given an exact, stored Foo definition.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = language(provider)) {
            Node definition = language.codec().parseSource(
                    "name: Foo\nbar:\n  type: Integer\n", BlueFormat.YAML);
            Node stored = language.identity().canonicalIdentityInput(definition);
            String typeId = language.identity().directBlueId(stored);
            provider.addSingleNodes(stored);
            assertNotNull(language.resolution().resolve(definition));

            // When specializing the equivalent inline and referenced types.
            Node inline = instance(stored, new Node().value(42));
            Node referenced = instance(reference(typeId), new Node().value(42));
            // Then resolution and source identity agree across representations.
            assertEquals(BigInteger.valueOf(42), language.resolution()
                    .resolve(inline).getProperties().get("bar").getValue());
            assertEquals(BigInteger.valueOf(42), language.resolution()
                    .resolve(referenced).getProperties().get("bar").getValue());
            assertEquals(language.identity().sourceDocumentBlueId(inline),
                    language.identity().sourceDocumentBlueId(referenced));
            provider.addSingleNodes(language.preprocessing().preprocess(referenced));
            Node expanded = language.graph().expand(referenced);
            assertEquals(language.identity().sourceDocumentBlueId(referenced),
                    language.identity().sourceDocumentBlueId(expanded));
            assertEquals(language.identity().sourceDocumentBlueId(referenced),
                    language.identity().sourceDocumentBlueId(
                            language.graph().collapse(expanded)));
        }
    }

    @Test
    void shouldRejectNonnumericInheritedIntegerThroughPublicResolution() {
        // Given the same definition in inline and reference representations.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = language(provider)) {
            Node stored = language.identity().canonicalIdentityInput(
                    language.codec().parseSource(
                            "name: Foo\nbar:\n  type: Integer\n", BlueFormat.YAML));
            String typeId = language.identity().directBlueId(stored);
            provider.addSingleNodes(stored);
            // When a nonnumeric scalar is supplied, then resolution rejects it.
            for (Node type : Arrays.asList(stored, reference(typeId))) {
                assertThrows(IllegalArgumentException.class,
                        () -> language.resolution().resolve(instance(type,
                                new Node().value("not-an-integer"))));
            }
            Node authored = language.codec().parseSource(
                    "type:\n  blueId: " + typeId
                            + "\nbar: not-an-integer\n", BlueFormat.YAML);
            assertThrows(IllegalArgumentException.class,
                    () -> language.resolution().resolve(authored));
        }
    }

    @Test
    void shouldNotCertifyNonnumericInheritedIntegerAsConformant() {
        // Given the public conformance engine and an exact stored definition.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = language(provider)) {
            Node stored = language.identity().canonicalIdentityInput(
                    language.codec().parseSource(
                            "name: Foo\nbar:\n  type: Integer\n", BlueFormat.YAML));
            String typeId = language.identity().directBlueId(stored);
            provider.addSingleNodes(stored);
            try (ConformanceEngine engine = engine(provider)) {
                // When checking supplied payloads, then only Integers conform.
                assertTrue(engine.conforms(stored));
                for (Node type : Arrays.asList(stored, reference(typeId))) {
                    assertTrue(engine.conforms(instance(type,
                            new Node().value(42))));
                    assertFalse(engine.conforms(instance(type,
                            new Node().value("not-an-integer"))));
                }
            }
        }
    }

    @Test
    void shouldWaitForTypeEvidenceAndValidateWhenItBecomesAvailable() {
        // Given an exact Foo type whose provider is temporarily unavailable.
        BasicNodeProvider backing = new BasicNodeProvider(new Node()
                .name("Foo").properties("bar", new Node().type("Integer")));
        String typeId = backing.getBlueIdByName("Foo");
        PendingProvider provider = new PendingProvider(backing, typeId);
        Node valid = instance(reference(typeId), new Node().value(42));
        Node invalid = instance(reference(typeId),
                new Node().value("not-an-integer"));
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider).build()) {
            // When required type evidence is unavailable, then never certify it.
            BlueOperationResult<Node> pending = language.resolution()
                    .resolveLimited(valid, BlueOperationLimits.UNLIMITED);
            assertEquals(BlueOperationOutcome.INCOMPLETE, pending.outcome());
            assertTrue(pending.outstandingBlueIds().contains(typeId));
            assertEquals(NodeProviderOutcome.UNAVAILABLE,
                    pending.providerOutcome().orElseThrow(AssertionError::new));
            // When it arrives, then retained obligations decide the result.
            provider.available = true;
            for (int repetition = 0; repetition < 2; repetition++) {
                assertEquals(BlueOperationOutcome.ESTABLISHED,
                        language.resolution().resolveLimited(valid,
                                BlueOperationLimits.UNLIMITED).outcome());
                assertEquals(BlueOperationOutcome.INVALID,
                        language.resolution().resolveLimited(invalid,
                                BlueOperationLimits.UNLIMITED).outcome());
            }
        }
    }

    @Test
    void shouldLeaveUnrelatedReferencesColdForDemandedScalarValidation() {
        // Given a type and an unrelated unavailable reference in the candidate.
        BasicNodeProvider backing = new BasicNodeProvider(
                new Node().name("Foo")
                        .properties("bar", new Node().type("Integer")),
                new Node().name("Unrelated").value("remote"));
        String typeId = backing.getBlueIdByName("Foo");
        String unrelatedId = backing.getBlueIdByName("Unrelated");
        PendingProvider provider = new PendingProvider(backing, unrelatedId);
        Node candidate = instance(reference(typeId), new Node().value(42))
                .properties("remote", reference(unrelatedId));
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider).build()) {
            // When only bar is demanded, then its result needs no remote payload.
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    language.resolution().resolveLimited(candidate,
                            BlueOperationLimits.demandedPath("/bar")).outcome());
            assertFalse(provider.requests.contains(unrelatedId));
        }
    }

    private static BlueLanguage language(BasicNodeProvider provider) {
        return BlueLanguage.builder().nodeProvider(provider).build();
    }

    private static Node instance(Node type, Node value) {
        return new Node().type(type.clone()).properties("bar", value);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static ConformanceEngine engine(BasicNodeProvider provider) {
        return new ConformanceEngine(provider,
                new SequentialMergingProcessor(Arrays.asList(
                        new ValuePropagator(), new TypeAssigner(),
                        new ListProcessor(), new DictionaryProcessor(),
                        new SchemaPropagator(), new SchemaVerifier(),
                        new BasicTypesVerifier())));
    }

    private static final class PendingProvider implements NodeProvider {
        private final BasicNodeProvider backing;
        private final String pendingId;
        private final Set<String> requests = new HashSet<>();
        private boolean available;

        private PendingProvider(BasicNodeProvider backing, String pendingId) {
            this.backing = backing;
            this.pendingId = pendingId;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return backing.fetchByBlueId(blueId);
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            requests.add(blueId);
            return pendingId.equals(blueId) && !available
                    ? NodeProviderResult.unavailable("temporarily offline")
                    : backing.fetchResultByBlueId(blueId);
        }
    }
}
