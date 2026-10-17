package com.idraak.smartbite.v2.store;

import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import com.idraak.smartbite.v2.config.V2Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class StoreConfig {

    private static final Logger log = LoggerFactory.getLogger(StoreConfig.class);

    @Bean
    public DocumentStore documentStore(V2Properties props, ObjectProvider<FirebaseApp> app, ObjectProvider<JdbcTemplate> jdbc,
                                       ObjectProvider<TransactionTemplate> tx, ObjectMapper mapper) {
        if (props.stores().postgres()) {
            // StartupGuard has already checked that DATABASE_URL is set.
            log.info("document store: postgres prefix='{}'", props.stores().collectionPrefix());
            return new JdbcDocumentStore(jdbc.getObject(), tx.getObject(), mapper, props.stores().collectionPrefix());
        }
        if (props.stores().firestore()) {
            log.info("document store: firestore prefix='{}'", props.stores().collectionPrefix());
            return new FirestoreDocumentStore(FirestoreClient.getFirestore(app.getObject()),
                    props.stores().collectionPrefix(), props.stores().operationTimeout());
        }
        log.info("document store: in-memory");
        return new InMemoryDocumentStore();
    }
}
