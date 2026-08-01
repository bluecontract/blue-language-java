package blue.language.mapping;

import blue.language.model.Node;

import java.lang.reflect.Type;

/**
 * Public entry point for recursively materializing Blue nodes as Java object
 * graphs.
 */
public class NodeToObjectConverter {
    private final ConverterFactory converterFactory;

    /**
     * Creates a mapping facade.
     *
     * @param typeClassResolver resolver for Blue-declared Java types
     */
    public NodeToObjectConverter(TypeClassResolver typeClassResolver) {
        this(typeClassResolver, ObjectFactoryRegistry.defaults());
    }

    /**
     * Creates a mapping facade with an immutable object factory registry.
     *
     * @param typeClassResolver resolver for Blue-declared Java types
     * @param objectFactories immutable object factory registry
     */
    public NodeToObjectConverter(
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories) {
        this.converterFactory = new ConverterFactory(
                typeClassResolver,
                objectFactories);
    }

    /**
     * Converts while prioritizing the caller's target class over a resolved
     * Blue type mapping.
     *
     * @param node source Blue node
     * @param targetClass requested Java class
     * @param <T> requested Java value type
     * @return converted value
     */
    public <T> T convert(Node node, Class<T> targetClass) {
        return convertWithType(node, targetClass, true);
    }

    /**
     * Converts to an arbitrary reflective type.
     *
     * @param node source Blue node
     * @param targetType requested reflective Java type
     * @param prioritizeTargetType whether the requested type takes precedence
     *                             over resolved Blue metadata
     * @param <T> converted Java value type
     * @return converted value
     */
    @SuppressWarnings("unchecked")
    public <T> T convertWithType(Node node, Type targetType, boolean prioritizeTargetType) {
        Converter<?> converter = converterFactory.getConverter(node, targetType, prioritizeTargetType);
        return (T) converter.convert(node, targetType, prioritizeTargetType);
    }
}
