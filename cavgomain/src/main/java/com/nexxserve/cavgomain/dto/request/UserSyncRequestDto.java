package com.nexxserve.cavgomain.dto.request;

import lombok.Data;

/**
 * Request body for user sync. When {@code companyCode} is provided,
 * the user is associated with that company (used by fleet managers
 * to link themselves to their company after first login).
 */
@Data
public class UserSyncRequestDto {
    /** Optional company code to associate the user with a company. */
    private String companyCode;
}
