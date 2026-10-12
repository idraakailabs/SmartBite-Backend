package com.idraak.smartbite.v2.store;

/** The store the tests and local runs use must behave like Firestore (see {@link DocumentStoreContract}). */
class InMemoryDocumentStoreTest extends DocumentStoreContract {

    private final DocumentStore store = new InMemoryDocumentStore();

    @Override
    DocumentStore store() {
        return store;
    }
}
