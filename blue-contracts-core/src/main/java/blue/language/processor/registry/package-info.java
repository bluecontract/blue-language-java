/**
 * Publishes and verifies the closed Blue Contracts runtime type registry.
 *
 * <p><strong>Contents.</strong> This package contains stable runtime keys,
 * named BlueId constants, compatibility aliases, and the fail-closed registry
 * that verifies bundled canonical type resources. Application contract
 * registration and arbitrary classpath discovery do not belong here.</p>
 *
 * <p><strong>Entry points.</strong> Use
 * {@link blue.language.processor.registry.RuntimeTypeKey} and
 * {@link blue.language.processor.registry.RuntimeBlueIds} instead of repeating
 * encoded identities. {@link blue.language.processor.registry.BlueRuntimeTypeRegistry}
 * supplies verified canonical nodes and a read-only provider.</p>
 *
 * <p><strong>Lifecycle.</strong> Registry construction eagerly verifies every
 * resource and fails closed. A successfully constructed registry is immutable,
 * thread-safe, and returns defensive node copies; the default instance can be
 * shared for the process lifetime.</p>
 *
 * <p><strong>Extension.</strong> Built-in registry changes are protocol changes
 * and require regenerated canonical resources, digests, package identity, and
 * conformance evidence. Application-defined processors instead register exact
 * identities through {@link blue.language.processor.ContractProcessorRegistry}
 * in the neighboring {@link blue.language.processor} package.</p>
 */
package blue.language.processor.registry;
