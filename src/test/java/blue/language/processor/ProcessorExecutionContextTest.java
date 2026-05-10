package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.contracts.TestEventChannelProcessor;
import blue.language.processor.model.SetProperty;
import blue.language.processor.model.TestEvent;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused tests for the slim handler context surface.
 */
final class ProcessorExecutionContextTest {

    @Test
    void documentHelpersExposeSnapshots() {
        Node document = new Node()
                .properties("value", new Node().value(1))
                .properties("nested", new Node().properties("inner", new Node().value("x")));

        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, document.clone());
        execution.loadBundles("/");

        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false, false);

        assertNull(context.contractKey());
        assertNull(context.contractNode());
        assertNull(context.frozenContractNode());

        Node snapshot = context.documentAt("/nested/inner");
        assertNotNull(snapshot);
        assertEquals("x", snapshot.getValue());

        Node missing = context.documentAt("/unknown");
        assertNull(missing);

        assertTrue(context.documentContains("/value"));
        assertFalse(context.documentContains("/value/missing"));

        // Ensure the returned node is a clone (mutation should not leak back).
        snapshot.value("mutated");
        Node reread = context.documentAt("/nested/inner");
        assertEquals("x", reread.getValue());
    }

    @Test
    void emitEventQueuesAndChargesGas() {
        DocumentProcessor owner = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(owner, new Node());
        execution.loadBundles("/");
        ProcessorExecutionContext context = execution.createContext("/", execution.bundleForScope("/"), new Node(), false, false);

        context.emitEvent(new Node().value("payload"));

        ScopeRuntimeContext scopeRuntime = execution.runtime().scope("/");
        assertEquals(1, scopeRuntime.triggeredQueue().size());
        assertTrue(execution.runtime().totalGas() >= 20L);
    }

    @Test
    void executingHandlerContextExposesContractKeyAndOriginalContractNode() {
        MetadataProbeProcessor processor = new MetadataProbeProcessor();
        Blue blue = new Blue();
        blue.registerContractProcessor(new TestEventChannelProcessor());
        blue.registerContractProcessor(processor);

        Node document = blue.yamlToNode("name: Context Metadata\n" +
                "contracts:\n" +
                "  events:\n" +
                "    type:\n" +
                "      blueId: TestEventChannel\n" +
                "  probe:\n" +
                "    name: Probe Handler\n" +
                "    description: Captures execution context metadata\n" +
                "    type:\n" +
                "      blueId: SetProperty\n" +
                "    channel: events\n" +
                "    propertyKey: /x\n" +
                "    propertyValue: 1\n");
        Node initialized = blue.initializeDocument(document).document();

        blue.processDocument(initialized, blue.objectToNode(new TestEvent().eventId("evt-1")));

        assertEquals("probe", processor.contractKey.get());
        Node contractNode = processor.contractNode.get();
        assertNotNull(contractNode);
        assertEquals("Probe Handler", contractNode.getName());
        assertEquals("Captures execution context metadata", contractNode.getDescription());
        assertEquals("/x", contractNode.get("/propertyKey"));
        assertNotNull(processor.frozenContractNode.get());
        assertEquals("Probe Handler", processor.frozenContractNode.get().toNode().getName());
        assertEquals("Probe Handler", processor.secondContractNode.get().getName(),
                "contractNode() must return a defensive materialization");
    }

    private static final class MetadataProbeProcessor implements HandlerProcessor<SetProperty> {
        private final AtomicReference<String> contractKey = new AtomicReference<>();
        private final AtomicReference<Node> contractNode = new AtomicReference<>();
        private final AtomicReference<Node> secondContractNode = new AtomicReference<>();
        private final AtomicReference<blue.language.snapshot.FrozenNode> frozenContractNode = new AtomicReference<>();

        @Override
        public Class<SetProperty> contractType() {
            return SetProperty.class;
        }

        @Override
        public void execute(SetProperty contract, ProcessorExecutionContext context) {
            contractKey.set(context.contractKey());
            Node first = context.contractNode();
            contractNode.set(first != null ? first.clone() : null);
            if (first != null) {
                first.name("Mutated");
            }
            secondContractNode.set(context.contractNode());
            frozenContractNode.set(context.frozenContractNode());
        }
    }
}
