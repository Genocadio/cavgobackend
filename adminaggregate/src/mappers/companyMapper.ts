import type { CompanyResponseDto, Company } from "../types";

export function mapCompanyResponseDtoToCompany(dto: CompanyResponseDto): Company {
  if (dto.id == null) {
    throw new Error("Company mapper: id is required");
  }
  if (!dto.companyName) {
    throw new Error(`Company mapper: companyName is required for company ${dto.id}`);
  }
  if (!dto.companyCode) {
    throw new Error(`Company mapper: companyCode is required for company ${dto.id}`);
  }
  if (!dto.status) {
    throw new Error(`Company mapper: status is required for company ${dto.id}`);
  }

  // cavgomain allows companies without contact details, but the local schema
  // requires both fields. Normalise them instead of aborting the whole sync.
  const normalizedEmail = dto.email?.trim() || `company-${dto.id}@missing.local`;
  const normalizedPhone = dto.phone?.trim() || `unknown-${dto.id}`;

  return {
    id: String(dto.id),
    companyName: dto.companyName,
    email: normalizedEmail,
    phone: normalizedPhone,
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



