package com.idraak.smartbite.v2.entitlement;

/** Where Premium is decided (RevenueCat in production). */
public interface EntitlementSource {

    /** @throws EntitlementUnavailableException when the source can't answer */
    boolean isPremium(String uid);
}
