package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.model.Schema;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Contracts ownership of runtime-produced documents, independent of BEX. */
final class HostedDocumentFactoryOutputTest {
    @Test
    void shouldPreserveInheritedMatchersAndExecuteTheCreatedChild() {
        // given
        for (boolean inline : new boolean[]{false, true}) {
            try (Fixture fixture = new Fixture(new Node().value("sample"), inline)) {
                Node root = fixture.root();
                // when
                DocumentProcessingResult created = fixture.processor.initializeDocument(root);
                assertTrue(created.commits(), diagnostic(created));
                Node child = created.document().getNode("/child");
                DocumentProcessingResult initialized = fixture.processor.initializeDocument(child);
                // then
                assertTrue(initialized.commits(), diagnostic(initialized));
                assertEquals("sample", child.getAsText("/label"));
                assertEquals("ready", initialized.document().getAsText("/state"));
                assertNotNull(initialized.document().getNode("/contracts/initialized"));
                assertNull(root.getProperties());
                assertEquals(DirectBlueIdCalculator.calculateBlueId(child), fixture.childIdentity);
                // Complete manually authored input is an additional control.
                DocumentProcessingResult manual = fixture.processor.initializeDocument(fixture.child());
                assertTrue(manual.commits(), diagnostic(manual));
                assertEquals("ready", manual.document().getAsText("/state"));
            }
        }
    }

    @Test
    void shouldStillRejectMissingAndWrongKindBusinessFieldsBeforePublishing() {
        // given
        for (Node argument : new Node[]{null, new Node().value(42L), new Node().properties(Collections.emptyMap())}) {
            try (Fixture fixture = new Fixture(argument, false)) {
                Node input = fixture.root();
                // when
                DocumentProcessingResult result = fixture.processor.initializeDocument(input);
                // then
                assertFalse(result.commits());
                assertEquals(NodeWireForm.get(input), NodeWireForm.get(result.document()));
                assertTrue(result.events().isEmpty());
                assertNull(fixture.childIdentity);
            }
        }
    }

    public static final class FactoryHandler extends HandlerContract { }
    public static final class ChildHandler extends HandlerContract { }

    private static final class Fixture implements AutoCloseable {
        final Node argument;
        final boolean inline;
        final Node definition;
        final String factoryId;
        final BlueLanguage language;
        final BlueContracts contracts;
        final DocumentProcessor processor;
        String childIdentity;

        Fixture(Node argument, boolean inline) {
            this.argument = argument;
            this.inline = inline;
            Node factoryType = new Node().name("Hosted factory").type(ref(RuntimeBlueIds.HANDLER));
            Node childType = new Node().name("Hosted child").type(ref(RuntimeBlueIds.HANDLER));
            factoryId = id(factoryType);
            definition = new Node().name("Reading")
                    .properties("label", new Node().type(ref(BlueLanguageConstants.TEXT_TYPE_BLUE_ID))
                            .schema(new Schema().required(new Node().value(true))))
                    .properties("state", new Node().type(ref(BlueLanguageConstants.TEXT_TYPE_BLUE_ID)))
                    .contracts(lifecycle(id(childType)));
            Map<String, Node> nodes = new LinkedHashMap<>();
            nodes.put(id(definition), definition);
            nodes.put(factoryId, factoryType);
            nodes.put(id(childType), childType);
            language = BlueLanguage.builder().nodeProvider(id -> nodes.containsKey(id)
                    ? Collections.singletonList(nodes.get(id).clone()) : BlueRuntimeTypeRegistry.getDefault().asProvider().fetchByBlueId(id)).build();
            ContractProcessorRegistry registry = ContractProcessorRegistryBuilder.create().registerDefaults()
                    .register(factoryId, factoryType, new HandlerProcessor<FactoryHandler>() {
                        public Class<FactoryHandler> contractType() { return FactoryHandler.class; }
                        public void execute(FactoryHandler handler, ProcessorExecutionContext context) {
                            context.emitEvent(new Node().value("tentative"));
                            ExactBlueValue child = context.semanticOutputBoundary().admit(child());
                            childIdentity = child.blueId();
                            context.applyFrozenPatch(FrozenJsonPatch.add("/child", child));
                        }
                    }).register(id(childType), childType, new HandlerProcessor<ChildHandler>() {
                        public Class<ChildHandler> contractType() { return ChildHandler.class; }
                        public void execute(ChildHandler handler, ProcessorExecutionContext context) {
                            context.applyPatch(JsonPatch.add("/state", new Node().value("ready")));
                        }
                    }).build();
            contracts = BlueContracts.builder(language.processing()).runtimeRegistry(registry).build();
            processor = DocumentProcessor.builder().runtimeAccess(contracts.runtimeAccess())
                    .runtimeRegistry(registry).runtimeRegistryIdentity(registry.generationIdentity()).build();
        }
        Node child() {
            Node result = new Node().type(inline ? definition.clone() : ref(id(definition)));
            if (argument != null) result.properties("label", argument.clone());
            return result;
        }
        Node root() { return new Node().contracts(lifecycle(factoryId)); }
        public void close() { processor.close(); contracts.close(); language.close(); }
    }
    private static Node lifecycle(String handlerId) {
        return new Node().properties("lifecycle", new Node().type(ref(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)))
                .properties("initialize", new Node().type(ref(handlerId))
                        .properties("channel", new Node().value("lifecycle"))
                        .properties("event", new Node().type(ref(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED))));
    }
    private static Node ref(String id) { return new Node().blueId(id); }
    private static String id(Node node) { return DirectBlueIdCalculator.calculateBlueId(node); }
    private static String diagnostic(DocumentProcessingResult result) {
        return result.diagnostic() == null ? result.status().toString() : result.diagnostic().message();
    }
}
