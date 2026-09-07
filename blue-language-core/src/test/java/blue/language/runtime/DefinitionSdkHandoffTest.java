package blue.language.runtime;

import blue.language.api.*;
import blue.language.codec.BlueFormat;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Executable public SDK inputs shared with integration and transport owners. */
final class DefinitionSdkHandoffTest {
    @Test
    void shouldDefineStoreReferenceAndCompleteGenderAfterEvidenceArrives() throws Exception {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node gender = fixture(language, "gender.yaml");
            Node canonical = language.identity().canonicalIdentityInput(gender);
            String id = language.identity().directBlueId(canonical);
            Node instance = new Node().type(new Node().blueId(id)).value("female");
            // when
            Node prepared = language.resolution().resolveDefinition(gender);
            BlueOperationResult<Node> missing = language.resolution().resolveLimited(instance, BlueOperationLimits.UNLIMITED);
            provider.addSingleNodes(canonical);
            BlueOperationResult<Node> available = language.resolution().resolveLimited(instance, BlueOperationLimits.UNLIMITED);
            // then
            assertNull(prepared.getValue());
            assertEquals(BlueOperationOutcome.INCOMPLETE, missing.outcome());
            assertTrue(missing.outstandingBlueIds().contains(id));
            assertEquals("female", available.requireEstablished().getValue());
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(instance.clone().value("other")));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(instance.clone().value(null)));
            assertEquals(id, language.identity().sourceDocumentBlueId(gender));
        }
    }

    @Test
    void shouldRetainRequestAndEventObligationsUntilActualPayloadIsSupplied() throws Exception {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node pattern = fixture(language, "request-pattern.yaml");
            Node canonical = language.identity().canonicalIdentityInput(pattern);
            provider.addSingleNodes(canonical);
            String id = language.identity().directBlueId(canonical);
            Node invocation = fixture(language, "request-payload.yaml").type(new Node().blueId(id));
            // when
            Node prepared = language.resolution().resolveDefinition(pattern);
            Node completed = language.resolution().resolve(invocation);
            Node invalid = invocation.clone();
            invalid.getContracts().getProperties().get("operation").getProperties().get("request")
                    .getProperties().get("text").value("x");
            // then
            assertNotNull(prepared.getContracts());
            assertNotNull(completed.getContracts());
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(new Node().type(new Node().blueId(id))));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(invalid));
            assertTrue(language.matching().matches(new Node().value("ok"),
                    new Node().schema(new Schema().required(true).minLength(2))));
            assertFalse(language.matching().matches(new Node().value("x"),
                    new Node().schema(new Schema().required(true).minLength(2))));
        }
    }

    @Test
    void shouldRejectInvalidInheritedFooAndKnownTypeSchemaCombinations() throws Exception {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node foo = fixture(language, "invalid-foo.yaml");
            Node wrongKind = language.codec().parseSource("type: Text\nschema: {minimum: 1}\n", BlueFormat.YAML);
            // when
            Node fixed = language.codec().parseSource("type: Text\nvalue: bad\nschema: {enum: [good]}\n", BlueFormat.YAML);
            // then
            for (Node invalid : new Node[]{foo, wrongKind, fixed}) {
                assertThrows(IllegalArgumentException.class, () -> language.resolution().resolveDefinition(invalid));
                assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(invalid));
                assertThrows(IllegalArgumentException.class, () -> language.identity().canonicalIdentityInput(invalid));
            }
        }
    }

    @Test
    void shouldKeepColdReferenceAcceptanceDistinctFromTargetConformance() {
        // given
        BasicNodeProvider content = new BasicNodeProvider();
        AtomicInteger reads = new AtomicInteger();
        try (BlueLanguageRuntime runtime = BlueLanguageRuntime.create(id -> {
            reads.incrementAndGet();
            return content.fetchByBlueId(id);
        }, BlueCachePolicy.boundedDefaults(), Collections.emptyMap());
             ConformanceEngine engine = runtime.newConformanceEngine()) {
            Node invalidTarget = runtime.preprocessing().preprocess(runtime.codec().parseSource(
                    "type: Text\nvalue: bad\nschema: {enum: [good]}\n", BlueFormat.YAML));
            String id = runtime.identity().directBlueId(invalidTarget);
            Node ref = new Node().blueId(id);
            // when
            String identity = runtime.identity().sourceDocumentBlueId(ref);
            boolean acceptedReference = engine.check(ref).isConformant();
            Node resolvedReference = runtime.resolution().resolveLimited(ref,
                    BlueOperationLimits.UNLIMITED.withMaxReferenceExpansions(0)).requireEstablished();
            Node snapshotReference = runtime.snapshots().resolve(ref).resolvedRoot();
            int coldReads = reads.get();
            content.addSingleNodes(invalidTarget);
            Node target = runtime.graph().expand(ref);
            // then
            assertEquals(id, identity);
            assertTrue(acceptedReference);
            assertTrue(resolvedReference.isReferenceOnly());
            assertTrue(snapshotReference.isReferenceOnly());
            assertEquals(0, coldReads);
            assertFalse(engine.check(target).isConformant());
            assertThrows(IllegalArgumentException.class, () -> runtime.resolution().resolve(target));
            assertThrows(IllegalArgumentException.class, () -> runtime.snapshots().load(id));
        }
    }

    private static Node fixture(BlueLanguage language, String name) throws Exception {
        try (InputStream input = DefinitionSdkHandoffTest.class.getResourceAsStream("/campaign/d2/" + name)) {
            if (input == null) throw new IllegalStateException("Missing fixture " + name);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) >= 0) bytes.write(buffer, 0, count);
            return language.codec().parseSource(new String(bytes.toByteArray(), StandardCharsets.UTF_8), BlueFormat.YAML);
        }
    }
}
