package blue.language.conformance.contracts;

import static blue.language.conformance.contracts.ContractsFixtureExecutionEngine.*;
import static blue.language.conformance.contracts.ContractsFixtureInputPreparer.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionExtractor.*;
import static blue.language.conformance.contracts.ContractsFixtureProjectionSupport.*;

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


/** Validates and installs the closed scripted Contracts fixture controls. */
abstract class ContractsFixtureScriptedEnvironment extends ContractsFixtureInputPreparer {

    /**
     * Rejects controls whose causal path is absent from the published input.
     * The harness must not manufacture an embedded scope or count a handler
     * that can never be selected as coverage of the declared control.
     */
    void validateExecutableControls(JsonNode fixture) {
        JsonNode input = fixture.path(
                ContractsFixtureConstants.Field.INPUT);
        JsonNode runtime = input.path(ContractsFixtureConstants.Field.RUNTIME);
        if (!runtime.isObject()) {
            return;
        }

        String fixtureId = fixture.path(
                ContractsFixtureConstants.Field.ID).asText();
        ObjectNode root = requireObject(
                input.get(ContractsFixtureConstants.Field.ROOT),
                "input.root").deepCopy();
        applyBuilders(root, input.path(ContractsFixtureConstants.Field.BUILDERS));
        promoteMixedFixtureScalarToObject(root);
        List<ScopeValue> scopes = enumerateDeclaredScopes(root);
        Set<String> scopePaths = new LinkedHashSet<>();
        for (ScopeValue scope : scopes) {
            scopePaths.add(scope.path);
        }
        JsonNode feeder = input.path(ContractsFixtureConstants.Field.FEEDER);
        JsonNode selectedChild = firstNonRootDeliveryHintOrNull(feeder);

        if (runtime.has("childEmissions")) {
            if (runtime.get("childEmissions").size() == 0) {
                contradiction(
                        fixtureId,
                        "runtime.childEmissions",
                        "the emission list is empty");
            }
            requireSelectedChild(
                    fixtureId,
                    "runtime.childEmissions",
                    selectedChild,
                    scopePaths);
        }

        JsonNode cascade = runtime.path("cascadeMutation");
        if (!cascade.isObject()) {
            return;
        }
        if (cascade.path(
                "replaceScopeDuringLifecycle").asBoolean(false)) {
            String target = cascade.path("replaceScope").asText(null);
            requireEmbeddedTarget(
                    fixtureId,
                    "runtime.cascadeMutation.replaceScopeDuringLifecycle",
                    target,
                    scopePaths,
                    "no exact non-root replacement scope is declared");
        }
        if (cascade.path(
                "sourceCutOffDuringUpdate").asBoolean(false)) {
            String target = cascade.path("replaceScope").asText(null);
            if (target == null && selectedChild != null) {
                target = selectedChild.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText(null);
            }
            requireEmbeddedTarget(
                    fixtureId,
                    "runtime.cascadeMutation.sourceCutOffDuringUpdate",
                    target,
                    scopePaths,
                    "the only possible Document Update source is Root");
            if (selectedChild == null
                    || !target.equals(
                    selectedChild.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText())
                    || !selectedChildCanProduceUpdate(
                    root, runtime, selectedChild)) {
                contradiction(
                        fixtureId,
                        "runtime.cascadeMutation.sourceCutOffDuringUpdate",
                        "no selected Handler in " + target
                                + " can originate the update being cut off");
            }
        }
    }

    static void requireSelectedChild(
            String fixtureId,
            String control,
            JsonNode selectedChild,
            Set<String> scopePaths) {
        if (selectedChild == null) {
            contradiction(
                    fixtureId,
                    control,
                    "deliverySnapshot contains no non-root occurrence");
        }
        String path = selectedChild.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText();
        if (!scopePaths.contains(path)) {
            contradiction(
                    fixtureId,
                    control,
                    "selected child " + path
                            + " is not reachable through Process Embedded");
        }
    }

    static void requireEmbeddedTarget(
            String fixtureId,
            String control,
            String target,
            Set<String> scopePaths,
            String absentReason) {
        if (target == null || "/".equals(target)) {
            contradiction(fixtureId, control, absentReason);
        }
        if (!scopePaths.contains(target)) {
            contradiction(
                    fixtureId,
                    control,
                    "replacement target " + target
                            + " is not a declared embedded scope root");
        }
    }

    static void contradiction(String fixtureId,
                                      String control,
                                      String reason) {
        throw new FixturePackageContradictionException(
                fixtureId, control, reason);
    }


    /**
     * Expands non-Blue runtime controls into ordinary fixture contracts. The
     * installed handlers still have to be discovered, matched, and executed
     * by the production processor; this method never mutates run state.
     */
    void installRuntimeContracts(ObjectNode root,
                                         JsonNode runtime,
                                         JsonNode feeder) {
        if (runtime == null || !runtime.isObject()) {
            return;
        }

        JsonNode cascade = runtime.get("cascadeMutation");

        if (runtime.has("initializationPatches")) {
            promoteFixtureScalarToObject(root);
            for (ScopeValue scope : enumerateDeclaredScopes(root)) {
                ObjectNode contracts = contractsObject(scope.value);
                installHandlerPair(
                        contracts,
                        FIXTURE_INIT_CHANNEL,
                        registryId("LifecycleEventChannel"),
                        FIXTURE_INIT_HANDLER,
                        null,
                        null);
            }
        }

        if (runtime.has("childEmissions")) {
            JsonNode childHint = firstNonRootDeliveryHint(feeder);
            String childPath = childHint.path(ContractsFixtureConstants.Field.SCOPE_PATH).asText();
            ObjectNode child = requireObject(
                    jsonAt(root, childPath),
                    "selected child scope " + childPath);
            installScriptedHandler(
                    contractsObject(child),
                    FIXTURE_CHILD_EMITTER_HANDLER,
                    childHint.path(ContractsFixtureConstants.Field.CHANNEL_KEY).asText(),
                    null,
                    UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
        }

        if (runtime.path("rootForwardAll").asBoolean(false)) {
            ObjectNode contracts = contractsObject(root);
            List<String> childPaths = directRootChildScopePaths(root);
            if (childPaths.isEmpty()) {
                /*
                 * The control promises to install the Root handler, not that
                 * the fixture must deliver a descendant occurrence to it.
                 * A non-matching source path keeps that installation ordinary
                 * and inert without manufacturing a child scope.
                 */
                childPaths = Collections.singletonList(
                        FIXTURE_ABSENT_CHILD_PATH);
            }
            for (int index = 0; index < childPaths.size(); index++) {
                String suffix = index == 0 ? "" : "_" + index;
                String channelKey = FIXTURE_EMBEDDED_CHANNEL + suffix;
                ObjectNode channel = installContract(
                        contracts, channelKey,
                        registryId("EmbeddedNodeChannel"));
                channel.put("sourcePath", childPaths.get(index));
                installScriptedHandler(
                        contracts,
                        FIXTURE_FORWARD_HANDLER + suffix,
                        channelKey,
                        null,
                        UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
            }
        }

        if (runtime.has("nestedEnqueues")) {
            ObjectNode contracts = contractsObject(root);
            installContract(
                    contracts, FIXTURE_TRIGGERED_CHANNEL,
                    registryId("TriggeredEventChannel"));
            installScriptedHandler(
                    contracts,
                    FIXTURE_NESTED_HANDLER,
                    FIXTURE_TRIGGERED_CHANNEL,
                    null,
                    UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
        }

        if (cascade != null && cascade.isObject()) {
            ObjectNode contracts = contractsObject(root);
            boolean lifecycle = cascade.path(
                    "replaceScopeDuringLifecycle").asBoolean(false);
            boolean sourceCutOff = cascade.path(
                    "sourceCutOffDuringUpdate").asBoolean(false);
            if (lifecycle) {
                installHandlerPair(
                        contracts,
                        FIXTURE_LIFECYCLE_CHANNEL,
                        registryId("LifecycleEventChannel"),
                        FIXTURE_LIFECYCLE_HANDLER,
                        registryId("DocumentProcessingInitiated"),
                        UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
            }
            if (sourceCutOff || !lifecycle) {
                ObjectNode channel = installContract(
                        contracts,
                        FIXTURE_UPDATE_CHANNEL,
                        registryId("DocumentUpdateChannel"));
                channel.put("path", "/");
                installScriptedHandler(
                        contracts,
                        FIXTURE_CASCADE_HANDLER,
                        FIXTURE_UPDATE_CHANNEL,
                        null,
                        UncheckedObjectMapper.JSON_MAPPER.createObjectNode());
            }
        }
    }

    static ObjectNode contractsObject(ObjectNode scope) {
        return objectField(
                scope, ProcessorContractConstants.KEY_CONTRACTS, true);
    }

    static void installHandlerPair(
            ObjectNode contracts,
            String channelKey,
            String channelTypeBlueId,
            String handlerKey,
            String eventTypeBlueId,
            ObjectNode result) {
        installContract(contracts, channelKey, channelTypeBlueId);
        installScriptedHandler(
                contracts, handlerKey, channelKey, eventTypeBlueId, result);
    }

    static ObjectNode installScriptedHandler(
            ObjectNode contracts,
            String handlerKey,
            String channelKey,
            String eventTypeBlueId,
            ObjectNode result) {
        ObjectNode handler = installContract(
                contracts, handlerKey, MockTypeBlueIds.MOCK_HANDLER);
        handler.put("channel", channelKey);
        if (eventTypeBlueId != null) {
            handler.putObject(ContractsFixtureConstants.Field.EVENT)
                    .putObject(BlueLanguageConstants.OBJECT_TYPE)
                    .put(BlueLanguageConstants.OBJECT_BLUE_ID, eventTypeBlueId);
        }
        if (result != null) {
            handler.set(ContractsFixtureConstants.Field.RESULT, result.deepCopy());
        }
        return handler;
    }

    static ObjectNode installContract(
            ObjectNode contracts,
            String key,
            String typeBlueId) {
        if (contracts.has(key)) {
            throw new IllegalArgumentException(
                    "Fixture runtime contract key collision: " + key);
        }
        ObjectNode contract = contracts.putObject(key);
        contract.putObject(BlueLanguageConstants.OBJECT_TYPE).put(BlueLanguageConstants.OBJECT_BLUE_ID, typeBlueId);
        return contract;
    }

    void applyBuilders(ObjectNode root, JsonNode builders) {
        if (!builders.isArray()) {
            return;
        }
        for (JsonNode builder : builders) {
            String kind = builder.path("kind").asText();
            JsonNode value;
            if ("generated-object".equals(kind)) {
                int count = exactInt(builder.get("memberCount"),
                        "builder.memberCount");
                int width = Math.max(1,
                        Integer.toString(Math.max(0, count - 1)).length());
                ObjectNode object = UncheckedObjectMapper.JSON_MAPPER.createObjectNode();
                for (int index = 0; index < count; index++) {
                    String suffix = String.format("%0" + width + "d", index);
                    object.set(builder.path("keyPrefix").asText() + suffix,
                            builder.get(BlueLanguageConstants.OBJECT_VALUE).deepCopy());
                }
                value = object;
            } else if ("generated-list".equals(kind)) {
                int count = exactInt(builder.get("itemCount"),
                        "builder.itemCount");
                ArrayNode array = UncheckedObjectMapper.JSON_MAPPER.createArrayNode();
                for (int index = 0; index < count; index++) {
                    array.add(builder.get("item").deepCopy());
                }
                value = array;
            } else if ("repeated-text".equals(kind)) {
                int count = exactInt(builder.get("codePointCount"),
                        "builder.codePointCount");
                String unit = builder.path("text").asText();
                StringBuilder repeated = new StringBuilder();
                for (int index = 0; index < count; index++) {
                    repeated.append(unit);
                }
                value = UncheckedObjectMapper.JSON_MAPPER
                        .getNodeFactory().textNode(repeated.toString());
            } else {
                throw new IllegalArgumentException(
                        "Unsupported Contracts builder: " + kind);
            }
            setPointer(root, builder.path("target").asText(), value);
        }
    }

    void applyVariant(ObjectNode root, JsonNode variant) {
        if (variant.has(ContractsFixtureConstants.Field.ACCEPT)) {
            setAllScriptedChannelAcceptance(root, variant.get(ContractsFixtureConstants.Field.ACCEPT).asBoolean());
        }
        if (variant.has(ContractsFixtureConstants.Field.LIST_OPERATION)) {
            installListOperation(
                    root, variant.get(ContractsFixtureConstants.Field.LIST_OPERATION));
        }
        if (variant.has("newEmbeddedSurface")) {
            installEmbeddedSurfaceTransition(
                    root, variant.get("newEmbeddedSurface").asText());
        }
    }

    static void installListOperation(ObjectNode root,
                                             JsonNode operation) {
        int size = exactInt(operation.get(ContractsFixtureConstants.Field.SIZE),
                "variant.listOperation.size");
        String kind = operation.path(ContractsFixtureConstants.Field.OP).asText();

        promoteFixtureScalarToObject(root);
        ArrayNode list = root.putArray(FIXTURE_LIST_FIELD);
        for (int index = 0; index < size; index++) {
            list.add(0);
        }

        ObjectNode contracts = requireObject(
                root.get(ProcessorContractConstants.KEY_CONTRACTS),
                "input.root.contracts");
        ObjectNode handler = firstScriptedHandler(contracts);
        if (handler == null) {
            throw new IllegalArgumentException(
                    "listOperation requires an ordinary selected "
                            + "Scripted Handler");
        }
        ObjectNode result = objectField(handler, ContractsFixtureConstants.Field.RESULT, true);
        ArrayNode patches =
                UncheckedObjectMapper.JSON_MAPPER.createArrayNode();
        result.set(ContractsFixtureConstants.Field.PATCHES, patches);

        if (ContractsFixtureConstants.ListOperation.APPEND.equals(kind)) {
            int delta = exactInt(
                    operation.get(ContractsFixtureConstants.Field.DELTA),
                    "variant.listOperation.delta");
            for (int index = 0; index < delta; index++) {
                ObjectNode patch = patches.addObject();
                patch.put(
                        ContractsFixtureConstants.PatchField.OPERATION,
                        ContractsFixtureConstants.PatchOperation.ADD);
                patch.put(
                        ContractsFixtureConstants.PatchField.PATH,
                        "/" + FIXTURE_LIST_FIELD + "/-");
                patch.put(ContractsFixtureConstants.PatchField.VALUE, 1);
            }
            return;
        }
        if (!ContractsFixtureConstants.ListOperation.REPLACE.equals(kind)) {
            throw new IllegalArgumentException(
                    "Unknown listOperation op: " + kind);
        }
        int index = exactInt(
                operation.get(ContractsFixtureConstants.Field.INDEX),
                "variant.listOperation.index");
        if (index >= size) {
            throw new IllegalArgumentException(
                    "variant.listOperation.index must be less than size");
        }
        ObjectNode patch = patches.addObject();
        patch.put(
                ContractsFixtureConstants.PatchField.OPERATION,
                ContractsFixtureConstants.PatchOperation.REPLACE);
        patch.put(
                ContractsFixtureConstants.PatchField.PATH,
                "/" + FIXTURE_LIST_FIELD + "/" + index);
        patch.put(ContractsFixtureConstants.PatchField.VALUE, 1);
    }

    static boolean snapshotRootForm(String rootForm) {
        return "reference".equals(rootForm)
                || "lazy".equals(rootForm)
                || "eager".equals(rootForm);
    }

    static boolean referenceBackedRootForm(String rootForm) {
        return "reference".equals(rootForm)
                || "lazy".equals(rootForm);
    }

    static void setAllScriptedChannelAcceptance(JsonNode node,
                                                        boolean accepted) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode type = node.path(BlueLanguageConstants.OBJECT_TYPE).path(BlueLanguageConstants.OBJECT_BLUE_ID);
            if (MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL.equals(type.asText(null))) {
                ((ObjectNode) node).put(ContractsFixtureConstants.Field.ACCEPT, accepted);
            }
            node.elements().forEachRemaining(
                    child -> setAllScriptedChannelAcceptance(child, accepted));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(
                    child -> setAllScriptedChannelAcceptance(child, accepted));
        }
    }

    void installEmbeddedSurfaceTransition(ObjectNode root,
                                                  String scenario) {
        ObjectNode contracts = objectAt(
                root,
                ProcessorPointerConstants.RELATIVE_CONTRACTS,
                true);
        ObjectNode embedded = installContract(
                contracts,
                ProcessorContractConstants.KEY_EMBEDDED,
                registryId("ProcessEmbedded"));
        if (!embedded.has(ProcessorContractConstants.KEY_PATHS)) {
            embedded.putArray(ProcessorContractConstants.KEY_PATHS);
        }
        ObjectNode handler = firstScriptedHandler(contracts);
        if (handler == null) {
            throw new IllegalArgumentException(
                    "newEmbeddedSurface requires a selected Scripted Handler");
        }
        ObjectNode result = objectField(handler, ContractsFixtureConstants.Field.RESULT, true);
        ArrayNode patches = arrayField(result, ContractsFixtureConstants.Field.PATCHES, true);
        ObjectNode patch = patches.addObject();
        patch.put(
                ContractsFixtureConstants.PatchField.OPERATION,
                ContractsFixtureConstants.PatchOperation.REPLACE);
        patch.put(
                ContractsFixtureConstants.PatchField.PATH,
                ProcessorPointerConstants.RELATIVE_EMBEDDED_PATHS);
        ArrayNode paths = patch.putArray(
                ContractsFixtureConstants.PatchField.VALUE);
        if ("cycle".equals(scenario)) {
            paths.add("/");
        } else if ("invalid-path".equals(scenario)) {
            paths.add("not-absolute");
        } else if ("unsupported-channel".equals(scenario)) {
            promoteFixtureScalarToObject(root);
            ObjectNode unsupportedScope =
                    objectField(root, "unsupported", true);
            ObjectNode unsupportedContracts =
                    contractsObject(unsupportedScope);
            ObjectNode unsupportedChannel = installContract(
                    unsupportedContracts,
                    "out",
                    MockTypeBlueIds.MOCK_EXTERNAL_CHANNEL);
            unsupportedChannel.put(ContractsFixtureConstants.Field.ORDER, 0);
            unsupportedChannel.put(ContractsFixtureConstants.Field.ACCEPT, true);
            unsupportedChannel.put(
                    "checkpointDomain", "unsupported-v1");
            paths.add("/unsupported");
        } else {
            throw new IllegalArgumentException(
                    "Unknown newEmbeddedSurface transformation: " + scenario);
        }
    }

    static void promoteFixtureScalarToObject(
            ObjectNode root) {
        JsonNode scalar = root.remove(BlueLanguageConstants.OBJECT_VALUE);
        if (scalar == null) {
            return;
        }
        if (root.has(FIXTURE_VALUE_FIELD)) {
            throw new IllegalArgumentException(
                    "Fixture scalar promotion key collision");
        }
        root.set(FIXTURE_VALUE_FIELD, scalar);
        JsonNode contracts = root.get(
                ProcessorContractConstants.KEY_CONTRACTS);
        if (contracts == null || !contracts.isObject()) {
            return;
        }
        for (JsonNode contract : contracts) {
            if (!MockTypeBlueIds.MOCK_HANDLER.equals(
                    contract.path(BlueLanguageConstants.OBJECT_TYPE).path(BlueLanguageConstants.OBJECT_BLUE_ID).asText(null))) {
                continue;
            }
            JsonNode patches =
                    contract.path(ContractsFixtureConstants.Field.RESULT).path(ContractsFixtureConstants.Field.PATCHES);
            if (!patches.isArray()) {
                continue;
            }
            for (JsonNode patch : patches) {
                if (patch.isObject()
                        && ProcessorPointerConstants.RELATIVE_VALUE.equals(
                        patch.path("path").asText(null))) {
                    ((ObjectNode) patch).put(
                            "path",
                            "/" + FIXTURE_VALUE_FIELD);
                }
            }
        }
    }

    static void promoteMixedFixtureScalarToObject(
            ObjectNode root) {
        /*
         * A fixture that adds an authored object edge beside the conventional
         * scalar /value shorthand must become an ordinary object before the
         * strict Language decoder sees it. Reuse the harness's established
         * private field and patch-path rewrite instead of admitting a mixed
         * payload Node.
         */
        if (root.has(BlueLanguageConstants.OBJECT_VALUE)
                && hasAuthoredObjectField(root)) {
            promoteFixtureScalarToObject(root);
        }
    }

}
