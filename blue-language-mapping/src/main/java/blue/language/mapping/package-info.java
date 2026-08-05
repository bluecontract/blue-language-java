/**
 * Maps between Blue {@link blue.language.model.Node} graphs and Java objects.
 *
 * <p><strong>Contents.</strong> This package contains the mapping facade,
 * exact BlueId-to-class registration, object factories, and focused converter
 * extension points. Language identity calculation, preprocessing, reference
 * resolution, and content retrieval do not belong here.</p>
 *
 * <p><strong>Entry points.</strong> Applications should configure an immutable
 * {@link blue.language.mapping.BlueMapper} through its builder. Lower-level
 * integrations can use {@link blue.language.mapping.TypeClassResolver},
 * {@link blue.language.mapping.ObjectFactoryRegistry}, and
 * {@link blue.language.mapping.Converter} when the facade is insufficient.</p>
 *
 * <p><strong>Lifecycle.</strong> A built {@code BlueMapper} snapshots its
 * configuration and can be shared. Builders and the legacy mutable registries
 * are configuration-scoped and should not be modified concurrently; publish
 * them only after registration is complete.</p>
 *
 * <p><strong>Extension.</strong> Register mappings by exact BlueId and keep
 * converters free of ambient global state. Use
 * {@link blue.language.dictionary} for named schema dictionaries,
 * {@link blue.language.mapping.provider} for optional classpath discovery,
 * and {@link blue.language.model.Node} for the values crossing this
 * boundary.</p>
 */
package blue.language.mapping;
