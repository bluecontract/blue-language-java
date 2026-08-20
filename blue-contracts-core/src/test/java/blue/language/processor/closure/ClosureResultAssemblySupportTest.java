package blue.language.processor.closure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ClosureResultAssemblySupportTest {

    @Test
    void projectsPhysicalHostedRuntimeNamespacesToNormativeRuntime() {
        assertEquals(
                GasTraceEntry.Namespace.RUNTIME,
                ClosureResultAssemblySupport.namespace(
                        "coordination.00000000"));
        assertEquals(
                GasTraceEntry.Namespace.RUNTIME,
                ClosureResultAssemblySupport.namespace(
                        "bex.workflow.sha256:fixture.compute.00000000"));
        assertEquals(
                GasTraceEntry.Namespace.RUNTIME,
                ClosureResultAssemblySupport.namespace("runtime"));
    }

    @Test
    void preservesCoreNamespacesAndRejectsMissingPhysicalEvidence() {
        assertEquals(
                GasTraceEntry.Namespace.PROCESSOR,
                ClosureResultAssemblySupport.namespace("processor"));
        assertEquals(
                GasTraceEntry.Namespace.SEMANTIC,
                ClosureResultAssemblySupport.namespace("semantic"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClosureResultAssemblySupport.namespace(""));
        assertThrows(
                IllegalArgumentException.class,
                () -> ClosureResultAssemblySupport.namespace(null));
    }
}
