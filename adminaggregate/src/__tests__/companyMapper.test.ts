import assert from "node:assert/strict";
import { test } from "node:test";
import { mapCompanyResponseDtoToCompany } from "../mappers/companyMapper";

const baseDto = {
  id: 3,
  companyName: "Musanze",
  address: null as any,
  city: null as any,
  companyCode: "TWMSN329",
  status: "ACTIVE" as const,
  createdAt: "2026-11-18T17:24:33.029Z",
  updatedAt: "2026-11-18T17:24:33.029Z",
  createdBy: "",
  updatedBy: "",
};

test("company mapper tolerates missing contact details", () => {
  const company = mapCompanyResponseDtoToCompany({ ...baseDto, email: null, phone: null });

  assert.equal(company.id, "3");
  assert.equal(company.email, "company-3@missing.local");
  assert.equal(company.phone, "unknown-3");
});

test("company mapper preserves supplied contact details", () => {
  const company = mapCompanyResponseDtoToCompany({
    ...baseDto,
    email: " test@gocavgo.com ",
    phone: " +250780000001 ",
  });

  assert.equal(company.email, "test@gocavgo.com");
  assert.equal(company.phone, "+250780000001");
});
