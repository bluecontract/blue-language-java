package blue.language.conformance.contracts;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.runtime.BlueLanguageRuntime;
import blue.language.conformance.ConformanceEngine;
import blue.language.conformance.api.BlueContractsConformanceReport;
import blue.language.provider.NodeProvider;
import blue.language.registry.BootstrapProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifiedNodeProvider;
import blue.language.conformance.ConformancePlan;
import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.ConformanceChangedPath;
import blue.language.processor.ConformancePlannerOverride;
import blue.language.processor.ContractMatchingService;
import blue.language.processor.CheckpointDomain;
import blue.language.processor.DocumentProcessingResult;
import blue.language.processor.DocumentProcessor;
import blue.language.processor.EffectiveContractSnapshot;
import blue.language.processor.EffectiveContractSnapshotConstants;
import blue.language.processor.ExternalDeliveryPlan;
import blue.language.processor.ExternalDeliverySnapshot;
import blue.language.processor.ExternalChannelDependencySnapshot;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.GasSchedule;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.GasTraceEntry;
import blue.language.processor.ProcessAttemptResult;
import blue.language.processor.ProcessingConformanceTrace;
import blue.language.processor.ProcessingDebugResult;
import blue.language.processor.ProcessingSnapshotManager;
import blue.language.processor.ProcessingTraceConstants;
import blue.language.processor.ProcessingTraceRecord;
import blue.language.processor.PlatformCommitCompanion;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorStatus;
import blue.language.processor.SubscriptionDelta;
import blue.language.processor.VerifiedExecutionEvidence;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.model.NodeWireForm;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


/**
 * Closed executable harness for one Blue Contracts 1.0 fixture envelope.
 *
 * <p>Actual values are built only from production API return values, canonical
 * run records, and declared feeder/runtime algorithms. The {@code expected}
 * subtree is read solely by {@link ContractsAssertionEvaluator} after
 * execution.</p>
 */
final class ContractsFixtureHarness extends ContractsFixtureExecutionEngine {

    /**
     * Creates a harness bound to the packaged schema, projection catalog, gas
     * manifest, and conformance registry resources.
     *
     * @throws IllegalStateException when a required packaged resource is
     *         missing, malformed, or identity-inconsistent
     */
    public ContractsFixtureHarness() {
    }


    /**
     * Validates, executes, projects, and asserts one Contracts 1.0 fixture.
     *
     * <p>Execution uses a fresh, conformance-owned Language runtime so host
     * configuration cannot change a fixture result. Successful return means
     * every fixture assertion passed. The returned projection is
     * execution-local and remains mutable to the caller.</p>
     *
     * @param fixture complete fixture JSON
     * @param completeCounterCoverage whether the enclosing suite proved
     *         one-to-one gas counter microfixture coverage
     * @return actual presence-aware projection, including executed variants
     * @throws IllegalArgumentException when validation or an executable
     *         control fails deterministically
     * @throws AssertionError when an expected observable does not match
     */
    ContractsConformanceProjection execute(
            JsonNode fixture,
            boolean completeCounterCoverage) {
        validator.validate(fixture);
        projectionCatalog.validateFixtureAssertions(fixture);

        String operation = fixture.path(
                ContractsFixtureConstants.Field.OPERATION).asText();
        JsonNode input = fixture.path(
                ContractsFixtureConstants.Field.INPUT);
        if (ContractsFixtureConstants.Operation.GAS_MICRO.equals(
                operation)
                && !input.has(ContractsFixtureConstants.Field.ROOT)) {
            ContractsConformanceProjection projection =
                    executeStandaloneGas(fixture, completeCounterCoverage);
            assertions.evaluate(fixture, projection);
            return projection;
        }

        validateExecutableControls(fixture);
        boolean requiresExecutionEvidence =
                !ContractsFixtureConstants.Operation.PLATFORM.equals(
                        operation);
        PreparedInput base = prepare(
                input,
                null,
                null,
                requiresExecutionEvidence,
                hasVector(fixture, "C-LOOP-01"));
        ContractsConformanceProjection projection;
        if (ContractsFixtureConstants.Operation.PLATFORM.equals(
                operation)) {
            projection = executePlatform(fixture, base);
        } else if (ContractsFixtureConstants.Operation.PROCESS_ATTEMPT
                .equals(operation)) {
            projection = executeAttempt(fixture, base);
        } else if (ContractsFixtureConstants.Operation.PROCESS.equals(
                operation)) {
            projection = executeProcess(fixture, base);
        } else if (ContractsFixtureConstants.Operation.GAS_MICRO.equals(
                operation)) {
            ProcessExecution execution = runProcess(base);
            projection = projectProcess(base, execution);
            addCompositeGasAudit(
                    projection, execution.trace, completeCounterCoverage);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported Contracts 1.0 fixture operation: " + operation);
        }

        executeVariants(fixture, base, projection);
        assertions.evaluate(fixture, projection);
        return projection;
    }

    /**
     * Validates fixture structure and declared projection paths without
     * executing runtime controls or assertions.
     *
     * @param fixture candidate fixture JSON
     * @throws IllegalArgumentException when the fixture or a projection path
     *         violates the closed Contracts 1.0 format
     */
    public void validate(JsonNode fixture) {
        validator.validate(fixture);
        projectionCatalog.validateFixtureAssertions(fixture);
    }
}
