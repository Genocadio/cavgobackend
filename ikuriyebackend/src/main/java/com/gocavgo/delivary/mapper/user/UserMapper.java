package com.gocavgo.delivary.mapper.user;

import com.gocavgo.delivary.dto.user.output.UserResponse;
import com.gocavgo.delivary.entity.user.UserEntity;
import com.gocavgo.delivary.enums.user.Role;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "role", expression = "java(role)")
    @Mapping(target = "avatarUrl", ignore = true)
    UserResponse toResponse(UserEntity entity, Role role);

    default UserResponse toResponseWithNullCheck(UserEntity entity, Role role) {
        if (entity == null) return null;
        return toResponse(entity, role);
    }
}
