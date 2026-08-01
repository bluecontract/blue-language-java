package blue.buildlogic;

/** Stable task names, groups, and report paths shared by Blue convention plugins. */
public final class BuildLogicConstants {

    public static final String VERIFICATION_GROUP = "verification";

    public static final String TASK_API_BASELINE_DIFF = "apiBaselineDiff";
    public static final String TASK_COMPARE_ARCHIVE_REPLICAS = "compareArchiveReplicas";
    public static final String TASK_GENERATE_AGGREGATE_RELEASE_RECEIPT =
            "generateAggregateReleaseReceipt";
    public static final String TASK_GENERATE_MODULE_STRUCTURE_INVENTORY =
            "generateModuleStructureInventory";
    public static final String TASK_GENERATE_PUBLIC_API_INVENTORY = "generatePublicApiInventory";
    public static final String TASK_GENERATE_PUBLIC_API_UNION = "generatePublicApiUnion";
    public static final String TASK_VERIFY_AGGREGATE_RELEASE_RECEIPT =
            "verifyAggregateReleaseReceipt";
    public static final String TASK_VERIFY_MODULE_STRUCTURE = "verifyModuleStructure";

    public static final String REPORT_AGGREGATE_RELEASE_RECEIPT =
            "reports/release-evidence/aggregate-release-receipt.json";
    public static final String REPORT_AGGREGATE_RELEASE_VERIFICATION =
            "reports/release-evidence/aggregate-release-verification.json";
    public static final String REPORT_API_BASELINE_DIFF = "reports/api/baseline-diff.json";
    public static final String REPORT_API_CURRENT = "reports/api/current-api.txt";
    public static final String REPORT_API_UNION = "reports/api/current-api-union.txt";
    public static final String REPORT_ARCHIVE_REPLICAS =
            "reports/reproducibility/archive-replicas.json";
    public static final String REPORT_MODULE_INVENTORY =
            "reports/module/module-inventory.txt";
    public static final String REPORT_MODULE_STRUCTURE =
            "reports/architecture/module-structure.json";

    private BuildLogicConstants() {}
}
