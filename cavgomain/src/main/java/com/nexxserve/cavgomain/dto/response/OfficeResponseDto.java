package com.nexxserve.cavgomain.dto.response;

import com.nexxserve.cavgomain.entity.Office;
import lombok.Data;

import java.util.List;

@Data
public class OfficeResponseDto {
    private Long id;
    private Long companyId;
    private String companyName;
    private String name;
    private String email;
    private String phone;
    private String address;
    private String city;
    private String companyCode;
    private Double latitude;
    private Double longitude;
    private String googlePlaceName;
    private String customName;
    private String placeId;
    private List<String> contactEmails;
    private List<String> contactPhones;
    private String createdAt;
    private String updatedAt;

    public static OfficeResponseDto fromEntity(Office office) {
        OfficeResponseDto dto = new OfficeResponseDto();
        dto.setId(office.getId());
        // Office IS a Company (joined inheritance) — use inherited fields directly
        dto.setCompanyName(office.getCompanyName());
        dto.setName(office.getCompanyName());
        dto.setEmail(office.getEmail());
        dto.setPhone(office.getPhone());
        dto.setAddress(office.getAddress());
        dto.setCity(office.getCity());
        dto.setCompanyCode(office.getCompanyCode());
        dto.setLatitude(office.getLatitude());
        dto.setLongitude(office.getLongitude());
        dto.setGooglePlaceName(office.getGooglePlaceName());
        dto.setCustomName(office.getCustomName());
        dto.setPlaceId(office.getPlaceId());
        dto.setContactEmails(office.getContactEmails());
        dto.setContactPhones(office.getContactPhones());
        dto.setCreatedAt(office.getCreatedAt() != null ? office.getCreatedAt().toString() : null);
        dto.setUpdatedAt(office.getUpdatedAt() != null ? office.getUpdatedAt().toString() : null);
        return dto;
    }
}
