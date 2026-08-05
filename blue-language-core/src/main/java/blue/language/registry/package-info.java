/**
 * Released Language bootstrap content and verified core-type registry.
 *
 * <p><strong>Contents.</strong> Canonical core type definitions, bundled
 * transformation manifests, and bootstrap provider composition belong here.
 * Host runtime types and application registration do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.registry.BlueCoreTypeRegistry} exposes released core
 * definitions, while {@link blue.language.registry.BootstrapProvider} and
 * {@link blue.language.registry.NodeProviderWrapper} assemble verified lookup.</p>
 *
 * <p><strong>Lifecycle.</strong> Released registries and bootstrap providers are
 * immutable process-wide values and thread-safe after initialization. They own
 * no external closeable resources.</p>
 *
 * <p><strong>Extension.</strong> Changing identity-bearing registry content is
 * a versioned Language release operation. Runtime-specific types belong in the
 * owning runtime registry; ordinary content belongs behind
 * {@code blue.language.provider.NodeProvider}.</p>
 */
package blue.language.registry;
