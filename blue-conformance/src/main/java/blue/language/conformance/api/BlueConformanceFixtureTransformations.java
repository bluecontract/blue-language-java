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


/** Owns the closed conformance-only preprocessing transformation registry. */
abstract class BlueConformanceFixtureTransformations extends BlueConformanceProviderEnvironment {

    /**
     * Field vocabulary for the conformance-only transformation registry.
     */
    static final class FixtureTransformationField {

        static final String REGISTRY = "registry";
        static final String REGISTRY_KIND = "registryKind";
        static final String SPECIFICATION_VERSION =
                "specificationVersion";
        static final String ENTRIES = "entries";
        static final String KEY = "key";
        static final String FROM = "from";
        static final String TO = "to";
        static final String FIELD = "field";
        static final String SUFFIX = "suffix";

        FixtureTransformationField() {
        }
    }

    /**
     * Exact manifest keys and paths for the three fixture-only types.
     */
    static final class FixtureTransformationDefinition {

        static final String REGISTRY_NAME =
                "blue-language-conformance-preprocessing-transformations";
        static final String REGISTRY_KIND =
                "fixture-only-transformation-type";
        static final String SPECIFICATION_VERSION = "1.0";
        static final String RENAME_ROOT_FIELD_KEY =
                "RenameRootFieldTransformation";
        static final String RENAME_ROOT_FIELD_PATH =
                "RenameRootFieldTransformation.blue";
        static final String SET_ROOT_FIELD_KEY =
                "SetRootFieldTransformation";
        static final String SET_ROOT_FIELD_PATH =
                "SetRootFieldTransformation.blue";
        static final String APPEND_ROOT_TEXT_KEY =
                "AppendRootTextTransformation";
        static final String APPEND_ROOT_TEXT_PATH =
                "AppendRootTextTransformation.blue";
        static final int ENTRY_COUNT = 3;

        FixtureTransformationDefinition() {
        }
    }

    /**
     * Closed transformation registry loaded only by the fixture harness.
     */
    static final class FixtureTransformationRegistry
            implements TransformationProcessorProvider {

        static final FixtureTransformationRegistry INSTANCE =
                new FixtureTransformationRegistry();

        final Map<String, FixtureTransformationFactory>
                factoriesByBlueId;

        FixtureTransformationRegistry() {
            Map<String, FixtureTransformationFactory> factoriesByKey =
                    new LinkedHashMap<>();
            factoriesByKey.put(
                    FixtureTransformationDefinition.RENAME_ROOT_FIELD_KEY,
                    RenameRootFieldProcessor::new);
            factoriesByKey.put(
                    FixtureTransformationDefinition.SET_ROOT_FIELD_KEY,
                    SetRootFieldProcessor::new);
            factoriesByKey.put(
                    FixtureTransformationDefinition.APPEND_ROOT_TEXT_KEY,
                    AppendRootTextProcessor::new);

            Map<String, String> pathsByKey = new LinkedHashMap<>();
            pathsByKey.put(
                    FixtureTransformationDefinition.RENAME_ROOT_FIELD_KEY,
                    FixtureTransformationDefinition.RENAME_ROOT_FIELD_PATH);
            pathsByKey.put(
                    FixtureTransformationDefinition.SET_ROOT_FIELD_KEY,
                    FixtureTransformationDefinition.SET_ROOT_FIELD_PATH);
            pathsByKey.put(
                    FixtureTransformationDefinition.APPEND_ROOT_TEXT_KEY,
                    FixtureTransformationDefinition.APPEND_ROOT_TEXT_PATH);

            JsonNode manifest = readYamlResource(
                    PREPROCESSING_REGISTRY_MANIFEST_RESOURCE);
            assertEquals(
                    FixtureTransformationDefinition.REGISTRY_NAME,
                    requireText(
                            manifest,
                            FixtureTransformationField.REGISTRY));
            assertEquals(
                    FixtureTransformationDefinition.REGISTRY_KIND,
                    requireText(
                            manifest,
                            FixtureTransformationField.REGISTRY_KIND));
            assertEquals(
                    FixtureTransformationDefinition.SPECIFICATION_VERSION,
                    requireText(
                            manifest,
                            FixtureTransformationField.SPECIFICATION_VERSION));

            JsonNode entries = requireArray(
                    manifest, FixtureTransformationField.ENTRIES);
            assertEquals(
                    FixtureTransformationDefinition.ENTRY_COUNT,
                    entries.size());
            Map<String, FixtureTransformationFactory> discovered =
                    new LinkedHashMap<>();
            Set<String> discoveredKeys = new LinkedHashSet<>();
            for (JsonNode entry : entries) {
                String key = requireText(
                        entry, FixtureTransformationField.KEY);
                FixtureTransformationFactory factory =
                        factoriesByKey.get(key);
                if (factory == null || !discoveredKeys.add(key)) {
                    throw new IllegalStateException(
                            "Unknown or duplicate fixture transformation key: "
                                    + key);
                }
                String path = requireText(entry, FixtureField.PATH);
                assertEquals(pathsByKey.get(key), path);
                validateRelativePath(path);
                String declaredBlueId = BlueIds.requirePlainBlueId(
                        requireText(entry, BlueLanguageConstants.OBJECT_BLUE_ID),
                        "preprocessing.registry." + key);
                Node typeDefinition = readNode(readYamlResource(
                        PREPROCESSING_REGISTRY_ROOT + path));
                assertEquals(
                        declaredBlueId,
                        DirectBlueIdCalculator.calculateBlueId(typeDefinition));
                if (discovered.put(declaredBlueId, factory) != null) {
                    throw new IllegalStateException(
                            "Duplicate fixture transformation BlueId: "
                                    + declaredBlueId);
                }
            }
            assertEquals(factoriesByKey.keySet(), discoveredKeys);
            this.factoriesByBlueId = Collections.unmodifiableMap(
                    discovered);
        }

        @Override
        public Optional<TransformationProcessor> getProcessor(
                Node transformation) {
            if (transformation == null
                    || transformation.getType() == null
                    || !transformation.getType().isReferenceOnly()) {
                return Optional.empty();
            }
            return processorFor(
                    transformation.getType().getBlueId(),
                    transformation);
        }

        @Override
        public Optional<TransformationProcessor> processorFor(
                String exactTypeBlueId,
                Node exactTransformationNode) {
            FixtureTransformationFactory factory =
                    factoriesByBlueId.get(exactTypeBlueId);
            if (factory == null) {
                return Optional.empty();
            }
            return Optional.of(factory.create(
                    exactTransformationNode.clone()));
        }
    }

    /** Creates one immutable fixture transformation processor. */
    interface FixtureTransformationFactory {

        TransformationProcessor create(Node configuration);
    }

    /** Moves one existing direct root field to an absent destination. */
    static final class RenameRootFieldProcessor
            implements TransformationProcessor {

        final String from;
        final String to;

        RenameRootFieldProcessor(Node configuration) {
            validateFixtureTransformationConfiguration(
                    configuration,
                    immutableSet(
                            FixtureTransformationField.FROM,
                            FixtureTransformationField.TO));
            this.from = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.FROM),
                    FixtureTransformationField.FROM);
            this.to = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.TO),
                    FixtureTransformationField.TO);
        }

        @Override
        public Node process(Node document) {
            Node result = requireObjectSourceRoot(document);
            if (!hasDirectRootField(result, from)) {
                throw new IllegalArgumentException(
                        "Reserved fixture transformation source field is absent: "
                                + from);
            }
            if (hasDirectRootField(result, to)) {
                throw new IllegalArgumentException(
                        "Reserved fixture transformation destination field already exists: "
                                + to);
            }
            Node value = readDirectRootField(result, from);
            removeDirectRootField(result, from);
            writeDirectRootField(result, to, value);
            return result;
        }
    }

    /** Writes a defensive configuration-node copy to one direct root field. */
    static final class SetRootFieldProcessor
            implements TransformationProcessor {

        final String field;
        final Node value;

        SetRootFieldProcessor(Node configuration) {
            validateFixtureTransformationConfiguration(
                    configuration,
                    immutableSet(
                            FixtureTransformationField.FIELD,
                            BlueLanguageConstants.OBJECT_VALUE));
            this.field = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.FIELD),
                    FixtureTransformationField.FIELD);
            this.value = configuration.getProperties().get(
                    BlueLanguageConstants.OBJECT_VALUE).clone();
        }

        @Override
        public Node process(Node document) {
            Node result = requireObjectSourceRoot(document);
            writeDirectRootField(result, field, value.clone());
            return result;
        }
    }

    /** Appends one configured suffix to an existing direct Text field. */
    static final class AppendRootTextProcessor
            implements TransformationProcessor {

        final String field;
        final String suffix;

        AppendRootTextProcessor(Node configuration) {
            validateFixtureTransformationConfiguration(
                    configuration,
                    immutableSet(
                            FixtureTransformationField.FIELD,
                            FixtureTransformationField.SUFFIX));
            this.field = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.FIELD),
                    FixtureTransformationField.FIELD);
            this.suffix = requireTextScalar(
                    configuration.getProperties().get(
                            FixtureTransformationField.SUFFIX),
                    FixtureTransformationField.SUFFIX);
        }

        @Override
        public Node process(Node document) {
            Node result = requireObjectSourceRoot(document);
            if (!hasDirectRootField(result, field)) {
                throw new IllegalArgumentException(
                        "Reserved fixture transformation Text field is absent: "
                                + field);
            }
            Node current = readDirectRootField(result, field);
            String text = requireTextScalar(current, field);
            current.value(text + suffix);
            writeDirectRootField(result, field, current);
            return result;
        }
    }

    static void validateFixtureTransformationConfiguration(
            Node configuration,
            Set<String> expectedFields) {
        if (configuration == null
                || configuration.getType() == null
                || !configuration.getType().isReferenceOnly()
                || configuration.getName() != null
                || configuration.getDescription() != null
                || configuration.getItemType() != null
                || configuration.getKeyType() != null
                || configuration.getValueType() != null
                || configuration.getRawValue() != null
                || configuration.getItems() != null
                || configuration.getContracts() != null
                || configuration.getBlueId() != null
                || configuration.getSchema() != null
                || configuration.getMergePolicy() != null
                || configuration.getPreviousBlueId() != null
                || configuration.getPosition() != null
                || configuration.getBlue() != null
                || configuration.getProperties() == null
                || !expectedFields.equals(
                        configuration.getProperties().keySet())) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation configuration has an invalid shape.");
        }
    }

    static Node requireObjectSourceRoot(Node document) {
        if (document == null
                || document.getRawValue() != null
                || document.getItems() != null
                || document.getBlueId() != null
                || document.getPreviousBlueId() != null
                || document.getPosition() != null) {
            throw new IllegalArgumentException(
                    "Reserved preprocessing transformation requires an object Source root.");
        }
        return document.clone();
    }

    static String requireTextScalar(
            Node node,
            String role) {
        if (node == null
                || !(node.getRawValue() instanceof String)
                || node.getItems() != null
                || node.getProperties() != null
                || node.getBlueId() != null
                || node.getBlue() != null
                || !hasTextCompatibleType(node.getType())) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation " + role
                            + " must be Text.");
        }
        return (String) node.getRawValue();
    }

    static boolean hasTextCompatibleType(Node type) {
        if (type == null) {
            return true;
        }
        if (type.isReferenceOnly()) {
            return BlueLanguageConstants.TEXT_TYPE_BLUE_ID.equals(
                    type.getBlueId());
        }
        return BlueLanguageConstants.TEXT_TYPE.equals(type.getRawValue())
                && type.getItems() == null
                && type.getProperties() == null
                && type.getBlueId() == null;
    }

    static boolean hasDirectRootField(
            Node root,
            String field) {
        switch (field) {
            case BlueLanguageConstants.OBJECT_NAME:
                return root.getName() != null;
            case BlueLanguageConstants.OBJECT_DESCRIPTION:
                return root.getDescription() != null;
            case BlueLanguageConstants.OBJECT_TYPE:
                return root.getType() != null;
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                return root.getItemType() != null;
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                return root.getKeyType() != null;
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                return root.getValueType() != null;
            case BlueLanguageConstants.OBJECT_VALUE:
                return root.getRawValue() != null;
            case BlueLanguageConstants.OBJECT_ITEMS:
                return root.getItems() != null;
            case BlueLanguageConstants.OBJECT_BLUE_ID:
                return root.getBlueId() != null;
            case BlueLanguageConstants.OBJECT_BLUE:
                return root.getBlue() != null;
            case BlueLanguageConstants.OBJECT_SCHEMA:
                return root.getSchema() != null;
            case BlueLanguageConstants.OBJECT_MERGE_POLICY:
                return root.getMergePolicy() != null;
            case BlueLanguageConstants.OBJECT_CONTRACTS:
                return root.getContracts() != null;
            case BlueLanguageConstants.LIST_CONTROL_PREVIOUS:
                return root.getPreviousBlueId() != null;
            case BlueLanguageConstants.LIST_CONTROL_POS:
                return root.getPosition() != null;
            default:
                return root.getProperties() != null
                        && root.getProperties().containsKey(field);
        }
    }

    static Node readDirectRootField(
            Node root,
            String field) {
        switch (field) {
            case BlueLanguageConstants.OBJECT_NAME:
                return inlineScalar(root.getName());
            case BlueLanguageConstants.OBJECT_DESCRIPTION:
                return inlineScalar(root.getDescription());
            case BlueLanguageConstants.OBJECT_TYPE:
                return cloneNode(root.getType());
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                return cloneNode(root.getItemType());
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                return cloneNode(root.getKeyType());
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                return cloneNode(root.getValueType());
            case BlueLanguageConstants.OBJECT_VALUE:
                return inlineScalar(root.getRawValue());
            case BlueLanguageConstants.OBJECT_ITEMS:
                return new Node().items(cloneNodes(root.getItems()));
            case BlueLanguageConstants.OBJECT_BLUE_ID:
                return inlineScalar(root.getBlueId());
            case BlueLanguageConstants.OBJECT_BLUE:
                return cloneNode(root.getBlue());
            case BlueLanguageConstants.OBJECT_SCHEMA:
                return new Node().schema(root.getSchema().clone());
            case BlueLanguageConstants.OBJECT_MERGE_POLICY:
                return inlineScalar(root.getMergePolicy());
            case BlueLanguageConstants.OBJECT_CONTRACTS:
                return cloneNode(root.getContracts());
            case BlueLanguageConstants.LIST_CONTROL_PREVIOUS:
                return new Node().blueId(root.getPreviousBlueId());
            case BlueLanguageConstants.LIST_CONTROL_POS:
                return inlineScalar(BigInteger.valueOf(
                        root.getPosition()));
            default:
                return cloneNode(root.getProperties().get(field));
        }
    }

    static void removeDirectRootField(
            Node root,
            String field) {
        switch (field) {
            case BlueLanguageConstants.OBJECT_NAME:
                root.name(null);
                return;
            case BlueLanguageConstants.OBJECT_DESCRIPTION:
                root.description(null);
                return;
            case BlueLanguageConstants.OBJECT_TYPE:
                root.type((Node) null);
                return;
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                root.itemType((Node) null);
                return;
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                root.keyType((Node) null);
                return;
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                root.valueType((Node) null);
                return;
            case BlueLanguageConstants.OBJECT_VALUE:
                root.value((Object) null);
                return;
            case BlueLanguageConstants.OBJECT_ITEMS:
                root.items((List<Node>) null);
                return;
            case BlueLanguageConstants.OBJECT_BLUE_ID:
                root.blueId(null);
                return;
            case BlueLanguageConstants.OBJECT_BLUE:
                root.blue(null);
                return;
            case BlueLanguageConstants.OBJECT_SCHEMA:
                root.schema(null);
                return;
            case BlueLanguageConstants.OBJECT_MERGE_POLICY:
                root.mergePolicy(null);
                return;
            case BlueLanguageConstants.OBJECT_CONTRACTS:
                root.contracts(null);
                return;
            case BlueLanguageConstants.LIST_CONTROL_PREVIOUS:
                root.previousBlueId(null);
                return;
            case BlueLanguageConstants.LIST_CONTROL_POS:
                root.position(null);
                return;
            default:
                Map<String, Node> properties = new LinkedHashMap<>(
                        root.getProperties());
                properties.remove(field);
                root.properties(properties.isEmpty()
                        ? null : properties);
        }
    }

    static void writeDirectRootField(
            Node root,
            String field,
            Node value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation field value is missing: "
                            + field);
        }
        switch (field) {
            case BlueLanguageConstants.OBJECT_NAME:
                root.name(requireTextScalar(value, field));
                return;
            case BlueLanguageConstants.OBJECT_DESCRIPTION:
                root.description(requireTextScalar(value, field));
                return;
            case BlueLanguageConstants.OBJECT_TYPE:
                root.type(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_ITEM_TYPE:
                root.itemType(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_KEY_TYPE:
                root.keyType(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_VALUE_TYPE:
                root.valueType(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_VALUE:
                requireScalarPayload(value, field);
                root.value(value.getRawValue());
                return;
            case BlueLanguageConstants.OBJECT_ITEMS:
                if (value.getItems() == null) {
                    throw new IllegalArgumentException(
                            "Reserved fixture transformation items value must be a list.");
                }
                root.items(cloneNodes(value.getItems()));
                return;
            case BlueLanguageConstants.OBJECT_BLUE_ID:
                root.blueId(requireTextScalar(value, field));
                return;
            case BlueLanguageConstants.OBJECT_BLUE:
                root.blue(value.clone());
                return;
            case BlueLanguageConstants.OBJECT_SCHEMA:
                if (value.getSchema() == null) {
                    throw new IllegalArgumentException(
                            "Reserved fixture transformation schema value must be a schema.");
                }
                root.schema(value.getSchema().clone());
                return;
            case BlueLanguageConstants.OBJECT_MERGE_POLICY:
                root.mergePolicy(requireTextScalar(value, field));
                return;
            case BlueLanguageConstants.OBJECT_CONTRACTS:
                root.contracts(value.clone());
                return;
            case BlueLanguageConstants.LIST_CONTROL_PREVIOUS:
                if (!value.isReferenceOnly()) {
                    throw new IllegalArgumentException(
                            "Reserved fixture transformation $previous value must be a pure reference.");
                }
                root.previousBlueId(value.getBlueId());
                return;
            case BlueLanguageConstants.LIST_CONTROL_POS:
                root.position(requireNonNegativeInteger(value, field));
                return;
            default:
                root.properties(field, value.clone());
        }
    }

    static void requireScalarPayload(
            Node value,
            String field) {
        if (value.getRawValue() == null
                || value.getItems() != null
                || value.getProperties() != null
                || value.getBlueId() != null) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation " + field
                            + " value must be a scalar.");
        }
    }

    static int requireNonNegativeInteger(
            Node value,
            String field) {
        requireScalarPayload(value, field);
        if (!(value.getRawValue() instanceof BigInteger)) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation " + field
                            + " value must be an integer.");
        }
        BigInteger integer = (BigInteger) value.getRawValue();
        if (integer.signum() < 0
                || integer.compareTo(
                        BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(
                    "Reserved fixture transformation " + field
                            + " value is outside the supported range.");
        }
        return integer.intValue();
    }

    static Node inlineScalar(Object value) {
        return new Node().value(value).inlineValue(true);
    }

    static Node cloneNode(Node node) {
        return node == null ? null : node.clone();
    }

    static List<Node> cloneNodes(List<Node> nodes) {
        List<Node> result = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            result.add(node.clone());
        }
        return result;
    }

}
