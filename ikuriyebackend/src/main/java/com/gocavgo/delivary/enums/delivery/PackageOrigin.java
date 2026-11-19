package com.gocavgo.delivary.enums.delivery;

/**
 * Distinguishes private courier packages from packages handled under a company.
 *
 * <p>This is deliberately separate from {@link DeliveryType}: an OPEN package can
 * still be carried by a company driver, while FIXED_ROUTE packages are normally
 * company/office based. Admin reporting focuses on {@code FLEET} packages;
 * private packages keep their trip/company context null.
 */
public enum PackageOrigin {
    PRIVATE,
    FLEET
}
