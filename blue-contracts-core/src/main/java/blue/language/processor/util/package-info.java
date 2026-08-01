/**
 * Holds narrow, protocol-facing helpers shared by Contracts processing code.
 *
 * <p><strong>Contents.</strong> This package contains named document-field and
 * pointer constants, canonical byte-size calculation, and processor pointer
 * validation. General-purpose collections, reflection, I/O, mutable global
 * state, and unrelated convenience methods do not belong here.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.processor.util.ProcessorContractConstants} and
 * {@link blue.language.processor.util.ProcessorPointerConstants} replace
 * repeated protocol literals. {@link blue.language.processor.util.PointerUtils}
 * and {@link blue.language.processor.util.NodeCanonicalizer} expose the narrow
 * deterministic operations used by the kernel.</p>
 *
 * <p><strong>Lifecycle.</strong> The types are stateless utility owners. Their
 * operations allocate or return owned values and are safe for concurrent use;
 * callers retain ownership of supplied nodes.</p>
 *
 * <p><strong>Extension.</strong> Add a helper only when it represents a shared
 * Contracts protocol rule and has deterministic, side-effect-free behavior.
 * General Language pointer and wire-form behavior belongs with
 * {@link blue.language.model.wire.JsonPointer}; processing orchestration belongs in
 * {@link blue.language.processor}.</p>
 */
package blue.language.processor.util;
