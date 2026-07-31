package blue.language.preprocess;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.NodeProviderOutcome;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.ProviderUnavailableException;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIds;
import blue.language.utils.Properties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static blue.language.utils.SchemaPropertyConstants.KEY_ENUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_FIELDS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_ITEMS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAX_LENGTH;
import static blue.language.utils.SchemaPropertyConstants.KEY_MAXIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_FIELDS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_ITEMS;
import static blue.language.utils.SchemaPropertyConstants.KEY_MIN_LENGTH;
import static blue.language.utils.SchemaPropertyConstants.KEY_MINIMUM;
import static blue.language.utils.SchemaPropertyConstants.KEY_MULTIPLE_OF;
import static blue.language.utils.SchemaPropertyConstants.KEY_REQUIRED;
import static blue.language.utils.SchemaPropertyConstants.KEY_UNIQUE_ITEMS;

/**
 * Resolves, verifies, validates, and freezes the complete root
 * {@code blue} directive before transformation execution.
 */
public final class PreprocessingDirectiveResolver {

    private final TransformationProcessorProvider processorProvider;
    private final NodeProvider verifiedProvider;
    private final Map<String, String> directiveAliases;
    private final Map<String, String> environmentImports;

    /**
     * Creates a resolver for one declared preprocessing environment.
     *
     * @param processorProvider exact transformation registry
     * @param verifiedProvider identity-verifying provider boundary
     * @param directiveAliases string aliases mapped to exact directive BlueIds
     * @param environmentImports explicit host alias mappings
     */
    public PreprocessingDirectiveResolver(
            TransformationProcessorProvider processorProvider,
            NodeProvider verifiedProvider,
            Map<String, String> directiveAliases,
            Map<String, String> environmentImports) {
        this.processorProvider = Objects.requireNonNull(
                processorProvider, "processorProvider");
        this.verifiedProvider = Objects.requireNonNull(
                verifiedProvider, "verifiedProvider");
        this.directiveAliases = exactMappings(
                directiveAliases, "directive alias");
        this.environmentImports = exactMappings(
                environmentImports, "environment import");
    }

    /**
     * Establishes the complete immutable plan without mutating source input.
     *
     * @param source parsed Source Document
     * @return frozen preprocessing plan
     */
    public PreprocessingPlan resolve(Node source) {
        Objects.requireNonNull(source, "source");
        PreprocessingLimits.requireGraphWithinBounds(
                source, "Source Document");
        rejectNestedBlue(source);

        List<String> dependencies = new ArrayList<>();
        Node directive = source.getBlue();
        String directiveBlueId = null;
        if (directive == null) {
            directive = new Node();
        } else if (directive.getValue() instanceof String) {
            String alias = (String) directive.getValue();
            directiveBlueId = directiveAliases.get(alias);
            if (directiveBlueId == null) {
                throw new IllegalArgumentException(
                        "Reserved \"blue\" directive alias is unbound: "
                                + alias);
            }
            addDependency(dependencies, directiveBlueId);
            directive = fetchExactNode(
                    directiveBlueId, "blue directive");
        } else if (directive.isReferenceOnly()) {
            directiveBlueId = BlueIds.requirePlainBlueId(
                    directive.getBlueId(), "blue.blueId");
            addDependency(dependencies, directiveBlueId);
            directive = fetchExactNode(
                    directiveBlueId, "blue directive");
        } else {
            directive = directive.clone();
        }

        validateDirective(directive);
        Map<String, String> imports = effectiveImports(
                directive, dependencies);
        List<TransformationSnapshot> transformations =
                transformations(directive, dependencies);
        return new PreprocessingPlan(
                directiveBlueId,
                imports,
                transformations,
                new ArrayList<>(new LinkedHashSet<>(dependencies)));
    }

    private Map<String, String> effectiveImports(
            Node directive,
            List<String> dependencies) {
        Map<String, String> result = new LinkedHashMap<>();
        mergeImports(result,
                BlueCoreTypeRegistry.INSTANCE.blueIdsByName(),
                "canonical core aliases");
        mergeImports(result, environmentImports,
                "preprocessing environment aliases");

        Node imports = property(
                directive, Properties.BLUE_DIRECTIVE_IMPORTS);
        if (imports == null) {
            return result;
        }
        if (imports.isReferenceOnly()) {
            String blueId = BlueIds.requirePlainBlueId(
                    imports.getBlueId(), "blue.imports.blueId");
            addDependency(dependencies, blueId);
            imports = fetchExactNode(blueId, "blue.imports");
        }
        validateImportsObject(imports);
        if (imports.getProperties() == null) {
            return result;
        }
        Map<String, String> declared = new LinkedHashMap<>();
        for (Map.Entry<String, Node> entry
                : imports.getProperties().entrySet()) {
            if (entry.getValue() == null
                    || !entry.getValue().isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Reserved \"blue.imports."
                                + entry.getKey()
                                + "\" must be a pure reference.");
            }
            declared.put(entry.getKey(),
                    BlueIds.requirePlainBlueId(
                            entry.getValue().getBlueId(),
                            "blue.imports." + entry.getKey()));
        }
        mergeImports(result, declared, "blue.imports");
        return result;
    }

    private List<TransformationSnapshot> transformations(
            Node directive,
            List<String> dependencies) {
        Node transformations = property(
                directive, Properties.BLUE_DIRECTIVE_TRANSFORMATIONS);
        if (transformations == null) {
            return Collections.emptyList();
        }
        if (transformations.isReferenceOnly()) {
            String blueId = BlueIds.requirePlainBlueId(
                    transformations.getBlueId(),
                    "blue.transformations.blueId");
            addDependency(dependencies, blueId);
            transformations = fetchExactNode(
                    blueId, "blue.transformations");
        }
        validateTransformationList(transformations);

        List<TransformationSnapshot> result = new ArrayList<>();
        List<Node> items = transformations.getItems();
        if (items == null) {
            return result;
        }
        PreprocessingLimits.requireTransformationCount(items.size());
        for (int index = 0; index < items.size(); index++) {
            Node transformation = items.get(index);
            String transformationBlueId = null;
            if (transformation != null
                    && transformation.isReferenceOnly()) {
                transformationBlueId = BlueIds.requirePlainBlueId(
                        transformation.getBlueId(),
                        "blue.transformations."
                                + index + ".blueId");
                addDependency(dependencies, transformationBlueId);
                transformation = fetchExactNode(
                        transformationBlueId,
                        "blue transformation " + index);
            } else if (transformation != null) {
                transformation = transformation.clone();
            }
            if (transformation == null) {
                throw new IllegalArgumentException(
                        "Reserved \"blue.transformations\" cannot contain null.");
            }
            rejectAnyBlue(transformation,
                    "blue.transformations/" + index);
            Node type = transformation.getType();
            if (type == null || !type.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Reserved preprocessing transformation type must identify one exact type BlueId at blue.transformations/"
                                + index + ".");
            }
            String typeBlueId = BlueIds.requirePlainBlueId(
                    type.getBlueId(),
                    "blue.transformations."
                            + index + ".type.blueId");
            Optional<TransformationProcessor> processor =
                    processorProvider.processorFor(
                            typeBlueId, transformation.clone());
            if (!processor.isPresent()) {
                throw new IllegalArgumentException(
                        "Unsupported preprocessing transform type: "
                                + typeBlueId);
            }
            if (transformationBlueId == null) {
                transformationBlueId =
                        BlueIdCalculator.calculateBlueId(transformation);
            }
            result.add(new TransformationSnapshot(
                    transformationBlueId,
                    typeBlueId,
                    transformation,
                    processor.get()));
        }
        return result;
    }

    private Node fetchExactNode(String blueId, String role) {
        NodeProviderResult result =
                verifiedProvider.fetchResultByBlueId(blueId);
        if (result.outcome() == NodeProviderOutcome.UNAVAILABLE) {
            throw new ProviderUnavailableException(
                    result.diagnostic().orElse(
                            "Provider unavailable for requested BlueId "
                                    + blueId));
        }
        if (result.outcome() == NodeProviderOutcome.INVALID_EVIDENCE) {
            throw new IllegalArgumentException(
                    result.diagnostic().orElse(
                            "Provider returned content that does not match requested BlueId "
                                    + blueId));
        }
        if (result.outcome() != NodeProviderOutcome.FOUND) {
            throw new IllegalArgumentException(
                    "Provider returned no content for requested BlueId "
                            + blueId + " (" + role + ").");
        }
        List<Node> nodes = result.nodes();
        if (nodes.size() != 1) {
            throw new IllegalArgumentException(
                    "Provider returned " + nodes.size()
                            + " nodes for requested BlueId " + blueId
                            + " (" + role + ").");
        }
        Node node = nodes.get(0).clone();
        if (blueId.equals(node.getBlueId())) {
            node.blueId(null);
        }
        PreprocessingLimits.requireGraphWithinBounds(
                node, role);
        return node;
    }

    private void addDependency(
            List<String> dependencies,
            String blueId) {
        dependencies.add(blueId);
        PreprocessingLimits.requireReferencedResourceCount(
                new LinkedHashSet<>(dependencies).size());
    }

    private void validateDirective(Node directive) {
        rejectAnyBlue(directive, Properties.OBJECT_BLUE);
        if (directive.getBlueId() != null
                || directive.getValue() != null
                || directive.getItems() != null
                || directive.getItemType() != null
                || directive.getKeyType() != null
                || directive.getValueType() != null
                || directive.getSchema() != null
                || directive.getContracts() != null
                || directive.getMergePolicy() != null
                || directive.getPreviousBlueId() != null
                || directive.getPosition() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue\" directive has an invalid portable shape.");
        }
        if (directive.getType() != null
                && !directive.getType().isReferenceOnly()) {
            throw new IllegalArgumentException(
                    "Reserved \"blue.type\" metadata must be an exact pure reference.");
        }
        if (directive.getProperties() != null) {
            for (String key : directive.getProperties().keySet()) {
                if (!Properties.BLUE_DIRECTIVE_IMPORTS.equals(key)
                        && !Properties.BLUE_DIRECTIVE_TRANSFORMATIONS
                        .equals(key)) {
                    throw new IllegalArgumentException(
                            "Reserved \"blue\" directive field is unsupported: "
                                    + key);
                }
            }
        }
    }

    private void validateImportsObject(Node imports) {
        if (imports.getBlueId() != null
                || imports.getValue() != null
                || imports.getItems() != null
                || imports.getName() != null
                || imports.getDescription() != null
                || imports.getType() != null
                || imports.getItemType() != null
                || imports.getKeyType() != null
                || imports.getValueType() != null
                || imports.getSchema() != null
                || imports.getContracts() != null
                || imports.getMergePolicy() != null
                || imports.getPreviousBlueId() != null
                || imports.getPosition() != null
                || imports.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue.imports\" must be an object mapping aliases to pure references.");
        }
    }

    private void validateTransformationList(Node transformations) {
        if (transformations.getBlueId() != null
                || transformations.getValue() != null
                || transformations.getProperties() != null
                || transformations.getName() != null
                || transformations.getDescription() != null
                || transformations.getType() != null
                || transformations.getItemType() != null
                || transformations.getKeyType() != null
                || transformations.getValueType() != null
                || transformations.getSchema() != null
                || transformations.getContracts() != null
                || transformations.getMergePolicy() != null
                || transformations.getPreviousBlueId() != null
                || transformations.getPosition() != null
                || transformations.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue.transformations\" must be a list.");
        }
    }

    private void mergeImports(
            Map<String, String> destination,
            Map<String, String> additions,
            String source) {
        for (Map.Entry<String, String> entry : additions.entrySet()) {
            String existing = destination.get(entry.getKey());
            if (existing != null && !existing.equals(entry.getValue())) {
                throw new IllegalArgumentException(
                        "Reserved preprocessing alias \""
                                + entry.getKey()
                                + "\" cannot be rebound by " + source + ".");
            }
            destination.put(entry.getKey(), entry.getValue());
        }
    }

    private Map<String, String> exactMappings(
            Map<String, String> mappings,
            String role) {
        Map<String, String> result = new LinkedHashMap<>();
        if (mappings == null) {
            return Collections.unmodifiableMap(result);
        }
        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) {
                throw new IllegalArgumentException(
                        role + " name must not be empty.");
            }
            result.put(entry.getKey(),
                    BlueIds.requirePlainBlueId(
                            entry.getValue(), role + "." + entry.getKey()));
        }
        return Collections.unmodifiableMap(result);
    }

    private Node property(Node node, String key) {
        return node.getProperties() == null
                ? null : node.getProperties().get(key);
    }

    private void rejectNestedBlue(Node source) {
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        visited.add(source);
        rejectChildBlue(source.getType(), "/type", visited);
        rejectChildBlue(source.getItemType(), "/itemType", visited);
        rejectChildBlue(source.getKeyType(), "/keyType", visited);
        rejectChildBlue(source.getValueType(), "/valueType", visited);
        rejectChildBlue(source.getContracts(), "/contracts", visited);
        rejectSchemaBlue(source.getSchema(), "/schema", visited);
        if (source.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : source.getProperties().entrySet()) {
                if (Properties.OBJECT_BLUE.equals(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Reserved \"blue\" is valid only on the root Source Document. Path: /blue");
                }
                rejectChildBlue(entry.getValue(),
                        "/" + entry.getKey(), visited);
            }
        }
        if (source.getItems() != null) {
            for (int index = 0; index < source.getItems().size(); index++) {
                rejectChildBlue(source.getItems().get(index),
                        "/" + index, visited);
            }
        }
    }

    private void rejectChildBlue(
            Node node,
            String path,
            Set<Node> visited) {
        if (node == null || !visited.add(node)) {
            return;
        }
        if (node.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue\" is valid only on the root Source Document. Path: "
                            + path + "/blue");
        }
        rejectChildBlue(node.getType(), path + "/type", visited);
        rejectChildBlue(node.getItemType(), path + "/itemType", visited);
        rejectChildBlue(node.getKeyType(), path + "/keyType", visited);
        rejectChildBlue(node.getValueType(), path + "/valueType", visited);
        rejectChildBlue(node.getContracts(), path + "/contracts", visited);
        rejectSchemaBlue(node.getSchema(), path + "/schema", visited);
        if (node.getProperties() != null) {
            for (Map.Entry<String, Node> entry
                    : node.getProperties().entrySet()) {
                if (Properties.OBJECT_BLUE.equals(entry.getKey())) {
                    throw new IllegalArgumentException(
                            "Reserved \"blue\" is valid only on the root Source Document. Path: "
                                    + path + "/blue");
                }
                rejectChildBlue(entry.getValue(),
                        path + "/" + entry.getKey(), visited);
            }
        }
        if (node.getItems() != null) {
            for (int index = 0; index < node.getItems().size(); index++) {
                rejectChildBlue(node.getItems().get(index),
                        path + "/" + index, visited);
            }
        }
    }

    private void rejectSchemaBlue(
            Schema schema,
            String path,
            Set<Node> visited) {
        if (schema == null) {
            return;
        }
        rejectChildBlue(schema.getRequired(), path + "/" + KEY_REQUIRED, visited);
        rejectChildBlue(schema.getMinLength(), path + "/" + KEY_MIN_LENGTH, visited);
        rejectChildBlue(schema.getMaxLength(), path + "/" + KEY_MAX_LENGTH, visited);
        rejectChildBlue(schema.getMinimum(), path + "/" + KEY_MINIMUM, visited);
        rejectChildBlue(schema.getMaximum(), path + "/" + KEY_MAXIMUM, visited);
        rejectChildBlue(schema.getExclusiveMinimum(),
                path + "/" + KEY_EXCLUSIVE_MINIMUM, visited);
        rejectChildBlue(schema.getExclusiveMaximum(),
                path + "/" + KEY_EXCLUSIVE_MAXIMUM, visited);
        rejectChildBlue(schema.getMultipleOf(), path + "/" + KEY_MULTIPLE_OF, visited);
        rejectChildBlue(schema.getMinItems(), path + "/" + KEY_MIN_ITEMS, visited);
        rejectChildBlue(schema.getMaxItems(), path + "/" + KEY_MAX_ITEMS, visited);
        rejectChildBlue(schema.getUniqueItems(), path + "/" + KEY_UNIQUE_ITEMS, visited);
        rejectChildBlue(schema.getMinFields(), path + "/" + KEY_MIN_FIELDS, visited);
        rejectChildBlue(schema.getMaxFields(), path + "/" + KEY_MAX_FIELDS, visited);
        if (schema.getEnum() != null) {
            for (int index = 0; index < schema.getEnum().size(); index++) {
                rejectChildBlue(schema.getEnum().get(index),
                        path + "/" + KEY_ENUM + "/" + index, visited);
            }
        }
    }

    private void rejectAnyBlue(Node node, String path) {
        if (node == null) {
            return;
        }
        if (node.getBlue() != null) {
            throw new IllegalArgumentException(
                    "Reserved \"blue\" directive is not allowed inside "
                            + path + ".");
        }
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        rejectChildBlue(node, path, visited);
    }
}
