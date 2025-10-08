package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.ContractBundle;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorBoundaryTest {

    @Test
    void allowsPatchingWithinScopeUsingLiteralSegments() {
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatch("/foo", bundle, JsonPatch.add("/foo//bar", new Node().value("ok")), false);

        Node foo = getProperty(document, "foo");
        Node empty = getProperty(foo, "");
        Node bar = getProperty(empty, "bar");
        assertEquals("ok", bar.getValue());
    }

    @Test
    void deniesPatchingOutsideScope() {
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatch("/foo", bundle, JsonPatch.add("/bar", new Node().value("oops")), false);

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/foo/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
        Node foo = resultDoc.getAsNode("/foo");
        Map<String, Node> fooProps = foo.getProperties();
        assertNotNull(fooProps);
        assertFalse(fooProps.containsKey("bar"));
    }

    @Test
    void parentCannotModifyEmbeddedChildInterior() {
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        execution.handlePatch("/foo", bundle, JsonPatch.add("/foo/child/value", new Node().value("nope")), false);

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/foo/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
        Node foo = resultDoc.getAsNode("/foo");
        Map<String, Node> fooProps = foo.getProperties();
        assertNotNull(fooProps);
        assertFalse(fooProps.containsKey("child"));
    }

    @Test
    void parentMayReplaceEntireEmbeddedChild() {
        Node child = new Node().properties("value", new Node().value("old"));
        Node parent = new Node().properties("child", child);
        Node document = new Node().properties("foo", parent);

        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        execution.handlePatch("/foo", bundle, JsonPatch.replace("/foo/child", new Node().properties("next", new Node().value("fresh"))), false);

        Node foo = getProperty(document, "foo");
        Node replacedChild = getProperty(foo, "child");
        Node next = getProperty(replacedChild, "next");
        assertEquals("fresh", next.getValue());
    }

    @Test
    void parentMayRemoveEntireEmbeddedChild() {
        Node child = new Node().properties("value", new Node().value("old"));
        Node parent = new Node().properties("child", child);
        Node document = new Node().properties("foo", parent);

        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        execution.handlePatch("/foo", bundle, JsonPatch.remove("/foo/child"), false);

        Node foo = getProperty(document, "foo");
        Map<String, Node> props = foo.getProperties();
        assertNotNull(props);
        assertFalse(props.containsKey("child"));
    }

    @Test
    void scopeCannotMutateItsOwnRoot() {
        Node document = new Node().properties("foo", new Node().properties("value", new Node().value("existing")));
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatch("/foo", bundle, JsonPatch.replace("/foo", new Node().value("new")), false);

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/foo/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
        Node foo = resultDoc.getAsNode("/foo");
        Node value = foo.getProperties().get("value");
        assertEquals("existing", value.getValue());
    }

    @Test
    void rootPatchTargetIsFatal() {
        Node document = new Node().properties("foo", new Node().value("ok"));
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        expectRunTermination(() -> execution.handlePatch("/", bundle, JsonPatch.remove("/"), false));

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/"));
        Node foo = resultDoc.getProperties().get("foo");
        assertEquals("ok", foo.getValue());
    }

    @Test
    void reservedRootContractsAreWriteProtected() {
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        expectRunTermination(() -> execution.handlePatch("/", bundle,
                JsonPatch.add("/contracts/checkpoint", new Node().value("forbidden")), false));

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/"));
    }

    @Test
    void reservedContractsWithinScopeAreWriteProtected() {
        Node document = new Node().properties("foo", new Node());
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatch("/foo", bundle,
                JsonPatch.add("/foo/contracts/initialized", new Node().value("bad")), false);

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/foo/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
        Node fooNode = resultDoc.getProperties().get("foo");
        assertNotNull(fooNode);
        assertTrue(fooNode.getProperties().containsKey("contracts"));
    }

    private Node getProperty(Node node, String key) {
        Map<String, Node> properties = node.getProperties();
        assertNotNull(properties, "Expected properties to exist for key '" + key + "'");
        Node child = properties.get(key);
        assertNotNull(child, "Missing property '" + key + "'");
        return child;
    }

    private void expectRunTermination(Runnable action) {
        try {
            action.run();
            fail("Expected run termination");
        } catch (RuntimeException ex) {
            assertEquals("blue.language.processor.RunTerminationException",
                    ex.getClass().getName());
        }
    }
}
