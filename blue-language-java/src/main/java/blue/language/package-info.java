/**
 * Composes the focused Blue Language and generic Contracts libraries.
 *
 * <p><strong>Contents.</strong> This aggregate package contains only the
 * closeable {@link blue.language.BlueRuntime} composition root and the compact
 * {@link blue.language.Blue} convenience facade. Language algorithms,
 * Contracts engine code, provider transports, conformance fixtures, and host
 * policy belong to their focused modules rather than this package.</p>
 *
 * <p><strong>Entry points.</strong> Use {@code BlueRuntime} when an application
 * needs explicit access to Language, Contracts, and mapping services. Use
 * {@code Blue} for a small set of common operations. Applications that need
 * only one capability should depend on and construct the corresponding focused
 * artifact directly.</p>
 *
 * <p><strong>Lifecycle and thread safety.</strong> Both entry points own bounded
 * runtime state, are reusable and thread-safe when borrowed providers are
 * thread-safe, and must be closed. Closing the aggregate releases Contracts
 * state before Language state and rejects subsequent semantic work.</p>
 *
 * <p><strong>Extension.</strong> Configure providers, immutable cache policy,
 * runtime type registries, gas, evidence, and observers through
 * {@code BlueRuntime.Builder}. Do not subclass the final composition types or
 * add ecosystem-specific semantics here. Language extension SPIs live under
 * {@code blue.language.provider}; generic Contracts SPIs live under
 * {@code blue.language.processor}.</p>
 */
package blue.language;
