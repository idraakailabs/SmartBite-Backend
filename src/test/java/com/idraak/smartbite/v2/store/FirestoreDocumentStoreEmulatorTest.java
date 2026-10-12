package com.idraak.smartbite.v2.store;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.cloud.NoCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.FirestoreOptions;
import io.grpc.ManagedChannelBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

/**
 * {@link FirestoreDocumentStore} against the Firestore emulator: runs only when {@code FIRESTORE_EMULATOR_HOST}
 * is set, e.g.
 * <pre>
 * firebase emulators:exec --only firestore --project demo-smartbite "./mvnw -B test -Dtest=FirestoreDocumentStoreEmulatorTest"
 * </pre>
 * (a {@code demo-} project needs no sign-in and touches no real data). See docs/TECHNICAL.md, "Tests".
 */
@EnabledIfEnvironmentVariable(named = "FIRESTORE_EMULATOR_HOST", matches = ".+")
class FirestoreDocumentStoreEmulatorTest extends DocumentStoreContract {

    private static Firestore db;
    private static DocumentStore store;

    private static synchronized DocumentStore shared() {
        if (store == null) {
            String host = System.getenv("FIRESTORE_EMULATOR_HOST");
            // The emulator speaks plaintext HTTP/2; without an explicit channel this client version
            // negotiates TLS and fails with "First received frame was not SETTINGS".
            db = FirestoreOptions.getDefaultInstance().toBuilder()
                    .setProjectId("demo-smartbite")
                    .setHost(host)
                    .setChannelProvider(InstantiatingGrpcChannelProvider.newBuilder()
                            .setEndpoint(host)
                            .setChannelConfigurator(ManagedChannelBuilder::usePlaintext)
                            .build())
                    .setCredentials(NoCredentials.getInstance())
                    .setCredentialsProvider(NoCredentialsProvider.create())
                    .build()
                    .getService();
            store = new FirestoreDocumentStore(db, "test_", Duration.ofSeconds(10));
        }
        return store;
    }

    @AfterAll
    static void close() throws Exception {
        // close() waits for the gRPC channel to terminate, which can hang against the emulator.
        if (db != null) db.shutdownNow();
    }

    @Override
    DocumentStore store() {
        return shared();
    }
}
