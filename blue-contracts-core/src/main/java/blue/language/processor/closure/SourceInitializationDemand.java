package blue.language.processor.closure;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Named noncommitting request for an independent source's canonical initialization evidence. */
public final class SourceInitializationDemand extends ClosureResourceDemand {
    private final SameOriginAttachmentPolicy.Selection selection;
    private final ClosureEnvironment environment;
    private final ExecutionPolicy policy;
    private final String creatorSeedIdentity, creatorPatchSite;

    SourceInitializationDemand(SameOriginAttachmentPolicy.Selection selection, String sourcePath,
            String creatorSeedIdentity, String creatorPatchSite, ClosureEnvironment environment, ExecutionPolicy policy) {
        super(Kind.CANONICAL_INITIALIZATION, identity(selection, sourcePath, creatorSeedIdentity, creatorPatchSite),
                selection.creatorLineage(), sourcePath, selection.suppliedExactRefBlueId(), 0L);
        if (selection.mode() != SameOriginAttachmentPolicy.Mode.FULL_HISTORY)
            throw new IllegalArgumentException("Initialization installation demand requires FULL_HISTORY");
        this.selection = selection;
        this.creatorSeedIdentity = creatorSeedIdentity;
        this.creatorPatchSite = creatorPatchSite;
        this.environment = Objects.requireNonNull(environment, "environment");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    private static String identity(SameOriginAttachmentPolicy.Selection selection, String path, String seed, String site) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("selectionIdentity", Objects.requireNonNull(selection, "selection").identity());
        value.put("creatorSeedIdentity", seed); value.put("creatorPatchSite", site); value.put("sourcePath", path);
        return ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.SOURCE_INITIALIZATION_DEMAND, value);
    }

    public SameOriginAttachmentPolicy.Selection selection() { return selection; }
    public DocumentId targetLineage() { return selection.targetLineage(); }
    public String creatorSeedIdentity() { return creatorSeedIdentity; }
    public String creatorPatchSite() { return creatorPatchSite; }
    public ClosureEnvironment environment() { return environment; }
    /** Creator retry context only; source reconstruction requires independently admitted producer authority. */
    public ExecutionPolicy executionPolicy() { return policy; }
}
