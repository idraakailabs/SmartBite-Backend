-- The server's own documents (quota counters, Places and recipe caches, reports, deletion retries) when
-- smartbite.v2.stores.mode = postgres. Same shape as the Firestore collections: one JSON object per document.
-- Instants are stored as {"$ts": "<ISO-8601>"} so they read back as Instants.
create table kv_document (
    collection  varchar(120) not null,
    id          varchar(300) not null,
    data        jsonb        not null,
    updated_at  timestamptz  not null default now(),
    primary key (collection, id)
);
