package blue.language.processor;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Characterizes the execution boundary used by managed closure processing. */
final class ClosureDocumentRuntimeIsolationTest {

    @Test
    void shouldIsolateDocumentRuntimeStateWhileSharingInvocationGasContext() {
        ProcessingGasContext invocationGas =
                new ProcessingGasContext(new GasMeter());

        DocumentProcessingRuntime first = runtime(
                new Node().properties(
                        "name", new Node().value("first")),
                invocationGas);
        DocumentProcessingRuntime second = runtime(
                new Node().properties(
                        "name", new Node().value("second")),
                invocationGas);

        assertNotSame(first, second);
        assertNotSame(
                first.documentViewComponent(),
                second.documentViewComponent());
        assertNotSame(
                first.scopeRegistryComponent(),
                second.scopeRegistryComponent());
        assertSame(invocationGas, first.gasContextComponent());
        assertSame(invocationGas, second.gasContextComponent());
        assertSame(first.gasMeter(), second.gasMeter());
    }

    private static DocumentProcessingRuntime runtime(
            Node document,
            ProcessingGasContext invocationGas) {
        return new DocumentProcessingRuntime(
                document,
                null,
                null,
                null,
                null,
                invocationGas,
                Collections.<String, java.util.List<String>>emptyMap(),
                false);
    }
}
