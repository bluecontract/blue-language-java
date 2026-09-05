package blue.language.runtime;

import blue.language.api.*;
import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class DefinitionLocalityTest {
    @Test
    void shouldRejectInvalidFixedScalarInsideWarmOuterMetadata() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node invalid = language.preprocessing().preprocess(source(language,
                    "type: Text\nvalue: bad\nschema: {enum: [good]}\n"));
            provider.addSingleNodes(invalid);
            Node outer = language.preprocessing().preprocess(source(language,
                    "type: List\nitemType: {blueId: " + language.identity().directBlueId(invalid) + "}\n"));
            provider.addSingleNodes(outer);
            // when
            Node instance = new Node().type(new Node().blueId(language.identity().directBlueId(outer)));
            // then
            for (int access = 0; access < 3; access++) {
                assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(instance));
                assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(instance));
            }
        }
    }

    @Test
    void shouldPreserveIncompleteForBudgetExhaustionInsideFixedReference() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node payload = language.preprocessing().preprocess(new Node().value("valid"));
            provider.addSingleNodes(payload);
            Node instance = source(language, "type:\n  field: {type: Text}\nfield: {blueId: "
                    + language.identity().directBlueId(payload) + "}\n");
            // when
            BlueOperationResult<Node> limited = language.resolution().resolveLimited(instance,
                    BlueOperationLimits.UNLIMITED.withMaxReferenceExpansions(0));
            // then
            assertEquals(BlueOperationOutcome.INCOMPLETE, limited.outcome());
            assertFalse(limited.outstandingBlueIds().isEmpty());
            assertEquals(BlueOperationOutcome.ESTABLISHED,
                    language.resolution().resolveLimited(instance, BlueOperationLimits.UNLIMITED).outcome());
        }
    }

    @Test
    void shouldPreserveTypedInvalidEvidenceRegardlessOfDiagnosticWording() {
        // given
        NodeProvider provider = new NodeProvider() {
            public java.util.List<Node> fetchByBlueId(String id) { return null; }
            public NodeProviderResult fetchResultByBlueId(String id) {
                return NodeProviderResult.invalidEvidence("opaque rejection");
            }
        };
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String id = language.identity().directBlueId(new Node().name("unavailable type"));
            // when
            BlueOperationResult<Node> result = language.resolution().resolveLimited(
                    new Node().type(new Node().blueId(id)), BlueOperationLimits.UNLIMITED);
            // then
            assertEquals(BlueOperationOutcome.INVALID, result.outcome());
            assertEquals(NodeProviderOutcome.INVALID_EVIDENCE, result.providerOutcome().orElse(null));
        }
    }

    @Test
    void shouldBoundColdLongAncestryAndValidateAgainWhenWarm() {
        // given
        BasicNodeProvider content = new BasicNodeProvider();
        AtomicInteger reads = new AtomicInteger();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
            reads.incrementAndGet();
            return content.fetchByBlueId(id);
        }).build()) {
            Node parent = language.preprocessing().preprocess(source(language, "type: Text\nschema: {minLength: 1}\n"));
            String id = null;
            for (int depth = 0; depth < 48; depth++) {
                content.addSingleNodes(parent);
                id = language.identity().directBlueId(parent);
                parent = new Node().name("Layer " + depth).type(new Node().blueId(id));
            }
            Node instance = new Node().type(new Node().blueId(id)).value("x");
            // when
            BlueOperationResult<Node> limited = language.resolution().resolveLimited(instance,
                    BlueOperationLimits.UNLIMITED.withMaxReferenceExpansions(8));
            int limitedReads = reads.get();
            // then
            assertEquals(BlueOperationOutcome.INCOMPLETE, limited.outcome());
            assertTrue(limitedReads <= 8, "reads=" + limitedReads);
            assertDoesNotThrow(() -> language.resolution().resolve(instance));
            assertDoesNotThrow(() -> language.resolution().resolve(instance));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(instance.clone().value("")));
            assertEquals(BlueOperationOutcome.INCOMPLETE, language.resolution().resolveLimited(instance,
                    BlueOperationLimits.UNLIMITED.withMaxReferenceExpansions(0)).outcome());
        }
    }

    @Test
    void shouldBoundProviderReadsAcrossLongEnumAncestryProbes() {
        // given
        BasicNodeProvider content = new BasicNodeProvider();
        AtomicInteger reads = new AtomicInteger();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
            reads.incrementAndGet();
            return content.fetchByBlueId(id);
        }).build()) {
            Node domain = language.preprocessing().preprocess(source(language, "name: Finite domain\ntype: Text\n"));
            content.addSingleNodes(domain);
            String id = language.identity().directBlueId(domain);
            java.util.List<Node> bare = new java.util.ArrayList<>();
            java.util.List<Node> typed = new java.util.ArrayList<>();
            for (int entry = 0; entry < 96; entry++) {
                bare.add(new Node().value("v" + entry));
                typed.add(new Node().type(new Node().blueId(id)).value("v" + entry));
            }
            Node parent = new Node().type(new Node().blueId(id))
                    .schema(new blue.language.model.Schema().enumValues(bare));
            Node value = new Node().type(parent).value("v0")
                    .schema(new blue.language.model.Schema().enumValues(typed));
            // when
            BlueOperationResult<Node> result = language.resolution().resolveLimited(value,
                    BlueOperationLimits.UNLIMITED.withMaxReferenceExpansions(1));
            // then
            assertEquals(BlueOperationOutcome.ESTABLISHED, result.outcome());
            assertEquals(96, result.requireEstablished().getSchema().getEnum().size());
            assertTrue(reads.get() <= 1, "provider reads=" + reads.get());
        }
    }

    private static Node source(BlueLanguage language, String yaml) {
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }
}
