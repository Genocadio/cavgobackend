import type { CompanyUserResponseDto, Driver } from "../types";

export function mapCompanyUserResponseDtoToDriver(dto: CompanyUserResponseDto): Driver {
  if (dto.id == null) {
    throw new Error("Driver mapper: id is required");
  }
  if (dto.companyId == null) {
    throw new Error(`Driver mapper: companyId is required for driver ${dto.id}`);
  }
  if (!dto.status) {
    throw new Error(`Driver mapper: status is required for driver ${dto.id}`);
  }

  const normalizedFirstName = dto.firstName?.trim() || "Unknown";
  const normalizedLastName = dto.lastName?.trim() || "Driver";
  const normalizedPhone = dto.phone?.trim() || `unknown-${dto.id}`;
  const normalizedEmail = dto.email?.trim() || `driver-${dto.id}@missing.local`;

  return {
    id: String(dto.id),
    firstName: normalizedFirstName,
    lastName: normalizedLastName,
    phoneNumber: normalizedPhone,
    email: normalizedEmail,
    status: dto.status,
    companyId: String(dto.companyId),
    dateOfBirth: dto.dateOfBirth || null,
    address: dto.address || null,
    licenseNumber: dto.licenseNumber || null,
    licenseExpiry: dto.licenseExpiry || null,
    role: dto.role || null,
    createdAt: dto.createdAt || null,
    updatedAt: dto.updatedAt || null,
  };
}



