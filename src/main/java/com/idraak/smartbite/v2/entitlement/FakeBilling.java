package com.idraak.smartbite.v2.entitlement;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Billing fake for tests and local runs ({@code ENTITLEMENT_PROVIDER=fake}): nobody is Premium unless a test says
 * so. Never used in staging or production.
 */
public class FakeBilling implements BillingProvider {

    private final Set<String> premium = ConcurrentHashMap.newKeySet();
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private volatile boolean unavailable;
    private volatile int reads;

    public void setPremium(String uid, boolean isPremium) {
        known.add(uid);
        if (isPremium) premium.add(uid);
        else premium.remove(uid);
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public List<String> deleted() {
        return deleted;
    }

    public int reads() {
        return reads;
    }

    public void reset() {
        premium.clear();
        deleted.clear();
        known.clear();
        unavailable = false;
        reads = 0;
    }

    @Override
    public boolean isPremium(String uid) {
        reads++;
        if (unavailable) throw new EntitlementUnavailableException("fake unavailable", null);
        return premium.contains(uid);
    }

    @Override
    public Outcome deleteCustomer(String uid) {
        if (unavailable) throw new EntitlementUnavailableException("fake unavailable", null);
        deleted.add(uid);
        premium.remove(uid);
        return known.remove(uid) ? Outcome.DELETION_QUEUED : Outcome.NOT_FOUND;
    }
}
