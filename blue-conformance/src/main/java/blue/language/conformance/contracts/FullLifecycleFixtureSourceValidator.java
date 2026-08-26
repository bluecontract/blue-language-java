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

import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.EXPECTED_SOURCE_IDS;
import static blue.language.conformance.contracts.FullLifecycleFixtureExporter.SOURCE_SCHEMA;
import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.require;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.DIRECT;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.JSON;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.array;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.node;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.nullableText;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.object;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.optionalArray;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.requiredBoolean;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.requiredLong;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.requiredObject;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.text;
import static blue.language.conformance.contracts.FullLifecycleFixtureSupport.textValues;

/** Strict authored-source and macro validation for full-lifecycle fixtures. */
final class FullLifecycleFixtureSourceValidator {

    private FullLifecycleFixtureSourceValidator() {
    }

static Map<String, String> expectedSourceIds() {
    LinkedHashMap<String, String> values =
            new LinkedHashMap<String, String>();
    values.put("fl-adm-01-root-patch-event.yaml",
            "fl-adm-01-root-patch-event");
    values.put("fl-adm-02-duplicate-equal-events.yaml",
            "fl-adm-02-duplicate-equal-events");
    values.put("fl-adm-03-non-public-containing-route.yaml",
            "fl-adm-03-non-public-containing-route");
    values.put("fl-adm-04-document-update-continuation.yaml",
            "fl-adm-04-document-update-continuation");
    values.put("fl-adm-05-graceful-termination.yaml",
            "fl-adm-05-graceful-termination");
    values.put("fl-adm-06-canonical-order-representation-parity.yaml",
            "fl-adm-06-order-representation");
    values.put("fl-adm-07-finite-cyclic-route.yaml",
            "fl-adm-07-finite-cyclic-route");
    values.put("fl-adm-08-infinite-cycle-gas-retry.yaml",
            "fl-adm-08-infinite-cycle-gas-retry");
    values.put("fl-adm-09-late-member-rollback.yaml",
            "fl-adm-09-late-member-rollback");
    values.put("fl-adm-10-unknown-occurrence.yaml",
            "fl-adm-10-unknown-occurrence");
    values.put("c-evo-18-missing-exact-node.yaml",
            "c-evo-18-missing-exact-node");
    values.put("c-evo-19-missing-occurrence-evidence.yaml",
            "c-evo-19-missing-occurrence-evidence");
    values.put("c-evo-20-canonical-demand-order.yaml",
            "c-evo-20-canonical-demand-order");
    values.put("c-evo-21-retry-determinism.yaml",
            "c-evo-21-retry-determinism");
    values.put("c-evo-22-low-gas-expanded-evidence.yaml",
            "c-evo-22-low-gas-expanded-evidence");
    values.put("c-evo-23-automatic-explicit-retry-parity.yaml",
            "c-evo-23-automatic-explicit-retry-parity");
    return Collections.unmodifiableMap(values);
}

static void validateSourceFamily(Path sourceFile, JsonNode value) {
    String fileName = sourceFile.getFileName().toString();
    String expectedId = EXPECTED_SOURCE_IDS.get(fileName);
    require(expectedId != null,
            "unexpected full-lifecycle source file " + fileName);
    ObjectNode source = requiredObject(value, "source");
    require(expectedId.equals(text(source, "id")),
            fileName + " must declare id " + expectedId);
    String expectedScenario = fileName.startsWith("c-evo-")
            ? "C-EVO-" + fileName.substring(6, 8)
            : "FL-ADM-" + fileName.substring(7, 9);
    require(expectedScenario.equals(text(source, "scenario")),
            fileName + " must declare scenario " + expectedScenario);
}

static void validateSourceEnvelope(JsonNode value) {
    ObjectNode source = requiredObject(value, "source");
    validateStrictSourceSchema(source);
    require(SOURCE_SCHEMA.equals(text(
                    source, BlueLanguageConstants.OBJECT_SCHEMA)),
            "unsupported full-lifecycle source schema");
    require("admit-closure".equals(text(source, "operation")),
            "full-lifecycle source operation must be admit-closure");
    require(text(source, "id").matches(
                    "(?:fl-adm|c-evo)-[0-9]{2}(?:-[a-z0-9-]+)?"),
            "invalid full-lifecycle source id");
    require(text(source, "scenario").matches(
                    "(?:FL-ADM|C-EVO)-[0-9]{2}"),
            "invalid full-lifecycle scenario");
    require(!text(source, "description").isEmpty(),
            "source description must not be empty");
    object(source, "events");
    ObjectNode documents = object(source, "documents");
    require(documents.size() > 0,
            "source must declare at least one document");
    array(source, "occurrences");
    object(source, "runtime");
    object(source, "gas");
    object(source, "expect");
    if (source.has("inputOrder")) {
        validatePermutation(array(source, "inputOrder"), documents,
                "inputOrder");
    }
    if (source.has("cases")) {
        ArrayNode cases = array(source, "cases");
        require(cases.size() > 0, "cases must not be empty");
        LinkedHashSet<String> suffixes = new LinkedHashSet<String>();
        for (JsonNode caseValue : cases) {
            String suffix = text(caseValue, "suffix");
            require(suffix.matches("[a-z0-9][a-z0-9-]*")
                            && !suffixes.contains(suffix),
                    "case suffixes must be valid and unique");
            if (caseValue.has("inputOrder")) {
                validatePermutation(array(caseValue, "inputOrder"),
                        documents, "case inputOrder");
            }
            if (caseValue.has("repeatOf")) {
                require(suffixes.contains(text(caseValue, "repeatOf")),
                        "repeatOf must name an earlier case");
            }
            if (caseValue.has("parityWith")) {
                require(suffixes.contains(text(caseValue, "parityWith")),
                        "parityWith must name an earlier case");
            }
            suffixes.add(suffix);
        }
    }
    validateAuthoredReferences(source);
}

/**
 * Applies the closed authored-source surface from source-schema.yaml.
 * Blue values remain intentionally open; every compiler-control object is
 * closed here so a typo can never become ignored input.
 */
private static void validateStrictSourceSchema(ObjectNode source) {
    validateObjectShape(source, "source",
            fields(BlueLanguageConstants.OBJECT_SCHEMA,
                    "id", "scenario", "description",
                    "operation", "events", "documents", "occurrences",
                    "runtime", "gas", "expect"),
            fields(BlueLanguageConstants.OBJECT_SCHEMA,
                    "id", "scenario", "description",
                    "operation", "events", "documents", "inputOrder",
                    "occurrences", "runtime", "gas", "cases",
                    "expect"));

    ObjectNode documents = object(source, "documents");
    Iterator<Map.Entry<String, JsonNode>> documentFields =
            documents.fields();
    while (documentFields.hasNext()) {
        Map.Entry<String, JsonNode> field = documentFields.next();
        ObjectNode wrapper = requiredObject(field.getValue(),
                "documents." + field.getKey());
        validateObjectShape(wrapper,
                "documents." + field.getKey(),
                fields("publicRoot", "document"),
                fields("publicRoot", "document"));
        requiredBoolean(wrapper, "publicRoot");
    }

    validateOccurrenceSchema(array(source, "occurrences"), "occurrences");
    validateRuntimeSchema(object(source, "runtime"));
    validateGasSchema(object(source, "gas"));
    if (source.has("inputOrder")) {
        validateUniqueTextArray(array(source, "inputOrder"),
                "inputOrder");
    }
    if (source.has("cases")) {
        validateCasesSchema(array(source, "cases"));
    }
    validateExpectSchema(object(source, "expect"));
}

private static void validateOccurrenceSchema(
        ArrayNode occurrences,
        String prefix) {
    for (int index = 0; index < occurrences.size(); index++) {
        ObjectNode occurrence = requiredObject(occurrences.get(index),
                prefix + "[" + index + "]");
        String label = prefix + "[" + index + "]";
        validateObjectShape(occurrence, label,
                fields("sourceDocumentId", "sourcePath",
                        "activationGeneration", "targetDocumentId",
                        "active"),
                fields("sourceDocumentId", "sourcePath",
                        "activationGeneration", "targetDocumentId",
                        "active"));
        text(occurrence, "sourceDocumentId");
        String pointer = text(occurrence, "sourcePath");
        require(pointer.matches(
                        "^/(?:[^~]|~0|~1)*(?:/(?:[^~]|~0|~1)*)*$"),
                label + ".sourcePath is not a valid JSON Pointer");
        require(requiredLong(occurrence, "activationGeneration") >= 1L,
                label + ".activationGeneration must be at least 1");
        text(occurrence, "targetDocumentId");
        requiredBoolean(occurrence, "active");
    }
}

private static void validateRuntimeSchema(ObjectNode runtime) {
    validateObjectShape(runtime, "runtime",
            fields("handlers", "initializationHandlers"),
            fields("handlers", "initializationHandlers"));
    for (String bucketName : Arrays.asList(
            "handlers", "initializationHandlers")) {
        ObjectNode bucket = object(runtime, bucketName);
        Iterator<Map.Entry<String, JsonNode>> handlers = bucket.fields();
        while (handlers.hasNext()) {
            Map.Entry<String, JsonNode> handler = handlers.next();
            String label = "runtime." + bucketName + "."
                    + handler.getKey();
            ObjectNode result = requiredObject(handler.getValue(), label);
            validateObjectShape(result, label,
                    Collections.<String>emptySet(),
                    fields("patches", "events", "termination", "fail"));
            if (result.has("patches")) {
                ArrayNode patches = array(result, "patches");
                for (int index = 0; index < patches.size(); index++) {
                    validatePatchSchema(requiredObject(
                            patches.get(index),
                            label + ".patches[" + index + "]"),
                            label + ".patches[" + index + "]");
                }
            }
            if (result.has("events")) {
                array(result, "events");
            }
            if (result.has("termination")) {
                ObjectNode termination = object(result, "termination");
                validateObjectShape(termination,
                        label + ".termination",
                        fields("cause"), fields("cause", "reason"));
                text(termination, "cause");
                if (termination.has("reason")) {
                    require(termination.get("reason").isTextual(),
                            label + ".termination.reason must be Text");
                }
            }
            if (result.has("fail")) {
                text(result, "fail");
                require(!result.has("patches")
                                && !result.has("events")
                                && !result.has("termination"),
                        label + ".fail is exclusive with successful output");
            }
        }
    }
}

private static void validatePatchSchema(ObjectNode patch, String label) {
    validateObjectShape(patch, label,
            fields("op", "path"), fields("op", "path", "val"));
    String operation = text(patch, "op");
    require(fields("add", "replace", "remove").contains(operation),
            label + ".op must be add, replace, or remove");
    text(patch, "path");
    if ("remove".equals(operation)) {
        require(!patch.has("val"),
                label + ".val is forbidden for remove");
    } else {
        require(patch.has("val"),
                label + ".val is required for " + operation);
    }
}

private static void validateGasSchema(ObjectNode gas) {
    validateObjectShape(gas, "gas", fields("sharedLimit"),
            fields("sharedLimit"));
    JsonNode limit = gas.get("sharedLimit");
    require((limit.isTextual()
                    && "release-default".equals(limit.textValue()))
                    || (limit.isIntegralNumber()
                    && limit.canConvertToLong()
                    && limit.longValue() >= 0L),
            "gas.sharedLimit must be release-default or a non-negative Integer");
}

private static void validateCasesSchema(ArrayNode cases) {
    require(cases.size() > 0, "cases must not be empty");
    for (int index = 0; index < cases.size(); index++) {
        ObjectNode value = requiredObject(cases.get(index),
                "cases[" + index + "]");
        String label = "cases[" + index + "]";
        validateObjectShape(value, label, fields("suffix"),
                fields("suffix", "inputOrder", "representation",
                        "repeatOf", "occurrences", "expect",
                        "parityWith", "parityProjections"));
        require(text(value, "suffix").matches(
                        "[a-z0-9][a-z0-9-]*"),
                label + ".suffix is invalid");
        if (value.has("inputOrder")) {
            validateUniqueTextArray(array(value, "inputOrder"),
                    label + ".inputOrder");
        }
        if (value.has("representation")) {
            ObjectNode representation = object(value, "representation");
            validateObjectShape(representation,
                    label + ".representation",
                    fields("documentId", "path", "form"),
                    fields("documentId", "path", "form"));
            text(representation, "documentId");
            text(representation, "path");
            require(fields("pure-reference", "inline").contains(
                            text(representation, "form")),
                    label + ".representation.form is invalid");
        }
        if (value.has("repeatOf")) {
            text(value, "repeatOf");
            require(!value.has("inputOrder")
                            && !value.has("representation")
                            && !value.has("occurrences"),
                    label + ".repeatOf cannot alter exact input");
        }
        if (value.has("occurrences")) {
            validateOccurrenceSchema(
                    array(value, "occurrences"), label + ".occurrences");
        }
        if (value.has("expect")) {
            validateExpectSchema(object(value, "expect"));
        }
        require(value.has("parityWith")
                        == value.has("parityProjections"),
                label + " parityWith and parityProjections are paired");
        if (value.has("parityWith")) {
            text(value, "parityWith");
            validateEnumArray(value, "parityProjections",
                    fields("invocationIdentity", "processResult", "gas",
                            "workTrace", "finalizations",
                            "rejectionEvidence", "resourceDemands",
                            "attempt", "harnessTranscript"), true);
            require(array(value, "parityProjections").size() > 0,
                    label + ".parityProjections must not be empty");
        }
    }
}

private static void validateExpectSchema(ObjectNode expect) {
    if ("NeedsResources".equals(nullableText(
            expect, "attemptOutcome"))) {
        validateObjectShape(expect, "expect",
                fields("attemptOutcome", "resourceDemandKinds"),
                fields("attemptOutcome", "resourceDemandKinds",
                        "requiredExactCount", "parityAcrossCases"));
        require(array(expect, "resourceDemandKinds").size() > 0,
                "expect.resourceDemandKinds must not be empty");
        validateEnumArray(expect, "resourceDemandKinds",
                fields("EXACT_NODE", "MANAGED_OCCURRENCE_EVIDENCE"),
                false);
        validateOptionalNonNegative(expect, "requiredExactCount");
        validateEnumArray(expect, "parityAcrossCases",
                fields("resourceDemands", "attempt"), true);
        return;
    }
    validateObjectShape(expect, "expect",
            fields("status", "atomic", "rollbackToInput",
                    "publicEvents", "checkpointWrites",
                    "commitCompanion"),
            fields("status", "diagnosticCategory", "atomic",
                    "rollbackToInput", "documents", "pointers",
                    "absentPointers", "workCounts",
                    "workContainsInOrder", "workKindsExact",
                    "publicEvents", "distinctEventOccurrenceIdentities",
                    "eventOccurrenceOrdinals",
                    "deliverySourceOccurrenceOrdinals",
                    "checkpointWrites", "terminationMarkerCount",
                    "finalizationBoundary",
                    "finalizationBoundariesExact", "commitCompanion",
                    "parityAcrossCases"));
    require(fields("success", "gas-limit-exceeded", "runtime-fatal",
                    "subscription-surface-invalid").contains(
                    text(expect, "status")),
            "expect.status is invalid");
    if (expect.has("diagnosticCategory")) {
        text(expect, "diagnosticCategory");
    }
    requiredBoolean(expect, "atomic");
    requiredBoolean(expect, "rollbackToInput");
    validateDocumentExpectations(optionalArray(expect, "documents"));
    validatePointerExpectations(optionalArray(expect, "pointers"), true);
    validatePointerExpectations(
            optionalArray(expect, "absentPointers"), false);
    validateWorkCounts(optionalArray(expect, "workCounts"));
    Set<String> workKinds = fields("INITIALIZATION", "LIFECYCLE",
            "TRIGGERED_EVENT", "EMBEDDED_EVENT", "DOCUMENT_UPDATE");
    validateEnumArray(expect, "workContainsInOrder", workKinds, false);
    validateEnumArray(expect, "workKindsExact", workKinds, false);
    validatePublicEventExpectations(array(expect, "publicEvents"));
    validateOptionalNonNegative(expect,
            "distinctEventOccurrenceIdentities");
    validateOrdinalArray(expect, "eventOccurrenceOrdinals");
    validateOrdinalArray(expect, "deliverySourceOccurrenceOrdinals");
    requiredLong(expect, "checkpointWrites");
    validateOptionalNonNegative(expect, "terminationMarkerCount");
    Set<String> boundaries = fields("WORK", "INITIALIZATION_BATCH",
            "TERMINATION_MARKER", "CHECKPOINT_SETTLEMENT");
    if (expect.has("finalizationBoundary")) {
        require(boundaries.contains(text(expect,
                        "finalizationBoundary")),
                "expect.finalizationBoundary is invalid");
    }
    validateEnumArray(expect, "finalizationBoundariesExact",
            boundaries, false);
    require(fields("present", "absent").contains(
                    text(expect, "commitCompanion")),
            "expect.commitCompanion is invalid");
    validateEnumArray(expect, "parityAcrossCases",
            fields("invocationIdentity", "processResult", "gas",
                    "workTrace", "finalizations", "rejectionEvidence",
                    "resourceDemands", "attempt"),
            true);
}

private static void validateDocumentExpectations(ArrayNode values) {
    for (int index = 0; index < values.size(); index++) {
        ObjectNode value = requiredObject(values.get(index),
                "expect.documents[" + index + "]");
        String label = "expect.documents[" + index + "]";
        validateObjectShape(value, label, fields("documentId"),
                fields("documentId", "initialized", "terminated"));
        text(value, "documentId");
        if (value.has("initialized")) {
            requiredBoolean(value, "initialized");
        }
        if (value.has("terminated")) {
            requiredBoolean(value, "terminated");
        }
    }
}

private static void validatePointerExpectations(
        ArrayNode values, boolean requireValue) {
    for (int index = 0; index < values.size(); index++) {
        String prefix = requireValue ? "expect.pointers["
                : "expect.absentPointers[";
        String label = prefix + index + "]";
        ObjectNode value = requiredObject(values.get(index), label);
        Set<String> required = requireValue
                ? fields("documentId", "pointer",
                        BlueLanguageConstants.OBJECT_VALUE)
                : fields("documentId", "pointer");
        validateObjectShape(value, label, required, required);
        text(value, "documentId");
        text(value, "pointer");
    }
}

private static void validateWorkCounts(ArrayNode values) {
    Set<String> kinds = fields("INITIALIZATION", "LIFECYCLE",
            "TRIGGERED_EVENT", "EMBEDDED_EVENT", "DOCUMENT_UPDATE");
    for (int index = 0; index < values.size(); index++) {
        ObjectNode value = requiredObject(values.get(index),
                "expect.workCounts[" + index + "]");
        String label = "expect.workCounts[" + index + "]";
        validateObjectShape(value, label, fields("kind", "count"),
                fields("kind", "count"));
        require(kinds.contains(text(value, "kind")),
                label + ".kind is invalid");
        requiredLong(value, "count");
    }
}

private static void validatePublicEventExpectations(ArrayNode values) {
    for (int index = 0; index < values.size(); index++) {
        ObjectNode value = requiredObject(values.get(index),
                "expect.publicEvents[" + index + "]");
        String label = "expect.publicEvents[" + index + "]";
        validateObjectShape(value, label, fields("event", "emitter"),
                fields("event", "emitter"));
        text(value, "event");
        text(value, "emitter");
    }
}

private static void validateOrdinalArray(JsonNode parent, String field) {
    if (!parent.has(field)) {
        return;
    }
    ArrayNode values = array(parent, field);
    for (JsonNode value : values) {
        require(value.isIntegralNumber() && value.canConvertToLong()
                        && value.longValue() >= 0L,
                "expect." + field
                        + " must contain non-negative Integers");
    }
}

private static void validateOptionalNonNegative(
        JsonNode parent, String field) {
    if (parent.has(field)) {
        requiredLong(parent, field);
    }
}

private static void validateEnumArray(
        JsonNode parent,
        String field,
        Set<String> permitted,
        boolean unique) {
    if (!parent.has(field)) {
        return;
    }
    ArrayNode values = array(parent, field);
    LinkedHashSet<String> observed = new LinkedHashSet<String>();
    for (JsonNode value : values) {
        require(value.isTextual()
                        && permitted.contains(value.textValue()),
                "expect." + field + " contains an invalid value");
        require(!unique || observed.add(value.textValue()),
                "expect." + field + " must contain unique values");
    }
}

private static void validateUniqueTextArray(
        ArrayNode values, String label) {
    List<String> strings = textValues(values);
    require(new LinkedHashSet<String>(strings).size() == strings.size(),
            label + " must contain unique values");
}

private static void validateObjectShape(
        ObjectNode value,
        String label,
        Set<String> required,
        Set<String> allowed) {
    Iterator<String> names = value.fieldNames();
    while (names.hasNext()) {
        String name = names.next();
        require(allowed.contains(name),
                label + " contains unknown field " + name);
    }
    for (String name : required) {
        require(value.has(name),
                label + " is missing required field " + name);
    }
}

private static Set<String> fields(String... names) {
    return new LinkedHashSet<String>(Arrays.asList(names));
}

private static void validateAuthoredReferences(ObjectNode source) {
    ObjectNode documents = object(source, "documents");
    Set<String> documentIds = new LinkedHashSet<String>();
    documents.fieldNames().forEachRemaining(documentIds::add);
    Set<String> eventIds = new LinkedHashSet<String>();
    object(source, "events").fieldNames().forEachRemaining(eventIds::add);
    validateAuthoredReferences(
            documentIds,
            eventIds,
            array(source, "occurrences"),
            object(source, "expect"));
    for (JsonNode caseValue : cases(source)) {
        if (caseValue != null
                && (caseValue.has("occurrences")
                || caseValue.has("expect"))) {
            validateAuthoredReferences(
                    documentIds,
                    eventIds,
                    occurrences(source, caseValue),
                    caseValue.has("expect")
                            ? object(caseValue, "expect")
                            : object(source, "expect"));
        }
    }
}

private static void validateAuthoredReferences(
        Set<String> documentIds,
        Set<String> eventIds,
        ArrayNode occurrences,
        JsonNode expect) {
    for (JsonNode occurrence : occurrences) {
        require(documentIds.contains(text(
                        occurrence, "sourceDocumentId"))
                        && documentIds.contains(text(
                        occurrence, "targetDocumentId")),
                "occurrence references unknown document");
        require(requiredLong(occurrence, "activationGeneration") > 0L,
                "occurrence activationGeneration must be positive");
    }
    for (String field : Arrays.asList(
            "documents", "pointers", "absentPointers")) {
        for (JsonNode item : optionalArray(expect, field)) {
            require(documentIds.contains(text(item, "documentId")),
                    field + " expectation references unknown document");
        }
    }
    for (JsonNode item : optionalArray(expect, "publicEvents")) {
        require(eventIds.contains(text(item, "event"))
                        && documentIds.contains(text(item, "emitter")),
                "public event expectation has an unresolved reference");
    }
    HashSet<String> expectedKinds = new HashSet<String>();
    for (JsonNode item : optionalArray(expect, "workCounts")) {
        require(expectedKinds.add(text(item, "kind")),
                "expected work kinds must be unique");
    }
}

static void validateRuntime(
        JsonNode source,
        ObjectNode runtime) {
    ObjectNode handlers = object(runtime, "handlers");
    ObjectNode initialization = object(runtime, "initializationHandlers");
    Set<String> keys = new LinkedHashSet<String>();
    for (ObjectNode bucket : Arrays.asList(handlers, initialization)) {
        Iterator<Map.Entry<String, JsonNode>> fields = bucket.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            require(keys.add(field.getKey()),
                    "runtime Handler appears in both buckets: "
                            + field.getKey());
            String[] parts = field.getKey().split("/", 2);
            require(parts.length == 2,
                    "runtime Handler key must be documentId/contractKey");
            JsonNode wrapper = object(source, "documents").get(parts[0]);
            require(wrapper != null,
                    "runtime Handler references unknown document");
            JsonNode contract = requiredObject(wrapper.get("document"),
                    "authored document")
                    .path(BlueLanguageConstants.OBJECT_CONTRACTS)
                    .path(parts[1]);
            require(contract.isObject(),
                    "runtime key does not select an authored contract");
            ObjectNode result = requiredObject(
                    field.getValue(), "runtime Handler result");
            if (result.has("fail")) {
                require(result.size() == 1,
                        "runtime fail is exclusive with successful output");
            }
            for (JsonNode patch : optionalArray(result, "patches")) {
                String path = text(patch, "path");
                require(!path.equals("/contracts/initialized")
                                && !path.startsWith(
                                "/contracts/initialized/")
                                && !path.equals("/contracts/terminated")
                                && !path.startsWith(
                                "/contracts/terminated/")
                                && !path.equals("/contracts/checkpoint")
                                && !path.startsWith(
                                "/contracts/checkpoint/"),
                        "runtime patch targets processor-owned state");
            }
        }
    }
}

static List<JsonNode> cases(JsonNode source) {
    if (!source.has("cases")) {
        return Collections.singletonList(null);
    }
    ArrayList<JsonNode> result = new ArrayList<JsonNode>();
    array(source, "cases").forEach(result::add);
    return result;
}

static ArrayNode occurrences(JsonNode source, JsonNode caseValue) {
    return caseValue != null && caseValue.has("occurrences")
            ? array(caseValue, "occurrences")
            : array(source, "occurrences");
}

static List<String> inputOrder(
        JsonNode source,
        JsonNode caseValue,
        ObjectNode documents) {
    JsonNode selected = caseValue != null && caseValue.has("inputOrder")
            ? caseValue.get("inputOrder") : source.get("inputOrder");
    if (selected == null) {
        ArrayList<String> sorted = new ArrayList<String>();
        documents.fieldNames().forEachRemaining(sorted::add);
        Collections.sort(sorted);
        return sorted;
    }
    ArrayList<String> result = new ArrayList<String>(
            textValues((ArrayNode) selected));
    validatePermutation((ArrayNode) selected, documents, "inputOrder");
    return result;
}

private static void validatePermutation(
        ArrayNode order,
        ObjectNode documents,
        String label) {
    LinkedHashSet<String> values = new LinkedHashSet<String>(
            textValues(order));
    LinkedHashSet<String> expected = new LinkedHashSet<String>();
    documents.fieldNames().forEachRemaining(expected::add);
    require(values.size() == order.size() && values.equals(expected),
            label + " must be a complete document permutation");
}

static void validateDocumentOccurrenceMacros(
        JsonNode source,
        JsonNode caseValue,
        List<String> inputOrder) {
    ObjectNode documents = object(source, "documents");
    LinkedHashMap<String, String> targetsByLocation =
            new LinkedHashMap<String, String>();
    for (JsonNode occurrence : occurrences(source, caseValue)) {
        String sourceDocumentId = text(
                occurrence, "sourceDocumentId");
        String sourcePath = text(occurrence, "sourcePath");
        String targetDocumentId = text(
                occurrence, "targetDocumentId");
        String location = occurrenceLocation(
                sourceDocumentId, sourcePath);
        require(targetsByLocation.put(location, targetDocumentId) == null,
                "multiple occurrences declare the same source path: "
                        + sourceDocumentId + sourcePath);
        JsonNode body = requiredObject(
                documents.get(sourceDocumentId),
                "document " + sourceDocumentId).get("document");
        JsonNode authored = body == null
                ? null : body.at(sourcePath);
        if (authored == null || authored.isMissingNode()) {
            require(!requiredBoolean(occurrence, "active"),
                    "active occurrence source path is absent in authored "
                            + "document: " + sourceDocumentId + sourcePath);
            continue;
        }
        validateAlignedDocumentMacro(
                authored,
                sourceDocumentId,
                sourcePath,
                targetDocumentId,
                inputOrder,
                hasExplicitInputOrder(source, caseValue));
    }

    Iterator<Map.Entry<String, JsonNode>> fields = documents.fields();
    while (fields.hasNext()) {
        Map.Entry<String, JsonNode> document = fields.next();
        JsonNode body = requiredObject(document.getValue(),
                "document " + document.getKey()).get("document");
        validateNoUnboundDocumentMacros(
                body,
                document.getKey(),
                "",
                targetsByLocation,
                inputOrder,
                hasExplicitInputOrder(source, caseValue));
    }
}

private static void validateAlignedDocumentMacro(
        JsonNode authored,
        String sourceDocumentId,
        String sourcePath,
        String targetDocumentId,
        List<String> inputOrder,
        boolean explicitInputOrder) {
    ObjectNode object = requiredObject(authored,
            "managed occurrence value " + sourceDocumentId + sourcePath);
    if (object.has("$documentBlueId")
            || object.has("$documentInline")) {
        require(object.size() == 1,
                "document macro must be the entire occurrence value at "
                        + sourceDocumentId + sourcePath);
        String macro = object.has("$documentBlueId")
                ? "$documentBlueId" : "$documentInline";
        require(targetDocumentId.equals(text(object, macro)),
                "document macro target disagrees with occurrence at "
                        + sourceDocumentId + sourcePath);
        return;
    }
    JsonNode identity = object.get(
            BlueLanguageConstants.OBJECT_BLUE_ID);
    require(identity != null && identity.isTextual()
                    && identity.textValue().startsWith("this#")
                    && object.size() == 1,
            "managed occurrence value must be a document macro or this#n "
                    + "placeholder at " + sourceDocumentId + sourcePath);
    require(explicitInputOrder,
            "this#n placeholder requires explicit inputOrder at "
                    + sourceDocumentId + sourcePath);
    int index = placeholderIndex(identity.textValue(),
            sourceDocumentId + sourcePath);
    require(index < inputOrder.size()
                    && targetDocumentId.equals(inputOrder.get(index)),
            identity.textValue() + " disagrees with occurrence target "
                    + targetDocumentId + " at "
                    + sourceDocumentId + sourcePath);
}

private static void validateNoUnboundDocumentMacros(
        JsonNode value,
        String documentId,
        String pointer,
        Map<String, String> targetsByLocation,
        List<String> inputOrder,
        boolean explicitInputOrder) {
    if (value == null || value.isValueNode()) {
        return;
    }
    if (value.isArray()) {
        for (int index = 0; index < value.size(); index++) {
            validateNoUnboundDocumentMacros(
                    value.get(index), documentId,
                    pointer + "/" + index,
                    targetsByLocation, inputOrder, explicitInputOrder);
        }
        return;
    }
    ObjectNode object = (ObjectNode) value;
    boolean documentMacro = object.has("$documentBlueId")
            || object.has("$documentInline");
    JsonNode identity = object.get(
            BlueLanguageConstants.OBJECT_BLUE_ID);
    boolean placeholder = identity != null && identity.isTextual()
            && identity.textValue().startsWith("this#");
    if (documentMacro || placeholder) {
        String target = targetsByLocation.get(
                occurrenceLocation(documentId, pointer));
        require(target != null,
                "document macro/placeholder has no declared occurrence at "
                        + documentId + pointer);
        validateAlignedDocumentMacro(object, documentId, pointer, target,
                inputOrder, explicitInputOrder);
        return;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = object.fields();
    while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        validateNoUnboundDocumentMacros(
                field.getValue(), documentId,
                pointer + "/" + escapePointer(field.getKey()),
                targetsByLocation, inputOrder, explicitInputOrder);
    }
}

private static boolean hasExplicitInputOrder(
        JsonNode source, JsonNode caseValue) {
    return (caseValue != null && caseValue.has("inputOrder"))
            || source.has("inputOrder");
}

private static int placeholderIndex(String value, String label) {
    try {
        int index = Integer.parseInt(value.substring(5));
        require(index >= 0,
                "invalid cyclic placeholder " + value + " at " + label);
        return index;
    } catch (NumberFormatException invalid) {
        throw new IllegalArgumentException(
                "invalid cyclic placeholder " + value + " at " + label,
                invalid);
    }
}

private static String occurrenceLocation(
        String documentId, String pointer) {
    return documentId + "\u0000" + pointer;
}

private static String escapePointer(String value) {
    return value.replace("~", "~0").replace("/", "~1");
}

static String referenceIdentityAt(Node body, String path) {
    Node selected = NodePathEditor.getOrNull(body, path);
    require(selected != null,
            "occurrence source path is absent: " + path);
    String blueId = selected.getBlueId();
    return blueId == null ? DIRECT.directBlueId(selected) : blueId;
}

static String scalarTextAt(Node body, String path) {
    Node selected = NodePathEditor.getOrNull(body, path);
    require(selected != null && selected.getValue() instanceof String,
            "documentId must be an authored Text value");
    return (String) selected.getValue();
}

}
