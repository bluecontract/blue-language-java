package blue.language.merge;

import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class CompletedValueValidatorPresenceTest {

    @Test
    void omittedOptionalTypedBranchDoesNotActivateRequiredDescendant() {
        PresenceFixture fixture = fixture();

        Node resolved = merger(fixture.provider).resolve(new Node()
                        .type(reference(fixture.handlerId))
                        .properties("channel", new Node().value("in"))
                        .properties("operationId", new Node()
                                .value("operation-1")),
                ResolutionLimits.NO_LIMITS);

        assertNotNull(resolved);
    }

    @Test
    void omittedOptionalNestedTypedBranchDoesNotActivateRequiredDescendant() {
        PresenceFixture fixture = fixture();

        Node resolved = merger(fixture.provider).resolve(new Node()
                        .type(reference(fixture.handlerId))
                        .properties("channel", new Node().value("in"))
                        .properties("operationId", new Node()
                                .value("operation-1"))
                        .properties("result", new Node()
                                .properties("patches", new Node()
                                        .items(Collections.emptyList()))),
                ResolutionLimits.NO_LIMITS);

        assertNotNull(resolved);
    }

    @Test
    void resolvedContractOverlayPreservesOmittedOptionalTypedBranch() {
        PresenceFixture fixture = fixture();
        Node rootType = new Node().name("Root With Required Contract")
                .contracts(new Node().properties(
                        "requiredWorkflow",
                        new Node().schema(new Schema().required(true))));
        fixture.provider.addSingleNodes(rootType);
        String rootTypeId = fixture.provider.getBlueIdByName(
                "Root With Required Contract");

        Node resolved = merger(fixture.provider).resolve(new Node()
                        .type(reference(rootTypeId))
                        .contracts(new Node().properties(
                                "requiredWorkflow",
                                new Node()
                                        .type(reference(fixture.handlerId))
                                        .properties("channel", new Node()
                                                .value("in"))
                                        .properties("operationId", new Node()
                                                .value("operation-1")))),
                ResolutionLimits.NO_LIMITS);

        assertNotNull(resolved);
    }

    @Test
    void fixedPureReferenceInheritedFromTypeRemainsSemanticallyPresent() {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node payload = new Node().name("Fixed Referenced Value")
                .properties("answer", new Node().value("fixed"));
        provider.addSingleNodes(payload);
        String payloadId = provider.getBlueIdByName("Fixed Referenced Value");
        Node base = new Node().name("Required Reference Base")
                .properties("field", new Node()
                        .schema(new Schema().required(true)));
        provider.addSingleNodes(base);
        String baseId = provider.getBlueIdByName("Required Reference Base");
        Node derived = new Node().name("Fixed Reference Derived")
                .type(reference(baseId))
                .properties("field", reference(payloadId));
        provider.addSingleNodes(derived);
        String derivedId = provider.getBlueIdByName(
                "Fixed Reference Derived");

        Node resolved = merger(provider).resolve(new Node()
                        .type(reference(derivedId)),
                ResolutionLimits.NO_LIMITS);

        Node field = resolved.getProperties().get("field");
        assertEquals(payloadId, field.getBlueId());
    }

    private static PresenceFixture fixture() {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node ledger = new Node().name("Optional Runtime Ledger")
                .properties("runtimeType", new Node()
                        .type("Text")
                        .schema(new Schema().required(true)))
                .properties("counters", new Node()
                        .type("List")
                        .schema(new Schema().required(true)));
        provider.addSingleNodes(ledger);
        String ledgerId = provider.getBlueIdByName("Optional Runtime Ledger");
        Node executionResult = new Node()
                .name("Execution Result With Optional Ledger")
                .properties("patches", new Node().type("List"))
                .properties("events", new Node().type("List"))
                .properties("runtimeLedger", new Node()
                        .type(reference(ledgerId)))
                .properties("termination", new Node()
                        .description("Optional one-time termination request."));
        provider.addSingleNodes(executionResult);
        String executionResultId = provider.getBlueIdByName(
                "Execution Result With Optional Ledger");
        Node handler = new Node().name("Handler With Optional Result")
                .properties("channel", new Node().type("Text"))
                .properties("operationId", new Node().type("Text"))
                .properties("result", new Node()
                        .type(reference(executionResultId)));
        provider.addSingleNodes(handler);
        String handlerId = provider.getBlueIdByName(
                "Handler With Optional Result");
        return new PresenceFixture(provider, handlerId);
    }

    private static Merger merger(BasicNodeProvider provider) {
        return new Merger(new SequentialMergingProcessor(Arrays.asList(
                new ValuePropagator(),
                new TypeAssigner(),
                new ListProcessor(),
                new DictionaryProcessor(),
                new SchemaPropagator(),
                new SchemaVerifier(),
                new BasicTypesVerifier())), provider);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class PresenceFixture {
        private final BasicNodeProvider provider;
        private final String handlerId;

        private PresenceFixture(BasicNodeProvider provider, String handlerId) {
            this.provider = provider;
            this.handlerId = handlerId;
        }
    }
}
