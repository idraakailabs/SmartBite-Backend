package com.idraak.smartbite.v2.entitlement;

/** Deletes the billing customer record (RevenueCat) on account deletion (TECH-012). */
public interface BillingCustomerDeleter {

    enum Outcome { DELETION_QUEUED, NOT_FOUND }

    /** @throws EntitlementUnavailableException when the provider can't be reached or fails */
    Outcome deleteCustomer(String uid);
}
