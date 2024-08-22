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
        return convertWithType(node, targetClass);
    }

    @SuppressWarnings("unchecked")
    public <T> T convertWithType(Node node, Type targetType) {
        Converter<?> converter = converterFactory.getConverter(node, targetType);
        return (T) converter.convert(node, targetType);
    }
}