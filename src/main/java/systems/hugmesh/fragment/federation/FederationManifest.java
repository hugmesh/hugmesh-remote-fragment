package systems.hugmesh.fragment.federation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Wire shape of {@code GET /api/federation/manifest} — the
 * hugmesh-federation-api keystone's
 * {@code FederationManifestDto}.
 *
 * <p>The federation read path already filters out disabled +
 * circuit-open rows server-side (the kill-switch), and
 * visibility-filters by the caller's authorities, so the
 * {@code remotes} this fleet receives are exactly the ones it is
 * both allowed AND meant to serve. THIS FLEET then filters
 * {@code remotes} to {@code kind == "fragment"}.
 *
 * <p>{@code metadata} is a JSON STRING on the wire (federation
 * never deserialises it). We keep it as a raw String and do not
 * parse it.
 *
 * @param generatedAt ISO-8601 timestamp at which the federation
 *                    server generated this manifest snapshot;
 *                    informational only
 * @param remotes     the list of enabled, visible remotes included
 *                    by the federation server for the calling
 *                    service account's authority level; may be
 *                    {@code null} on the wire — the
 *                    {@link #remotes()} accessor normalises this
 *                    to an empty list
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FederationManifest(
        String generatedAt,
        List<Remote> remotes
) {
    /**
     * Returns the list of enabled, visible remotes included in
     * this manifest. Normalises a {@code null} wire value (absent
     * JSON field) to an empty list so callers never need to
     * null-check.
     *
     * @return the remote list, never {@code null}
     */
    public List<Remote> remotes() {
        return remotes == null ? List.of() : remotes;
    }

    /**
     * One entry in the manifest — a single registered remote
     * module.
     *
     * <p>Only entries where {@link #isFragment()} returns
     * {@code true} are synced by this fleet; other kinds (e.g.
     * {@code "host"}, {@code "library"}) are ignored.
     *
     * @param slug           short stable identifier for the remote
     *                       (e.g. {@code admin-ui}); used as the
     *                       on-disk directory name under
     *                       {@code _fragments/}
     * @param displayName    human-readable label written into
     *                       {@code index.json}
     * @param kind           remote module type; only
     *                       {@code "fragment"} is synced
     * @param remoteEntryUrl canonical URL of the module federation
     *                       entry point as registered in
     *                       federation; written into
     *                       {@code index.json} for the Angular
     *                       host's {@code loadRemoteModule} call
     * @param exposedModule  the webpack/native federation exposed
     *                       module path (e.g. {@code ./Module});
     *                       written into {@code index.json}
     * @param version        semver version string of this bundle;
     *                       written into {@code index.json}
     * @param contentHash    SHA-256 content hash; used as the
     *                       immutable bundle address for
     *                       deduplication and directory naming
     * @param visibility     federation visibility label (e.g.
     *                       {@code "public"},
     *                       {@code "internal"}); written into
     *                       {@code index.json}; visibility
     *                       enforcement already applied
     *                       server-side
     * @param metadata       opaque JSON string; passed through to
     *                       {@code index.json} unchanged and never
     *                       parsed by the sync
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Remote(
            String slug,
            String displayName,
            String kind,
            String remoteEntryUrl,
            String exposedModule,
            String version,
            String contentHash,
            String visibility,
            String metadata
    ) {
        /**
         * Returns {@code true} if this remote is a static Angular
         * micro-frontend fragment that should be synced by this
         * fleet.
         *
         * @return {@code true} when {@code kind} equals
         *         {@code "fragment"}
         */
        public boolean isFragment() {
            return "fragment".equals(kind);
        }
    }
}
