package blue.language.mapping;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.BlueDescription;
import blue.language.model.BlueId;
import blue.language.model.BlueName;
import blue.language.model.Node;

import java.lang.reflect.*;
import java.util.*;

/**
 * Reflectively materializes a Blue object node as a Java object.
 *
 * <p>The converter honors Blue metadata annotations, inherited fields,
 * Jackson property names, resolved Blue type mappings, and generic field
 * types. Static and compiler-generated fields are class metadata rather than
 * instance payload and are deliberately ignored. Target classes use their
 * mapper-owned factory when registered, otherwise an accessible no-argument
 * constructor is required.</p>
 */
public class ComplexObjectConverter implements Converter<Object> {
    private final ConverterFactory converterFactory;
    private final TypeClassResolver typeClassResolver;
    private final ObjectFactoryRegistry objectFactories;

    /**
     * Creates a reflective object converter.
     *
     * @param converterFactory factory for nested field converters
     * @param typeClassResolver resolver for Blue-declared Java types
     */
    public ComplexObjectConverter(
            ConverterFactory converterFactory,
            TypeClassResolver typeClassResolver) {
        this(
                converterFactory,
                typeClassResolver,
                ObjectFactoryRegistry.defaults());
    }

    /**
     * Creates a reflective converter with explicit object factories.
     *
     * @param converterFactory factory for nested field converters
     * @param typeClassResolver resolver for Blue-declared Java types
     * @param objectFactories immutable object factory registry
     */
    public ComplexObjectConverter(
            ConverterFactory converterFactory,
            TypeClassResolver typeClassResolver,
            ObjectFactoryRegistry objectFactories) {
        this.converterFactory = converterFactory;
        this.typeClassResolver = typeClassResolver;
        this.objectFactories = objectFactories;
    }

    @Override
    public Object convert(Node node, Type targetType) {
        return convert(node, targetType, false);
    }

    @Override
    public Object convert(Node node, Type targetType, boolean prioritizeTargetType) {
        if (node == null) {
            return null;
        }

        MappingPayload.Kind payloadKind = MappingPayload.requireCompatible(
                node,
                targetType,
                "mapping root");

        Class<?> resolvedClass = converterFactory.resolveClass(
                node, typeClassResolver);
        Class<?> classToInstantiate;

        if (prioritizeTargetType) {
            classToInstantiate = MappingPayload.rawType(targetType);
        } else {
            classToInstantiate = resolvedClass != null
                    ? resolvedClass
                    : MappingPayload.rawType(targetType);
        }

        if (classToInstantiate == Object.class && resolvedClass == null) {
            switch (payloadKind) {
                case SCALAR:
                    return ValueConverter.convertValue(node, Object.class);
                case LIST:
                    return converterFactory.getConverter(node, List.class)
                            .convert(node, List.class);
                case OBJECT:
                    return converterFactory.convertMap(node, Map.class);
                case NONE:
                    throw MappingPayload.failure(
                            "mapping root",
                            "metadata-only node has no resolvable dynamic "
                                    + "Java target");
                default:
                    throw new IllegalStateException(
                            "Unhandled payload kind: " + payloadKind);
            }
        }

        if (classToInstantiate.isPrimitive() || ValueConverter.isSupportedType(classToInstantiate)) {
            return ValueConverter.convertValue(node, classToInstantiate);
        }

        if (resolvedClass != null
                && MappingPayload.rawType(targetType)
                        .isAssignableFrom(resolvedClass)) {
            classToInstantiate = resolvedClass;
        }

        try {
            Object instance = objectFactories.create(classToInstantiate);
            convertFields(node, classToInstantiate, instance);
            return instance;
        } catch (RuntimeException e) {
            throw MappingPayload.nestedFailure(
                    "mapping target " + classToInstantiate.getName(),
                    e);
        } catch (IllegalAccessException e) {
            throw new IllegalArgumentException(
                    "mapping target " + classToInstantiate.getName()
                            + ": cannot access mapped field",
                    e);
        }
    }

    private void convertFields(Node node, Class<?> clazz, Object instance) throws IllegalAccessException {
        if (clazz.getSuperclass() != null && clazz.getSuperclass() != Object.class) {
            convertFields(node, clazz.getSuperclass(), instance);
        }

        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || field.isSynthetic()) {
                continue;
            }
            field.setAccessible(true);
            String fieldName = field.getName();
            String propertyName = JacksonPropertyNames.propertyName(field);
            Object fieldValue = null;

            try {
                if (field.isAnnotationPresent(BlueId.class)) {
                    fieldValue = handleBlueIdAnnotation(node, propertyName);
                } else if (field.isAnnotationPresent(BlueName.class)) {
                    fieldValue = handleBlueNameAnnotation(node, clazz, field);
                } else if (field.isAnnotationPresent(BlueDescription.class)) {
                    fieldValue = handleBlueDescriptionAnnotation(node, clazz, field);
                } else {
                    Node fieldNode = propertyNode(node, propertyName);

                    if (fieldNode != null) {
                        Type fieldType = field.getGenericType();
                        Class<?> resolvedFieldClass =
                                converterFactory.resolveClass(
                                        fieldNode,
                                        typeClassResolver);

                        if (resolvedFieldClass != null && field.getType().isAssignableFrom(resolvedFieldClass)) {
                            Converter<?> fieldConverter = converterFactory.getConverter(fieldNode, resolvedFieldClass);
                            fieldValue = fieldConverter.convert(fieldNode, resolvedFieldClass);
                        } else if (Map.class.isAssignableFrom(field.getType())) {
                            fieldValue = converterFactory.convertMap(fieldNode, fieldType);
                        } else {
                            Converter<?> fieldConverter = converterFactory.getConverter(fieldNode, field.getType());
                            fieldValue = fieldConverter.convert(fieldNode, fieldType);
                        }
                    } else if (BlueLanguageConstants.OBJECT_NAME.equals(propertyName)) {
                        fieldValue = node.getName();
                    } else if (BlueLanguageConstants.OBJECT_DESCRIPTION.equals(
                            propertyName)) {
                        fieldValue = node.getDescription();
                    }
                }

                if (fieldValue == null && field.getType().isPrimitive()) {
                    fieldValue = ValueConverter.getDefaultPrimitiveValue(field.getType());
                }

                field.set(instance, fieldValue);
            } catch (RuntimeException e) {
                throw MappingPayload.nestedFailure(
                        "field '" + fieldName + "' ("
                                + field.getGenericType().getTypeName() + ")",
                        e);
            }
        }
    }

    private String handleBlueIdAnnotation(Node node, String propertyName) {
        Node targetNode = propertyNode(node, propertyName);
        if (targetNode == null) {
            return null;
        }
        return converterFactory.requireCanonicalContentBlueId(targetNode);
    }

    private String handleBlueNameAnnotation(Node node, Class<?> clazz, Field field) {
        BlueName annotation = field.getAnnotation(BlueName.class);
        String propertyName = JacksonPropertyNames.resolveTargetPropertyName(clazz, annotation.value());
        Node targetNode = propertyNode(node, propertyName);
        return targetNode != null ? targetNode.getName() : null;
    }

    private String handleBlueDescriptionAnnotation(Node node, Class<?> clazz, Field field) {
        BlueDescription annotation = field.getAnnotation(BlueDescription.class);
        String propertyName = JacksonPropertyNames.resolveTargetPropertyName(clazz, annotation.value());
        Node targetNode = propertyNode(node, propertyName);
        return targetNode != null ? targetNode.getDescription() : null;
    }

    private Node propertyNode(Node node, String propertyName) {
        if (BlueLanguageConstants.OBJECT_CONTRACTS.equals(propertyName)) {
            return node.getContracts();
        }
        return node.getProperties() != null ? node.getProperties().get(propertyName) : null;
    }

}
