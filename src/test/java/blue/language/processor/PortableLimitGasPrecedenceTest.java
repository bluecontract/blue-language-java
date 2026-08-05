package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies deterministic precedence between portable bounds and gas. */
final class PortableLimitGasPrecedenceTest {

    private static final String PATCH_LIMIT =
            GasScheduleConstants.PortableLimit
                    .PATCHES_PER_CONTRACT_RESULT;

    @Test
    void shouldReportPortablePatchLimitBeforeCompetingGasExhaustion() {
        // given
        ContextFixture fixture = contextWithGasLimit(0L);
        long limit = fixture.execution.runtime()
                .gasMeter().schedule().portableLimit(PATCH_LIMIT);
        List<JsonPatch> oversized = patches(
                Math.toIntExact(limit + 1L));

        // when
        Throwable failure;
        try {
            failure = captureFailure(
                    () -> fixture.context.applyPatches(oversized));
        } finally {
            fixture.close();
        }

        // then
        PortableLimitExceededException portable = assertInstanceOf(
                PortableLimitExceededException.class,
                failure);
        assertEquals(
                ProcessorErrorCategory.PatchLimitExceeded,
                portable.diagnostic().category());
        assertEquals(PATCH_LIMIT, portable.limitName());
        assertEquals(limit + 1L, portable.observed());
        assertEquals(limit, portable.limit());
        assertEquals(0L, fixture.execution.runtime().totalGas());
        assertTrue(
                fixture.execution.runtime()
                        .conformanceTrace().gas().isEmpty());
    }

    @Test
    void shouldReportGasExhaustionWhenPatchBatchIsWithinPortableLimit() {
        // given
        ContextFixture fixture = contextWithGasLimit(0L);
        fixture.context.applyPatch(
                JsonPatch.replace(
                        "/value",
                        new Node().value(1)));

        // when
        Throwable failure;
        try {
            failure = captureFailure(
                    fixture.context::applyBufferedEffects);
        } finally {
            fixture.close();
        }

        // then
        GasLimitExceededException gas = assertInstanceOf(
                GasLimitExceededException.class,
                failure);
        assertEquals(0L, gas.admittedGas());
        assertEquals(0L, gas.gasLimit());
        assertEquals(0L, fixture.execution.runtime().totalGas());
    }

    private static ContextFixture contextWithGasLimit(long gasLimit) {
        DocumentProcessor owner = DocumentProcessor.builder()
                .gasLimit(gasLimit)
                .build();
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        owner,
                        new Node().properties(
                                "value",
                                new Node().value(0)));
        execution.preflightScope("/");
        ProcessorExecutionContext context = execution.createContext(
                "/",
                execution.bundleForScope("/"),
                new Node(),
                false);
        return new ContextFixture(owner, execution, context);
    }

    private static List<JsonPatch> patches(int count) {
        List<JsonPatch> patches = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            patches.add(JsonPatch.add(
                    "/patch-" + index,
                    new Node().value(index)));
        }
        return patches;
    }

    private static final class ContextFixture implements AutoCloseable {
        private final DocumentProcessor owner;
        private final ProcessorInvocationState execution;
        private final ProcessorExecutionContext context;

        private ContextFixture(
                DocumentProcessor owner,
                ProcessorInvocationState execution,
                ProcessorExecutionContext context) {
            this.owner = owner;
            this.execution = execution;
            this.context = context;
        }

        @Override
        public void close() {
            context.close();
            owner.close();
        }
    }
}
