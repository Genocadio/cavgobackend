package com.gocavgo.delivary.controller.office;

import com.gocavgo.delivary.dto.delivery.output.LocationResponse;
import com.gocavgo.delivary.dto.location.input.CreateLocationInput;
import com.gocavgo.delivary.dto.office.input.CreateOfficeInput;
import com.gocavgo.delivary.dto.office.input.UpdateOfficeInput;
import com.gocavgo.delivary.dto.office.output.OfficeResponse;
import com.gocavgo.delivary.service.location.LocationService;
import com.gocavgo.delivary.service.office.OfficeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class OfficeResolver {

    private final OfficeService officeService;
    private final LocationService locationService;

    @QueryMapping
    public List<OfficeResponse> offices() {
        return officeService.getAllOffices();
    }

    @QueryMapping
    public OfficeResponse office(@Argument UUID id) {
        return officeService.getOfficeById(id);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public OfficeResponse createOffice(@Argument @Valid CreateOfficeInput input) {
        return officeService.createOffice(input);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public OfficeResponse updateOffice(@Argument @Valid UpdateOfficeInput input) {
        return officeService.updateOffice(input);
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPER_ADMIN')")
    public LocationResponse createLocation(@Argument @Valid CreateLocationInput input) {
        return locationService.createStandaloneLocation(input);
    }

    @SchemaMapping(typeName = "Office", field = "location")
    public LocationResponse location(OfficeResponse office) {
        return office.locationId() != null
                ? locationService.getLocationByIdOrNull(office.locationId())
                : null;
    }
}
