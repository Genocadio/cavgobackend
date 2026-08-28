package com.nexxserve.cavgomain.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * An office/branch. An office is a Company (JOINED inheritance into the
 * {@code office} table) and carries:
 * <ul>
 *   <li>A single location replicated from cavgotrips (coordinates, names and an
 *       external place id — the id is NOT generated here, it comes with the
 *       location data).</li>
 *   <li>Multiple contact emails and phones.</li>
 * </ul>
 */
@Entity
@Table(name = "office")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class Office extends Company {

    @Column(name = "location_latitude")
    private Double latitude;

    @Column(name = "location_longitude")
    private Double longitude;

    @Column(name = "google_place_name")
    private String googlePlaceName;

    @Column(name = "custom_name")
    private String customName;

    @Column(name = "place_id")
    private String placeId;

    @ElementCollection
    @CollectionTable(name = "office_contact_emails", joinColumns = @JoinColumn(name = "office_id"))
    @Column(name = "email")
    @ToString.Exclude
    private List<String> contactEmails = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "office_contact_phones", joinColumns = @JoinColumn(name = "office_id"))
    @Column(name = "phone")
    @ToString.Exclude
    private List<String> contactPhones = new ArrayList<>();
}
