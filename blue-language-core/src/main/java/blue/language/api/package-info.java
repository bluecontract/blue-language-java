/**
 * Transport-neutral configuration, outcome, diagnostic, and cache value types.
 *
 * <p><strong>Contents.</strong> Immutable API values shared by focused
 * Language services belong here. Semantic algorithms, provider transports,
 * mutable nodes, and host-runtime policy do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.api.BlueOperationLimits} describes bounded requests,
 * {@link blue.language.api.BlueOperationResult} reports exhaustive outcomes,
 * and {@link blue.language.api.BlueCachePolicy} configures owned caches.</p>
 *
 * <p><strong>Lifecycle.</strong> Values are immutable, thread-safe, reusable,
 * and own no closeable resources. They may safely cross application and
 * adapter boundaries.</p>
 *
 * <p><strong>Extension.</strong> The enums and value contracts are closed
 * Language vocabulary. New providers belong in {@code blue.language.provider};
 * semantic operations belong in the focused service packages.</p>
 */
package blue.language.api;
