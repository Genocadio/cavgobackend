import assert from "node:assert/strict";
import { test } from "node:test";
import { assertCompanyAccess, requireAdmin, type AuthContext } from "../auth/context";

const admin: AuthContext = {
  authenticated: true,
  userId: "admin-1",
  roles: ["SUPER_ADMIN"],
  companyId: null,
  isAdmin: true,
};

const staff = (companyId: string | null): AuthContext => ({
  authenticated: true,
  userId: "staff-1",
  roles: ["STAFF"],
  companyId,
  isAdmin: false,
});

test("admins bypass company scope", () => {
  assert.doesNotThrow(() => assertCompanyAccess(admin, "company-a"));
});

test("staff can only access their own company", () => {
  assert.doesNotThrow(() => assertCompanyAccess(staff("company-a"), "company-a"));
  assert.throws(
    () => assertCompanyAccess(staff("company-a"), "company-b"),
    /Company scope mismatch/
  );
});

test("missing company ids fail closed", () => {
  assert.throws(() => assertCompanyAccess(staff(null), "company-a"), /Company scope mismatch/);
  assert.throws(() => assertCompanyAccess(staff("company-a"), null), /Company scope mismatch/);
});

test("unauthenticated callers are rejected", () => {
  assert.throws(() => assertCompanyAccess(undefined, "company-a"), /Unauthenticated/);
});

test("admin-only guards reject staff", () => {
  assert.equal(requireAdmin(admin).userId, "admin-1");
  assert.throws(() => requireAdmin(staff("company-a")), /Admin role required/);
});
