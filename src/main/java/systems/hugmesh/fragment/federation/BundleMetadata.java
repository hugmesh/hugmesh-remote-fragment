package systems.hugmesh.fragment.federation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Wire shape of
 * {@code GET /api/federation/bundles/{contentHash}} — the
 * federation {@code UserModule} bundle metadata.
 *
 * <p>{@code manifestJson} is the per-bundle FILE LIST (the
 * multi-file manifest, games pattern). It round-trips as a
 * String; for TODAY's single-file federation PUT it defaults to
 * {@code "{}"} (federation deferral). The sync therefore parses
 * {@code manifestJson} for the file list when present and FALLS
 * BACK to the single entry file ({@code remoteEntry.json}) when
 * it is empty/{@code "{}"}.
 *
 * <p>{@code versionDir} is the federation server's INTERNAL
 * opaque storage locator ({@code file:/...} /
 * {@code s3://...}); the sync never uses it — it fetches files
 * by {@code contentHash} through the public bundles/files
 * endpoint.
 *
 * @param id           database surrogate key; not used by the
 *                     sync
 * @param ownerUserId  Casdoor user ID of the bundle owner; not
 *                     used by the sync
 * @param slug         short identifier for the remote (e.g.
 *                     {@code admin-ui}); used by
 *                     {@link FederationSyncClient} and the sync
 *                     engine to name the on-disk directory
 * @param versionDir   server-internal opaque storage path; never
 *                     read by the sync
 * @param contentHash  SHA-256 content hash; the sync uses this
 *                     as the bundle's immutable content address
 *                     and as the URL path segment for bundle/file
 *                     fetches
 * @param manifestJson JSON string encoding the per-bundle file
 *                     list; parsed by
 *                     {@code FragmentSyncService.fileList()} —
 *                     empty or {@code "{}"} signals fall-back to
 *                     the single default entry file
 * @param visibility   federation visibility label (e.g.
 *                     {@code "public"}, {@code "internal"});
 *                     informational at this layer — visibility
 *                     filtering is already enforced server-side
 *                     before this DTO reaches the sync
 * @param createdAt    ISO-8601 creation timestamp; not used by
 *                     the sync
 * @param supersededAt ISO-8601 timestamp when this hash was
 *                     superseded by a newer upload; not used by
 *                     the sync (federation excludes superseded
 *                     bundles from the manifest automatically)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BundleMetadata(
        Long id,
        String ownerUserId,
        String slug,
        String versionDir,
        String contentHash,
        String manifestJson,
        String visibility,
        String createdAt,
        String supersededAt
) {
}
