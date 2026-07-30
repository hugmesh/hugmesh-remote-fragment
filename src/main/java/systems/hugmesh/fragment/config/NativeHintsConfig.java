package systems.hugmesh.fragment.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import systems.hugmesh.fragment.federation.BundleMetadata;
import systems.hugmesh.fragment.federation.FederationManifest;
import systems.hugmesh.fragment.sync.FragmentSyncService;

/**
 * GraalVM native-image / AOT reachability hints for the DTO records
 * this app reaches reflectively without a compile-time reference to
 * every accessor — mirrors the hugmesh-federation-api /
 * admin-bff-api {@code NativeHintsConfig}. Most of these
 * ({@link FederationManifest}, {@link BundleMetadata}) are the
 * JSON-bound records that cross the federation HTTP boundary and
 * are deserialized reflectively by Jackson; {@link
 * FragmentSyncService.SyncResult} is not Jackson-bound (it is only
 * ever read field-by-field by {@code FirstSyncHealthIndicator}) but
 * still needs reflective accessor/constructor access registered for
 * native image, so it is hinted here too.
 *
 * <p>Spring Boot's own AOT covers the framework surface (the
 * WebFlux WebClient codecs + Jackson auto-configuration); what it
 * cannot always infer are the typed DTO records this client
 * deserializes manifest/bundle responses into. The Casdoor token
 * response is read into a generic {@code Map} (already
 * reflection-safe) and needs no hint.
 *
 * <p>{@code add-reachability-metadata} (from the parent's
 * {@code native} profile) supplies the library-side metadata for
 * the reactor/netty/jackson jars on top of these app-specific
 * hints. A full {@code -Pnative native:compile} on a GraalVM host
 * is the authoritative check.
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(NativeHintsConfig.Hints.class)
public class NativeHintsConfig {

    static class Hints implements RuntimeHintsRegistrar {

        /**
         * Register reflection access for the DTO records used by
         * the federation sync client: the Jackson-deserialized
         * manifest/bundle records plus the non-Jackson-bound
         * {@link FragmentSyncService.SyncResult} (read field-by-field
         * by {@code FirstSyncHealthIndicator} but still reflectively
         * accessed).
         *
         * @param hints       the mutable hints accumulator provided
         *                    by the AOT engine
         * @param classLoader the application class loader (unused
         *                    here; provided for conditional
         *                    registration in multi-classloader
         *                    environments)
         */
        @Override
        public void registerHints(
                final RuntimeHints hints,
                final ClassLoader classLoader) {
            hints.reflection()
                    .registerType(
                            FederationManifest.class,
                            dtoCategories())
                    .registerType(
                            FederationManifest.Remote.class,
                            dtoCategories())
                    .registerType(
                            BundleMetadata.class,
                            dtoCategories())
                    .registerType(
                            FragmentSyncService.SyncResult.class,
                            dtoCategories());
        }

        /**
         * Returns the minimal set of {@link MemberCategory} values
         * required for Jackson to construct and introspect a record
         * DTO reflectively: declared constructors (for
         * deserialization), declared methods (for accessor-based
         * serialization), and declared fields (for field-based
         * introspection).
         *
         * @return the DTO member category array
         */
        private static MemberCategory[] dtoCategories() {
            return new MemberCategory[] {
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.ACCESS_DECLARED_FIELDS,
            };
        }
    }
}
