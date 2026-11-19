package com.gocavgo.delivary.controller.delivery;

import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

import java.util.List;
import java.util.Objects;

/**
 * Derived GraphQL fields for a package's trip/company history. The lists are
 * projections of the immutable carrier legs written at each custody handoff,
 * so a package handed between many drivers exposes every trip and company it
 * travelled under — not just the current one.
 */
@Controller
public class PackageContextResolver {

    @SchemaMapping(typeName = "DeliveryPackage", field = "tripIds")
    public List<Long> tripIds(PackageResponse pkg) {
        return distinct(pkg.carrierLegs().stream()
                .map(PackageResponse.CarrierLegResponse::tripId)
                .toList());
    }

    @SchemaMapping(typeName = "DeliveryPackage", field = "companyIds")
    public List<java.util.UUID> companyIds(PackageResponse pkg) {
        return distinct(pkg.carrierLegs().stream()
                .map(PackageResponse.CarrierLegResponse::companyId)
                .toList());
    }

    @SchemaMapping(typeName = "DeliveryPackage", field = "companyRefIds")
    public List<Long> companyRefIds(PackageResponse pkg) {
        return distinct(pkg.carrierLegs().stream()
                .map(PackageResponse.CarrierLegResponse::companyRefId)
                .toList());
    }

    private static <T> List<T> distinct(List<T> values) {
        return values.stream().filter(Objects::nonNull).distinct().toList();
    }
}
