package blue.language.preprocess;

import blue.language.model.Node;
import blue.language.registry.BlueCoreTypeRegistry;
import blue.language.utils.BlueIds;
import blue.language.utils.Properties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Freezes canonical, environment, and authored preprocessing imports. */
public final class ImportMapBuilder {

    private final DirectiveResolver resolver;
    private final DirectiveValidator validator;
    private final Map<String, String> environmentImports;

    /** Creates a builder for one immutable preprocessing environment. */
    public ImportMapBuilder(
            DirectiveResolver resolver,
            DirectiveValidator validator,
            Map<String, String> environmentImports) {
        this.resolver = resolver;
        this.validator = validator;
        this.environmentImports = DirectiveResolver.exactMappings(
                environmentImports, "environment import");
    }

    /** Builds the complete import map before transformations can execute. */
    Map<String, String> build(
            Node directive, List<String> dependencies) {
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
            resolver.addDependency(dependencies, blueId);
            imports = resolver.fetchExactNode(blueId, "blue.imports");
        }
        validator.validateImportsObject(imports);
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
            declared.put(entry.getKey(), BlueIds.requirePlainBlueId(
                    entry.getValue().getBlueId(),
                    "blue.imports." + entry.getKey()));
        }
        mergeImports(result, declared, "blue.imports");
        return result;
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

    private Node property(Node node, String key) {
        return node.getProperties() == null
                ? null : node.getProperties().get(key);
    }
}
