package com.idraak.smartbite.v2.store;

import com.google.firebase.FirebaseApp;
import com.google.firebase.cloud.FirestoreClient;
import com.idraak.smartbite.v2.config.V2Properties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StoreConfig {

    @Bean
    public DocumentStore documentStore(V2Properties props, ObjectProvider<FirebaseApp> app) {
        if (props.stores().firestore()) {
            return new FirestoreDocumentStore(FirestoreClient.getFirestore(app.getObject()),
                    props.stores().collectionPrefix(), props.stores().operationTimeout());
        }
        return new InMemoryDocumentStore();
    }
}
