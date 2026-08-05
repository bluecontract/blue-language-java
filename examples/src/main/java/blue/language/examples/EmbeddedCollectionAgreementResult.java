package blue.language.examples;

import blue.language.processor.ExternalOrderKey;

/** Immutable observations proving all collection-path example steps. */
public final class EmbeddedCollectionAgreementResult {
    private final String initialLessonBlueId;
    private final String reusedParticipantBlueId;
    private final long lessonAProgressAfterTarget;
    private final long lessonBProgressAfterTarget;
    private final long lessonCProgressDuringCreation;
    private final long lessonCProgressAfterNextEvent;
    private final String activatedScopePath;
    private final ExternalOrderKey activationStart;
    private final String lessonAParticipantBlueId;
    private final String lessonBParticipantBlueId;
    private final String lessonCParticipantBlueId;
    private final String parentParticipantBlueId;

    EmbeddedCollectionAgreementResult(
            String initialLessonBlueId,
            String reusedParticipantBlueId,
            long lessonAProgressAfterTarget,
            long lessonBProgressAfterTarget,
            long lessonCProgressDuringCreation,
            long lessonCProgressAfterNextEvent,
            String activatedScopePath,
            ExternalOrderKey activationStart,
            String lessonAParticipantBlueId,
            String lessonBParticipantBlueId,
            String lessonCParticipantBlueId,
            String parentParticipantBlueId) {
        this.initialLessonBlueId = initialLessonBlueId;
        this.reusedParticipantBlueId = reusedParticipantBlueId;
        this.lessonAProgressAfterTarget = lessonAProgressAfterTarget;
        this.lessonBProgressAfterTarget = lessonBProgressAfterTarget;
        this.lessonCProgressDuringCreation = lessonCProgressDuringCreation;
        this.lessonCProgressAfterNextEvent = lessonCProgressAfterNextEvent;
        this.activatedScopePath = activatedScopePath;
        this.activationStart = activationStart;
        this.lessonAParticipantBlueId = lessonAParticipantBlueId;
        this.lessonBParticipantBlueId = lessonBParticipantBlueId;
        this.lessonCParticipantBlueId = lessonCParticipantBlueId;
        this.parentParticipantBlueId = parentParticipantBlueId;
    }

    /**
     * Returns the exact initial BlueId shared by lesson-a and lesson-b.
     *
     * @return shared initial Lesson BlueId
     */
    public String getInitialLessonBlueId() {
        return initialLessonBlueId;
    }

    /**
     * Returns the exact participant Channel BlueId reused by both Lessons.
     *
     * @return reused participant Channel BlueId
     */
    public String getReusedParticipantBlueId() {
        return reusedParticipantBlueId;
    }

    /**
     * Returns lesson-a progress after its concretely targeted event.
     *
     * @return lesson-a progress after targeting
     */
    public long getLessonAProgressAfterTarget() {
        return lessonAProgressAfterTarget;
    }

    /**
     * Returns lesson-b progress after lesson-a was targeted.
     *
     * @return unchanged lesson-b progress
     */
    public long getLessonBProgressAfterTarget() {
        return lessonBProgressAfterTarget;
    }

    /**
     * Returns lesson-c progress in the event that created it.
     *
     * @return lesson-c progress during creation
     */
    public long getLessonCProgressDuringCreation() {
        return lessonCProgressDuringCreation;
    }

    /**
     * Returns lesson-c progress after the next eligible event.
     *
     * @return lesson-c progress after activation
     */
    public long getLessonCProgressAfterNextEvent() {
        return lessonCProgressAfterNextEvent;
    }

    /**
     * Returns the concrete scope activated by the post-commit delta.
     *
     * @return activated concrete scope path
     */
    public String getActivatedScopePath() {
        return activatedScopePath;
    }

    /**
     * Returns the exclusive event-order boundary for lesson-c activation.
     *
     * @return exclusive activation boundary
     */
    public ExternalOrderKey getActivationStart() {
        return activationStart;
    }

    /**
     * Returns lesson-a's retained participant Channel BlueId.
     *
     * @return lesson-a participant Channel BlueId
     */
    public String getLessonAParticipantBlueId() {
        return lessonAParticipantBlueId;
    }

    /**
     * Returns lesson-b's retained participant Channel BlueId.
     *
     * @return lesson-b participant Channel BlueId
     */
    public String getLessonBParticipantBlueId() {
        return lessonBParticipantBlueId;
    }

    /**
     * Returns lesson-c's participant Channel BlueId.
     *
     * @return lesson-c participant Channel BlueId
     */
    public String getLessonCParticipantBlueId() {
        return lessonCParticipantBlueId;
    }

    /**
     * Returns the replacement parent participant Channel BlueId.
     *
     * @return replacement parent participant Channel BlueId
     */
    public String getParentParticipantBlueId() {
        return parentParticipantBlueId;
    }
}
