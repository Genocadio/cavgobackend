import type { VehicleResponseDto, Car } from "../types";

export function mapVehicleResponseDtoToCar(dto: VehicleResponseDto): Car {
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


