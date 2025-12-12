import type { CompanyResponseDto, Company } from "../types";

export function mapCompanyResponseDtoToCompany(dto: CompanyResponseDto): Company {
  return {
    id: String(dto.id),
    companyName: dto.companyName,
    email: dto.email,
    phone: dto.phone,
    address: dto.address || null,
    city: dto.city || null,
    companyCode: dto.companyCode,
    status: dto.status,
    createdAt: dto.createdAt || null,
    updatedAt: dto.updatedAt || null,
    createdBy: dto.createdBy || null,
    updatedBy: dto.updatedBy || null,
  };
}



