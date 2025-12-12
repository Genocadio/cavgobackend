import type { CompanyUserResponseDto, Driver } from "../types";

export function mapCompanyUserResponseDtoToDriver(dto: CompanyUserResponseDto): Driver {
  return {
    id: String(dto.id),
    firstName: dto.firstName || "",
    lastName: dto.lastName || "",
    phoneNumber: dto.phone || "",
    email: dto.email || "",
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



