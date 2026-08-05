/**
 * Owned composition and lifecycle for focused Blue Language services.
 *
 * <p><strong>Contents.</strong> Runtime construction, operation admission,
 * bounded caches, focused service adapters, and close behavior belong here.
 * Domain contracts, provider transports, and mutable global registration do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.runtime.BlueLanguage} is the application composition
 * root. {@link blue.language.runtime.LanguageRuntimeAccess} is the narrow
 * runtime boundary used by integrated processors.</p>
 *
 * <p><strong>Lifecycle.</strong> A runtime owns bounded derived state, is safe
 * to share subject to the configured provider's contract, and must be closed.
 * Configuration is frozen at build time; close is idempotent and rejects new
 * semantic work.</p>
 *
 * <p><strong>Extension.</strong> Supply providers and runtime integrations
 * through their explicit SPIs rather than subclassing composition classes.
 * Focused semantics live in {@code blue.language.graph},
 * {@code blue.language.resolve}, and {@code blue.language.identity}.</p>
 */
package blue.language.runtime;
