package com.nexxserve.cavgomain.dto.request;

import com.nexxserve.cavgomain.entity.Company;
import com.nexxserve.cavgomain.entity.Office;
import lombok.Data;

import java.util.List;

@Data
public class OfficeRequestDto {
    /** Company code that owns this office */
    private String companyCode;

    /** Office name (becomes companyName on the Company parent) */
    private String name;

    private String email;
    private String phone;
    private String address;
    private String city;

    // ── Location fields (from cavgotrips) ──
    private Double latitude;
    private Double longitude;
    private String googlePlaceName;
    private String customName;
    private String placeId;

    // ── Contact lists ──
    private List<String> contactEmails;
    private List<String> contactPhones;

    public Office toEntity(Company company) {
        Office office = new Office();
        // Office IS a Company (joined inheritance) — copy parent company fields
        office.setCompanyName(this.name);
        office.setEmail(this.email);
        office.setPhone(this.phone);
        office.setAddress(this.address);
        office.setCity(this.city);
        // Office-specific fields
        office.setLatitude(this.latitude);
        office.setLongitude(this.longitude);
        office.setGooglePlaceName(this.googlePlaceName);
        office.setCustomName(this.customName);
        office.setPlaceId(this.placeId);
        if (this.contactEmails != null) office.setContactEmails(new java.util.ArrayList<>(this.contactEmails));
        if (this.contactPhones != null) office.setContactPhones(new java.util.ArrayList<>(this.contactPhones));
        return office;
    }
}
