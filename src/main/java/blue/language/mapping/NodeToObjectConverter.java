package blue.language.mapping;

import blue.language.model.Node;
import blue.language.utils.TypeClassResolver;

import java.lang.reflect.Type;

public class NodeToObjectConverter {
    private final ConverterFactory converterFactory;

    public NodeToObjectConverter(TypeClassResolver typeClassResolver) {
        this.converterFactory = new ConverterFactory(typeClassResolver);
    }

    public <T> T convert(Node node, Class<T> targetClass) {
        return convertWithType(node, targetClass, true);
    }

    @SuppressWarnings("unchecked")
    public <T> T convertWithType(Node node, Type targetType, boolean prioritizeTargetType) {
        Converter<?> converter = converterFactory.getConverter(node, targetType, prioritizeTargetType);
        return (T) converter.convert(node, targetType, prioritizeTargetType);
    }
}