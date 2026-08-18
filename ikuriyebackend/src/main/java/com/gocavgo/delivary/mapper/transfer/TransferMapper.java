package com.gocavgo.delivary.mapper.transfer;

import com.gocavgo.delivary.dto.transfer.output.TransferResponse;
import com.gocavgo.delivary.entity.transfer.TransferEntity;
import com.gocavgo.delivary.entity.transfer.TransferPackageEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TransferMapper {

    @Mapping(target = "transferCode", ignore = true)
    @Mapping(target = "packages", source = "packages")
    TransferResponse toResponse(TransferEntity entity, List<TransferResponse.TransferPackageResponse> packages);

    TransferResponse.TransferPackageResponse toPackageResponse(TransferPackageEntity entity);

    default TransferResponse toResponseWithCode(TransferEntity entity,
                                                  List<TransferPackageEntity> packageEntities,
                                                  String transferCode) {
        var packages = packageEntities.stream().map(this::toPackageResponse).toList();
        return new TransferResponse(
                entity.getId(),
                entity.getCreatorId(),
                entity.getRuleType(),
                entity.getAcceptorType(),
                entity.getMatchCompanyId(),
                entity.getMatchUserId(),
                entity.getRequestorId(),
                entity.getStatus(),
                transferCode,
                packages,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
