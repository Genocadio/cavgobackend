package com.nexxserve.cavgomain.enums;

/**
 * Lifecycle of a company access request. A staff user (e.g. a fleet manager)
 * requests access to a company by entering its code. Another fleet manager of
 * the company approves or rejects the request — self-approval is not allowed.
 */
public enum CompanyAccessRequestStatus {
    PENDING, APPROVED, REJECTED
}