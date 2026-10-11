package com.idraak.smartbite.v2.entitlement;

/** The billing backend: RevenueCat in real environments, {@link FakeBilling} in tests and local runs. */
public interface BillingProvider extends EntitlementSource, BillingCustomerDeleter {
}
