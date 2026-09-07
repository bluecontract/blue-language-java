package blue.language.processor;

/**
 * Stable categorical values and dispatch-field names stored in
 * {@link EffectiveContractSnapshot}.
 */
public final class EffectiveContractSnapshotConstants {

    /** Roles assigned during effective-contract recognition. */
    public static final class Role {
        /** Processor-managed channel role. */
        public static final String PROCESSOR_CHANNEL =
                "processor-channel";
        /** Externally fed channel role. */
        public static final String EXTERNAL_CHANNEL =
                "external-channel";
        /** Event handler role. */
        public static final String HANDLER = "handler";
        /** Embedded-processing configuration role. */
        public static final String PROCESS_EMBEDDED =
                "process-embedded";
        /** Processor marker role. */
        public static final String MARKER = "marker";
        /** Registered executable extension role. */
        public static final String EXECUTABLE_EXTENSION =
                "executable-extension";

        private Role() {
        }
    }

    /** Header fields that affect dispatch without opening executable bodies. */
    public static final class DispatchField {
        /** Effective contract ordering field. */
        public static final String ORDER = "order";
        /** Handler channel-selection field. */
        public static final String CHANNEL = "channel";
        /** Channel event-selection field. */
        public static final String EVENT = "event";
        /** Embedded source-path field. */
        public static final String SOURCE_PATH = "sourcePath";
        /** Embedded collection declaration-path field. */
        public static final String COLLECTION_PATH = "collectionPath";
        /** Embedded collection descendant-selection field. */
        public static final String INCLUDE_DESCENDANTS =
                "includeDescendants";

        private DispatchField() {
        }
    }

    private EffectiveContractSnapshotConstants() {
    }
}
