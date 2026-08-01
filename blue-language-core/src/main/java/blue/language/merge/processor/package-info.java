/**
 * Ordered deterministic stages used by the Blue resolution merger.
 *
 * <p><strong>Contents.</strong> Type assignment, payload propagation, list and
 * dictionary merging, and schema propagation/validation stages belong here.
 * Application workflows, provider I/O, and host-specific policy do not.</p>
 *
 * <p><strong>Entry points.</strong>
 * {@link blue.language.merge.processor.SequentialMergingProcessor} composes
 * the focused {@link blue.language.merge.MergingProcessor} implementations.
 * Ordinary applications enter through {@code blue.language.resolve.BlueResolution}.</p>
 *
 * <p><strong>Lifecycle.</strong> Stages are stateless or invocation-scoped and
 * own no external resources. A composed processor follows the thread-safety of
 * its supplied stages and should not share mutable run state across calls.</p>
 *
 * <p><strong>Extension.</strong> These classes implement the closed Language
 * merge algorithm; adding a stage requires specification and conformance
 * evidence. Snapshot assembly lives in {@code blue.language.merge}; schema
 * value rules live in {@code blue.language.model.value}.</p>
 */
package blue.language.merge.processor;
