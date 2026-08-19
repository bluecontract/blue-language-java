package blue.language.processor;

import blue.language.model.Node;

import java.util.Objects;

/** Exact processor-side request for one isolated managed-document Root. */
public final class ManagedDocumentStepRequest {

    private final Node exactDocument;
    private final boolean initialized;
    private final boolean terminated;
    private final ManagedDocumentWorkKind workKind;
    private final String channelKey;
    private final Node exactPayload;
    private final Node occurrenceEvent;
    private final FrozenJsonPatch processorPatch;
    private final GasChargeContext attribution;
    private final ManagedDocumentResolutionOverlay resolutionOverlay;

    /**
     * Creates one exact Root-scoped request.
     *
     * @param exactDocument latest exact target body
     * @param initialized asserted exact initialization state
     * @param terminated asserted exact termination state
     * @param workKind closed work role
     * @param channelKey exact Root channel key, possibly empty
     * @param exactPayload exact work payload
     */
    public ManagedDocumentStepRequest(
            Node exactDocument,
            boolean initialized,
            boolean terminated,
            ManagedDocumentWorkKind workKind,
            String channelKey,
            Node exactPayload) {
        this(exactDocument,
                initialized,
                terminated,
                workKind,
                channelKey,
                exactPayload,
                null,
                null,
                GasChargeContext.empty(),
                ManagedDocumentResolutionOverlay.empty());
    }

    /**
     * Creates one exact request with closure gas attribution.
     *
     * @param exactDocument latest exact target body
     * @param initialized asserted exact initialization state
     * @param terminated asserted exact termination state
     * @param workKind closed work role
     * @param channelKey exact Root channel key, possibly empty
     * @param exactPayload exact work payload
     * @param attribution owning document/work gas context
     */
    public ManagedDocumentStepRequest(
            Node exactDocument,
            boolean initialized,
            boolean terminated,
            ManagedDocumentWorkKind workKind,
            String channelKey,
            Node exactPayload,
            GasChargeContext attribution) {
        this(exactDocument,
                initialized,
                terminated,
                workKind,
                channelKey,
                exactPayload,
                null,
                null,
                attribution,
                ManagedDocumentResolutionOverlay.empty());
    }

    /**
     * Creates one exact request including processor-managed delivery evidence.
     *
     * <p>An embedded-event request supplies both its adapter payload and the
     * originating semantic event. A containing-reference request supplies the
     * one already-frozen processor patch. Other work kinds reject these extra
     * values so their evidence shape remains closed.</p>
     *
     * @param exactDocument latest exact target body
     * @param initialized asserted exact initialization state
     * @param terminated asserted exact termination state
     * @param workKind closed work role
     * @param channelKey exact Root channel key, possibly empty
     * @param exactPayload exact work payload
     * @param occurrenceEvent exact originating event for embedded delivery,
     *        otherwise {@code null}
     * @param processorPatch exact containing-reference patch, otherwise
     *        {@code null}
     * @param attribution owning document/work gas context
     */
    public ManagedDocumentStepRequest(
            Node exactDocument,
            boolean initialized,
            boolean terminated,
            ManagedDocumentWorkKind workKind,
            String channelKey,
            Node exactPayload,
            Node occurrenceEvent,
            FrozenJsonPatch processorPatch,
            GasChargeContext attribution) {
        this(exactDocument,
                initialized,
                terminated,
                workKind,
                channelKey,
                exactPayload,
                occurrenceEvent,
                processorPatch,
                attribution,
                ManagedDocumentResolutionOverlay.empty());
    }

    /**
     * Creates one exact request with invocation-local managed resolution
     * evidence.
     *
     * @param exactDocument latest exact target body
     * @param initialized asserted exact initialization state
     * @param terminated asserted exact termination state
     * @param workKind closed work role
     * @param channelKey exact Root channel key, possibly empty
     * @param exactPayload exact work payload
     * @param occurrenceEvent exact originating event for embedded delivery,
     *        otherwise {@code null}
     * @param processorPatch exact containing-reference patch, otherwise
     *        {@code null}
     * @param attribution owning document/work gas context
     * @param resolutionOverlay exact forward-only managed resolution evidence
     */
    public ManagedDocumentStepRequest(
            Node exactDocument,
            boolean initialized,
            boolean terminated,
            ManagedDocumentWorkKind workKind,
            String channelKey,
            Node exactPayload,
            Node occurrenceEvent,
            FrozenJsonPatch processorPatch,
            GasChargeContext attribution,
            ManagedDocumentResolutionOverlay resolutionOverlay) {
        this.exactDocument = Objects.requireNonNull(
                exactDocument, "exactDocument").clone();
        this.initialized = initialized;
        this.terminated = terminated;
        this.workKind = Objects.requireNonNull(workKind, "workKind");
        this.channelKey = Objects.requireNonNull(channelKey, "channelKey");
        this.exactPayload = Objects.requireNonNull(
                exactPayload, "exactPayload").clone();
        this.occurrenceEvent = occurrenceEvent != null
                ? occurrenceEvent.clone()
                : null;
        this.processorPatch = processorPatch;
        this.attribution = Objects.requireNonNull(
                attribution, "attribution");
        this.resolutionOverlay = Objects.requireNonNull(
                resolutionOverlay, "resolutionOverlay");
        validateManagedEvidenceShape();
    }

    /**
     * Returns the latest exact target body.
     *
     * @return defensive exact target body
     */
    public Node exactDocument() { return exactDocument.clone(); }

    /**
     * Returns the asserted initialization state.
     *
     * @return asserted initialization state
     */
    public boolean initialized() { return initialized; }

    /**
     * Returns the asserted termination state.
     *
     * @return asserted termination state
     */
    public boolean terminated() { return terminated; }

    /**
     * Returns the one closed local work role.
     *
     * @return closed work role
     */
    public ManagedDocumentWorkKind workKind() { return workKind; }

    /**
     * Returns the already-selected Root channel key.
     *
     * @return exact Root channel key, possibly empty
     */
    public String channelKey() { return channelKey; }

    /**
     * Returns the exact channel or processor payload.
     *
     * @return defensive exact payload
     */
    public Node exactPayload() { return exactPayload.clone(); }

    /**
     * Returns the exact semantic occurrence behind an embedded adapter.
     *
     * @return defensive event copy, or {@code null} for other work kinds
     */
    public Node occurrenceEvent() {
        return occurrenceEvent != null ? occurrenceEvent.clone() : null;
    }

    /**
     * Returns the exact processor-authored containing-reference patch.
     *
     * @return immutable patch, or {@code null} for other work kinds
     */
    public FrozenJsonPatch processorPatch() { return processorPatch; }

    /**
     * Returns the default attribution for every charge in this step.
     *
     * @return immutable owning document/work gas context
     */
    public GasChargeContext attribution() { return attribution; }

    /**
     * Returns the processor-only forward managed resolution overlay.
     *
     * @return immutable overlay
     */
    public ManagedDocumentResolutionOverlay resolutionOverlay() {
        return resolutionOverlay;
    }

    private void validateManagedEvidenceShape() {
        boolean embedded = workKind == ManagedDocumentWorkKind.EMBEDDED_EVENT;
        boolean containing = workKind
                == ManagedDocumentWorkKind.CONTAINING_REFERENCE_UPDATE;
        if (embedded != (occurrenceEvent != null)) {
            throw new IllegalArgumentException(
                    "Only embedded-event work requires occurrenceEvent");
        }
        if (containing != (processorPatch != null)) {
            throw new IllegalArgumentException(
                    "Only containing-reference work requires processorPatch");
        }
    }
}
