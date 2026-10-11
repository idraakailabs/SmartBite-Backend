package com.idraak.smartbite.v2.area;

import com.idraak.smartbite.v2.store.DocumentStore;
import com.idraak.smartbite.v2.store.StoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * Hard-deletes expired Places cache documents (lat/lng must be deleted within 30 days; our TTL is 7). Scheduled
 * every 6 hours; a failed run is logged and retried on the next one, leaving weeks of margin.
 */
public class PlacesCacheCleanup {

    private static final Logger log = LoggerFactory.getLogger(PlacesCacheCleanup.class);

    private final DocumentStore store;
    private final Clock clock;

    public PlacesCacheCleanup(DocumentStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public int run() {
        int total = 0;
        try {
            for (String collection : new String[]{PlacesCache.CELLS, PlacesCache.TEXT}) {
                int deleted;
                do {
                    deleted = store.deleteExpired(collection, PlacesCache.EXPIRE_AT, clock.instant(), 400);
                    total += deleted;
                } while (deleted == 400);
            }
            log.info("places cache cleanup deleted={}", total);
        } catch (StoreUnavailableException e) {
            log.error("places cache cleanup failed; will retry on the next run");
        }
        return total;
    }
}
