package blue.buildlogic;

/** Stable task names, groups, and report paths shared by Blue convention plugins. */
public final class BuildLogicConstants {

    public static final String VERIFICATION_GROUP = "verification";
    public static final String ROOT_BUILD_TASK_PATH = ":build";
    public static final String ROOT_CLEAN_TASK_PATH = ":clean";

    /** Exact fixture inventory bound by the final Language 1.0 package. */
    public static final int EXPECTED_LANGUAGE_FIXTURE_COUNT = 182;

    /** Exact fixture inventory bound by the final Contracts 1.0 package. */
    public static final int EXPECTED_CONTRACTS_FIXTURE_COUNT = 290;

    /** Combined release-conformance fixture inventory. */
    public static final int EXPECTED_RELEASE_FIXTURE_COUNT =
            EXPECTED_LANGUAGE_FIXTURE_COUNT + EXPECTED_CONTRACTS_FIXTURE_COUNT;

    public static final String TASK_API_BASELINE_DIFF = "apiBaselineDiff";
    public static final String TASK_ASSEMBLE_IMMUTABLE_STAGED_REPOSITORY =
            "assembleImmutableStagedRepository";
    public static final String TASK_COMPARE_ARCHIVE_REPLICAS = "compareArchiveReplicas";
    public static final String TASK_JAR_REPLICA = "jarReplica";
    public static final String TASK_JAVADOC_JAR_REPLICA = "javadocJarReplica";
    public static final String TASK_SOURCES_JAR_REPLICA = "sourcesJarReplica";
    public static final String TASK_GENERATE_AGGREGATE_RELEASE_RECEIPT =
            "generateAggregateReleaseReceipt";
    public static final String TASK_GENERATE_CLEAN_BUILD_EVIDENCE =
            "generateCleanBuildEvidence";
    public static final String TASK_GENERATE_CLEAN_SOURCE_EVIDENCE =
            "generateCleanSourceEvidence";
    public static final String TASK_GENERATE_MODULE_STRUCTURE_INVENTORY =
            "generateModuleStructureInventory";
    public static final String TASK_GENERATE_PUBLIC_API_INVENTORY = "generatePublicApiInventory";
    public static final String TASK_GENERATE_PUBLIC_API_UNION = "generatePublicApiUnion";
    public static final String TASK_GENERATE_DOCUMENTATION_REFERENCES =
            "generateDocumentationReferences";
    public static final String TASK_GENERATE_DOCUMENTATION_REPORT =
            "generateDocumentationVerificationReport";
    public static final String TASK_GENERATE_FINAL_QUALITY_REPORT =
            "generateFinalQualityReport";
    public static final String TASK_GENERATE_SOURCE_RELEASE_CHECKSUM =
            "generateSourceReleaseChecksum";
    public static final String TASK_GENERATE_SOURCE_RELEASE_METADATA =
            "generateSourceReleaseMetadata";
    public static final String TASK_FRAGMENTED_PROCESSING_REPORT =
            "fragmentedProcessingReport";
    public static final String TASK_GENERATE_SEMANTIC_API_INVENTORY =
            "generateSemanticApiInventory";
    public static final String TASK_SEMANTIC_DISTRIBUTION_API_JAR =
            "semanticDistributionApiJar";
    public static final String TASK_PREPARE_SEMANTIC_VERIFICATION_WORKSPACE =
            "prepareSemanticVerificationWorkspace";
    public static final String TASK_SEMANTIC_BASELINE_CAPTURE = "semanticBaselineCapture";
    public static final String TASK_SEMANTIC_BASELINE_VERIFY = "semanticBaselineVerify";
    public static final String TASK_VERIFY_RELEASE_EVIDENCE_REPORT =
            "verifyReleaseEvidenceReport";
    public static final String TASK_VERIFY_SEMANTIC_API_MIGRATION =
            "verifySemanticApiMigration";
    public static final String TASK_COMPARE_SOURCE_RELEASE_REPLICA =
            "compareSourceReleaseReplica";
    public static final String TASK_SOURCE_RELEASE_ARCHIVE = "sourceReleaseArchive";
    public static final String TASK_SOURCE_RELEASE_ARCHIVE_REPLICA =
            "sourceReleaseArchiveReplica";
    public static final String TASK_VERIFY_AGGREGATE_RELEASE_RECEIPT =
            "verifyAggregateReleaseReceipt";
    public static final String TASK_VERIFY_JAVA_PACKAGE_CYCLES =
            "verifyJavaPackageCycles";
    public static final String TASK_VERIFY_MODULE_STRUCTURE = "verifyModuleStructure";
    public static final String TASK_VERIFY_REPRODUCIBLE_ARCHIVES =
            "verifyReproducibleArchives";
    public static final String TASK_VERIFY_BUILD_SCRIPT_SHAPE = "verifyBuildScriptShape";
    public static final String TASK_VERIFY_CLEAN_BUILD_EVIDENCE = "verifyCleanBuildEvidence";
    public static final String TASK_VERIFY_PUBLISHED_REPOSITORY = "verifyPublishedRepository";
    public static final String TASK_VERIFY_SOURCE_RELEASE_ARCHIVE =
            "verifySourceReleaseArchive";
    public static final String TASK_DOCUMENTATION_VERIFY = "documentationVerify";
    public static final String TASK_FINAL_QUALITY_VERIFY = "finalQualityVerify";
    public static final String TASK_UPDATE_DOCUMENTATION_REFERENCES =
            "updateGeneratedDocumentationReferences";

    public static final String REPORT_AGGREGATE_RELEASE_RECEIPT =
            "reports/release-evidence/aggregate-release-receipt.json";
    public static final String REPORT_AGGREGATE_RELEASE_VERIFICATION =
            "reports/release-evidence/aggregate-release-verification.json";
    public static final String REPORT_CLEAN_BUILD_EVIDENCE =
            "reports/release-evidence/clean-build.json";
    public static final String REPORT_CLEAN_BUILD_VERIFICATION =
            "reports/release-evidence/clean-build-verification.json";
    public static final String REPORT_CLEAN_SOURCE_EVIDENCE =
            "reports/release-evidence/clean-source-input.json";
    public static final String REPORT_API_BASELINE_DIFF = "reports/api/baseline-diff.json";
    public static final String REPORT_API_CURRENT = "reports/api/current-api.txt";
    public static final String REPORT_API_UNION = "reports/api/current-api-union.txt";
    public static final String REPORT_ARCHIVE_REPLICAS =
            "reports/reproducibility/archive-replicas.json";
    public static final String REPORT_MODULE_INVENTORY =
            "reports/module/module-inventory.txt";
    public static final String REPORT_MODULE_STRUCTURE =
            "reports/architecture/module-structure.json";
    public static final String REPORT_PACKAGE_CYCLES =
            "reports/architecture/package-cycles.json";
    public static final String REPORT_BUILD_SCRIPT_SHAPE =
            "reports/architecture/build-script-shape.json";
    public static final String REPORT_DOCUMENTATION_ANALYSIS =
            "reports/documentation/analysis.json";
    public static final String REPORT_DOCUMENTATION_VERIFICATION =
            "reports/documentation/verification.json";
    public static final String REPORT_FINAL_QUALITY =
            "reports/final-quality/final-quality.json";
    public static final String REPORT_FINAL_QUALITY_VERIFICATION =
            "reports/final-quality/verification.json";
    public static final String REPORT_PUBLISHED_REPOSITORY =
            "reports/published-repository/verification.json";
    public static final String REPORT_SOURCE_RELEASE_REPLICA =
            "reports/reproducibility/source-release-replica.json";
    public static final String REPORT_SOURCE_RELEASE_VERIFICATION =
            "reports/reproducibility/source-release-verification.json";
    public static final String REPORT_SOURCE_INPUT_EVIDENCE =
            "reports/release-evidence/source-input.json";
    public static final String REPORT_FRAGMENTED_PROCESSING =
            "reports/fragmented-processing/fragmented-processing.json";
    public static final String REPORT_FRAGMENTED_PROCESSING_MARKDOWN =
            "reports/fragmented-processing/final-generic-kernel.md";
    public static final String REPORT_RELEASE_EVIDENCE_VERIFICATION =
            "reports/fragmented-processing/verification.json";
    public static final String REPORT_SEMANTIC_API_INVENTORY =
            "reports/semantic-baseline/current-api.json";
    public static final String REPORT_SEMANTIC_API_MIGRATION =
            "reports/binary-api/final-1.0-baseline-to-candidate.txt";
    public static final String REPORT_SEMANTIC_BASELINE_VERIFICATION =
            "reports/semantic-baseline/verification.json";
    public static final String DIRECTORY_ARCHIVE_REPLICAS =
            "reproducibility/archive-replicas";
    public static final String DIRECTORY_SOURCE_RELEASE_METADATA =
            "generated/source-release-metadata";
    public static final String DIRECTORY_SOURCE_RELEASE_REPLICA =
            "reproducibility/source-release-replica";
    public static final String DIRECTORY_SOURCE_RELEASE = "release";
    public static final String DIRECTORY_GENERATED_DOCUMENTATION =
            "generated/documentation";

    private BuildLogicConstants() {}
}
