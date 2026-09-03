package blue.language.conformance.contracts;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.closure.ClosureFixtureConformance;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.CheckpointDomainValue;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;
import blue.language.processor.closure.TentativeFinalization;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.deleteTree;
import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.emptyAbsoluteDirectory;
import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.existingAbsoluteDirectory;
import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.require;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.deterministicYaml;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.readYaml;

/**
 * Compiles identity-free full-lifecycle sources into exact executable closure
 * fixtures by executing the normative production admission API.
 *
 * <p>The compiler never consumes an expected result. Source {@code expect}
 * values are post-execution assertions only. Every identity-bearing input is
 * constructed through Language or Contracts production APIs.</p>
 */
public final class FullLifecycleFixtureExporter {

    static final int SOURCE_COUNT = 18;
    static final int FIXTURE_COUNT = 31;
    static final String SOURCE_SCHEMA =
            "blue-contracts-full-lifecycle-source/1.0";
    static final String FIXTURE_SCHEMA =
            "blue-contracts-closure-fixture/1.0";
    static final String SUCCESS_MARKER =
            "FULL_LIFECYCLE_FIXTURES_EXPORTED count=31";

    static final Map<String, String> EXPECTED_SOURCE_IDS =
            FullLifecycleFixtureSourceCatalog.expectedSourceIds();

    private FullLifecycleFixtureExporter() {
    }

    /**
     * Runs the deterministic fixture exporter from the command line.
     *
     * @param args source root, candidate package root, and empty output root
     */
    public static void main(String[] args) {
        try {
            if (args.length != 3) {
                throw new IllegalArgumentException(
                        "usage: FullLifecycleFixtureExporter <sourceRoot> "
                                + "<packageRoot> <outputRoot>");
            }
            export(Paths.get(args[0]), Paths.get(args[1]),
                    Paths.get(args[2]));
            System.out.println(SUCCESS_MARKER);
        } catch (RuntimeException | IOException failure) {
            String message = failure.getMessage();
            System.err.println("FULL_LIFECYCLE_FIXTURE_EXPORT_FAILED "
                    + (message == null
                    ? failure.getClass().getSimpleName() : message));
            System.exit(2);
        }
    }

    static List<Path> export(
            Path sourceRoot,
            Path packageRoot,
            Path outputRoot) throws IOException {
        Path sources = existingAbsoluteDirectory(sourceRoot, "source root");
        Path candidate = existingAbsoluteDirectory(
                packageRoot, "candidate package root");
        Path output = emptyAbsoluteDirectory(outputRoot, "output root");
        List<Path> sourceFiles;
        try (Stream<Path> files = Files.list(sources)) {
            sourceFiles = files
                    .filter(path -> path.getFileName().toString()
                            .matches("(?:(?:fl-adm|c-evo)-[0-9]{2}"
                                    + "|c-emb-empty-[0-9]{2}"
                                    + "|c-evt-collection-[0-9]{2})"
                                    + ".*\\.yaml"))
                    .sorted()
                    .collect(Collectors.toList());
        }
        require(sourceFiles.size() == SOURCE_COUNT,
                "source root must contain exactly 18 full-lifecycle YAML sources");
        LinkedHashSet<String> sourceNames = sourceFiles.stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        require(sourceNames.equals(EXPECTED_SOURCE_IDS.keySet()),
                "source root must contain the exact FL-ADM-01..10 and "
                        + "C-EVO-18..23 plus C-EMB-EMPTY-05 and "
                        + "C-EVT-COLLECTION-07 family files; "
                        + "expected " + EXPECTED_SOURCE_IDS.keySet()
                        + " but found " + sourceNames);

        Path parent = output.getParent();
        Path staging = Files.createTempDirectory(
                parent, ".full-lifecycle-fixtures-");
        boolean published = false;
        try {
            FullLifecycleFixtureCompiler compiler =
                    new FullLifecycleFixtureCompiler(candidate);
            ArrayList<JsonNode> parsedSources = new ArrayList<JsonNode>();
            for (Path sourceFile : sourceFiles) {
                try {
                    JsonNode source = readYaml(sourceFile);
                    FullLifecycleFixtureSourceCatalog.validateSourceFamily(
                            sourceFile, source, EXPECTED_SOURCE_IDS);
                    compiler.preflight(source);
                    parsedSources.add(source);
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(
                            sourceFile.getFileName() + ": "
                                    + (failure.getMessage() == null
                                    ? failure.getClass().getSimpleName()
                                    : failure.getMessage()),
                            failure);
                }
            }
            ArrayList<Path> generated = new ArrayList<Path>();
            for (int sourceIndex = 0;
                    sourceIndex < sourceFiles.size(); sourceIndex++) {
                Path sourceFile = sourceFiles.get(sourceIndex);
                try {
                    JsonNode source = parsedSources.get(sourceIndex);
                    for (FullLifecycleFixtureCompiler.CompiledFixture fixture
                            : compiler.compile(source)) {
                        Path target = staging.resolve(fixture.fileName);
                        byte[] bytes = deterministicYaml(fixture.envelope);
                        Files.write(target, bytes);
                        generated.add(target);
                    }
                } catch (RuntimeException failure) {
                    throw new IllegalArgumentException(
                            sourceFile.getFileName() + ": "
                                    + (failure.getMessage() == null
                                    ? failure.getClass().getSimpleName()
                                    : failure.getMessage()),
                            failure);
                }
            }
            require(generated.size() == FIXTURE_COUNT,
                    "sources must compile to exactly 31 fixtures");
            ArrayList<String> names = generated.stream()
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toCollection(ArrayList::new));
            require(names.size() == new HashSet<String>(names).size(),
                    "compiled fixture names must be unique");

            Files.delete(output);
            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.createDirectory(output);
                throw new IllegalStateException(
                        "output filesystem does not support atomic publication",
                        unsupported);
            }
            published = true;
            ArrayList<Path> result = new ArrayList<Path>();
            for (String name : names) {
                result.add(output.resolve(name));
            }
            return Collections.unmodifiableList(result);
        } finally {
            if (!published) {
                deleteTree(staging);
                if (!Files.exists(output)) {
                    Files.createDirectory(output);
                }
            }
        }
    }

}
