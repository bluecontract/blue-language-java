package blue.language.processor;

import blue.language.model.Node;

import java.util.List;

/**
 * Synchronous closure-owned continuation reached at isolated local-effect
 * barriers.
 *
 * <p>The callback receives exact existing runtime values. It owns graph,
 * identity, public-event, and caused-work orchestration; the isolated runtime
 * never creates an ambient containing-document context.</p>
 */
public interface ManagedDocumentStepContinuation {

    /**
     * The exact forward read view after a synchronous continuation has completed. Returning
     * {@code null} preserves the initial overlay for compatibility continuations. This is
     * owning interpreter evidence, never a request to look up a host's current physical head.
     */
    default ManagedDocumentResolutionOverlay currentResolutionOverlay(String scopePath) { return null; }

    /**
     * Continues immediately after one exact authored patch was applied.
     *
     * @param scopePath normalized local scope, always Root in closure mode
     * @param currentDocument exact post-patch target state
     * @param patch immutable authored patch just applied
     * @param updates immutable exact update occurrences caused by the patch
     */
    void afterPatch(
            String scopePath,
            Node currentDocument,
            FrozenJsonPatch patch,
            List<DocumentUpdateOccurrence> updates);

    /**
     * Transfers one exact event before public-Root classification or global
     * FIFO admission and charging.
     *
     * @param scopePath normalized emitting scope
     * @param originContractKey exact emitting Handler contract key
     * @param event exact emitted application event
     * @param eventBlueId exact identity established before transfer
     */
    void onApplicationEvent(
            String scopePath,
            String originContractKey,
            Node event,
            String eventBlueId);

    /**
     * Transfers one exact termination intent to the closure orchestrator.
     *
     * @param scopePath normalized requesting scope
     * @param cause exact termination cause
     * @param reason exact termination reason
     */
    void onTerminationRequested(
            String scopePath,
            String cause,
            String reason);
}
