package blue.language.conformance.api;

import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.NodeDeserializer;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.DirectNodeManifest;
import blue.language.provider.ExactNodeGraphFragments;
import blue.language.api.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderEvidenceVerifier;
import blue.language.provider.ProviderMode;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.SourceProviderEnvironment;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.model.wire.JsonPointer;
import blue.language.model.NodePath;
import blue.language.registry.NodeProviderWrapper;
import blue.language.model.NodeWireForm;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.codec.jackson.UncheckedObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/**
 * Fail-closed executable harness for the exact Blue Language 1.0 fixture
 * package. Every behavior fixture is executed; unsupported data is a failure.
 */
public final class BlueConformanceSuiteRunner {
    private BlueConformanceSuiteRunner() {
    }

    /**
     * Executes every bundled Blue Language fixture.
     *
     * @return complete conformance report
     */
    public static BlueConformanceReport run() {
        BlueConformanceReport metadata = unexecutedReport();
        List<BlueConformanceFixtureSupport.FixtureEntry> entries =
                BlueConformanceFixtureExecution.fixtureEntries();
        List<String> passed = new ArrayList<>(entries.size());
        List<BlueConformanceFailure> failures = new ArrayList<>();
        for (BlueConformanceFixtureSupport.FixtureEntry fixture : entries) {
            try {
                BlueConformanceFixtureExecution.runFixture(fixture, entries);
                passed.add(fixture.id);
            } catch (RuntimeException | AssertionError failure) {
                failures.add(BlueConformanceFixtureExecution.failure(
                        fixture, failure));
            }
        }
        return new BlueConformanceReport(
                metadata.getSpecVersion(),
                metadata.getCoreRegistryBlueIds(),
                metadata.getFixturePackageIdentity(),
                metadata.getFixtureIds(),
                passed,
                Collections.<String>emptyList(),
                metadata.getFixtureCategories(),
                failures);
    }

    /**
     * Describes the packaged Language fixture inventory without executing it.
     *
     * @return report containing package metadata and no outcomes
     */
    public static BlueConformanceReport unexecutedReport() {
        return new BlueConformanceReport(
                "1.0",
                new LinkedHashMap<>(
                        BlueCoreTypeRegistry.INSTANCE.blueIdsByName()),
                BlueConformanceReport.loadFixturePackageIdentity(
                        "blue-language-1.0-fixtures:unavailable"),
                BlueConformanceReport.loadFixtureIds(),
                Collections.emptyList(),
                Collections.emptyList(),
                BlueConformanceReport.loadFixtureCategories());
    }

    /**

     * Returns supported fixture operations.

     *

     * @return immutable operation set

     */
    public static Set<String> knownOperations() {
        return BlueConformanceFixtureSupport.OPERATIONS;
    }

    /**
     * Validates fixture metadata for focused tests.
     *
     * @param spec parsed fixture envelope
     * @throws IllegalArgumentException when metadata is invalid
     */
    public static void validateFixtureMetadataForTest(JsonNode spec) {
        BlueConformanceFixtureExecution.validateFixtureMetadata(spec);
    }

    /**
     * Executes one parsed fixture for focused tests.
     *
     * @param spec parsed fixture envelope
     * @throws AssertionError when a fixture assertion fails
     */
    public static void runFixtureForTest(JsonNode spec) {
        BlueConformanceFixtureExecution.validateFixtureMetadata(spec);
        String operation = BlueConformanceFixtureExecution.requireText(
                spec,
                BlueConformanceFixtureSupport.FixtureField.OPERATION);
        if (BlueConformanceFixtureExecution.expectsTopLevelError(
                spec, operation)) {
            try {
                BlueConformanceFixtureExecution.runOperation(
                        spec,
                        operation,
                        BlueConformanceFixtureExecution.fixtureEntries());
            } catch (RuntimeException expected) {
                if (spec.hasNonNull(
                        BlueConformanceFixtureSupport.FixtureField
                                .EXPECTED_ERROR_CATEGORY)) {
                    BlueConformanceFixtureExecution.assertExpectedErrorCategory(
                            spec,
                            BlueConformanceFixtureSupport.FixtureField
                                    .EXPECTED_ERROR_CATEGORY,
                            expected);
                }
                return;
            }
            throw new AssertionError("Fixture expected an error but operation succeeded: "
                    + BlueConformanceFixtureExecution.requireText(
                            spec,
                            BlueConformanceFixtureSupport.FixtureField.ID));
        }
        BlueConformanceFixtureExecution.runOperation(
                spec,
                operation,
                BlueConformanceFixtureExecution.fixtureEntries());
    }

}
