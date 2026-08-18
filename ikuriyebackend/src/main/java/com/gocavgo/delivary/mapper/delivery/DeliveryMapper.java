package com.gocavgo.delivary.mapper.delivery;

import com.gocavgo.delivary.dto.delivery.output.PackageResponse;
import com.gocavgo.delivary.entity.delivery.PackageCustodianEntity;
import com.gocavgo.delivary.entity.delivery.PackageCustodyEntity;
import com.gocavgo.delivary.entity.delivery.PackageDetailEntity;
import com.gocavgo.delivary.entity.delivery.PackageEntity;
import com.gocavgo.delivary.entity.delivery.PackageEventEntity;
import com.gocavgo.delivary.entity.delivery.PackageLocationEntity;
import com.gocavgo.delivary.entity.delivery.PackageMediaEntity;
import com.gocavgo.delivary.entity.delivery.PackagePersonEntity;
import com.gocavgo.delivary.dto.transfer.output.TransferResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface DeliveryMapper {

    @Mapping(target = "name", source = "name")
    @Mapping(target = "phone", source = "phone")
    PackageResponse.CustodianResponse toCustodianResponse(
            PackageCustodianEntity entity,
            String name,
            String phone
    );

    PackageResponse.PersonResponse toPersonResponse(PackagePersonEntity entity);

    PackageResponse.LocationResponse toLocationResponse(PackageLocationEntity entity);

    PackageResponse.MediaResponse toMediaResponse(PackageMediaEntity entity);

    @Mapping(target = "media", source = "media")
    PackageResponse.DetailResponse toDetailResponse(PackageDetailEntity entity, List<PackageResponse.MediaResponse> media);

    PackageResponse.EventResponse toEventResponse(PackageEventEntity entity);

    PackageResponse.CustodyResponse toCustodyResponse(PackageCustodyEntity entity);

    // Full package response — primary fields come from the entity,
    // nested lists are passed separately since they require separate repo queries.
    @Mapping(target = "custodians", source = "custodians")
    @Mapping(target = "people", source = "people")
    @Mapping(target = "locations", source = "locations")
    @Mapping(target = "details", source = "details")
    @Mapping(target = "events", source = "events")
    @Mapping(target = "custody", source = "custody")
    @Mapping(target = "transfers", source = "transfers")
    PackageResponse toFullResponse(
            PackageEntity pkg,
            List<PackageResponse.CustodianResponse> custodians,
            List<PackageResponse.PersonResponse> people,
            List<PackageResponse.LocationResponse> locations,
            PackageResponse.DetailResponse details,
            List<PackageResponse.EventResponse> events,
            List<PackageResponse.CustodyResponse> custody,
            List<TransferResponse> transfers
    );
}
