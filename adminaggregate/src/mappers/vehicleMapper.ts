import type { VehicleResponseDto, Car } from "../types";

export function mapVehicleResponseDtoToCar(dto: VehicleResponseDto): Car {
  if (dto.id == null) {
    throw new Error("Vehicle mapper: id is required");
  }
  if (!dto.licensePlate) {
    throw new Error(`Vehicle mapper: licensePlate is required for vehicle ${dto.id}`);
  }
  if (!dto.make) {
    throw new Error(`Vehicle mapper: make is required for vehicle ${dto.id}`);
  }
  if (!dto.model) {
    throw new Error(`Vehicle mapper: model is required for vehicle ${dto.id}`);
  }
  if (dto.capacity == null) {
    throw new Error(`Vehicle mapper: capacity is required for vehicle ${dto.id}`);
  }
  if (!dto.status) {
    throw new Error(`Vehicle mapper: status is required for vehicle ${dto.id}`);
  }
  if (dto.companyId == null) {
    throw new Error(`Vehicle mapper: companyId is required for vehicle ${dto.id}`);
  }

  return {
    id: String(dto.id),
    plate: dto.licensePlate, // Map licensePlate from DTO to plate in Car
    make: dto.make,
    model: dto.model,
    vehicleType: dto.vehicleType || null,
    capacity: dto.capacity,
    status: dto.status,
    isOnline: false, // Default value, not provided in DTO
    currentLocation: null, // Location comes from separate location updates
    companyId: String(dto.companyId),
    createdAt: dto.createdAt || null,
    updatedAt: dto.updatedAt || null,
  };
}


