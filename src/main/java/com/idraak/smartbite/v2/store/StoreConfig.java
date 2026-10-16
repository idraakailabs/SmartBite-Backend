package com.idraak.smartbite.v2.store;

import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import com.idraak.smartbite.v2.config.V2Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class StoreConfig {

    @Bean
    public DocumentStore documentStore(V2Properties props, ObjectProvider<FirebaseApp> app, ObjectProvider<JdbcTemplate> jdbc,
                                       ObjectProvider<TransactionTemplate> tx, ObjectMapper mapper) {
        if (props.stores().postgres()) {
            // StartupGuard has already checked that DATABASE_URL is set.
            return new JdbcDocumentStore(jdbc.getObject(), tx.getObject(), mapper, props.stores().collectionPrefix());
        }
        if (props.stores().firestore()) {
            return new FirestoreDocumentStore(FirestoreClient.getFirestore(app.getObject()),
                    props.stores().collectionPrefix(), props.stores().operationTimeout());
        }
        return new InMemoryDocumentStore();
    }
}
