package com.nexxserve.cavgomain.enums;

public enum CompanyUserRole {
    ADMIN, DRIVER, FLEET_MANAGER, SUPERVISOR, WORKER,
    /** Global application superuser — never a company membership role. */
    SUPER_ADMIN
}
