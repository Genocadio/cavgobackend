import assert from "node:assert/strict";
import { generateKeyPairSync, sign, type KeyObject } from "node:crypto";
import { test } from "node:test";
import {
  assertCompanyAccess,
  buildAuthContext,
  requireAdmin,
  type AuthContext,
} from "../auth/context";

function makeToken(roles: string[], privateKey: KeyObject): string {
  const header = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(JSON.stringify({
    sub: "42",
    iss: "nexxauth",
    type: "org-access",
    roles,
    exp: Math.floor(Date.now() / 1000) + 600,
  })).toString("base64url");
  const signature = sign("RSA-SHA256", Buffer.from(`${header}.${payload}`), privateKey).toString("base64url");
  return `${header}.${payload}.${signature}`;
}

const superAdmin: AuthContext = {
  authenticated: true,
  userId: "admin-1",
  roles: ["SUPER_ADMIN"],
  companyId: null,
  isAdmin: true,
  isSuperAdmin: true,
};

const companyAdmin = (companyId: string | null): AuthContext => ({
  authenticated: true,
  userId: "company-admin-1",
  roles: ["ADMIN"],
  companyId,
  isAdmin: true,
  isSuperAdmin: false,
});

const staff = (companyId: string | null): AuthContext => ({
  authenticated: true,
  userId: "staff-1",
  roles: ["STAFF"],
  companyId,
  isAdmin: false,
  isSuperAdmin: false,
});

test("super admins are never company scoped", () => {
  assert.doesNotThrow(() => assertCompanyAccess(superAdmin, "company-a"));
  assert.doesNotThrow(() => assertCompanyAccess(superAdmin, "company-b"));
  assert.doesNotThrow(() => assertCompanyAccess(superAdmin, null));
});

test("company admins are limited to their own company", () => {
  assert.doesNotThrow(() => assertCompanyAccess(companyAdmin("company-a"), "company-a"));
  assert.throws(
    () => assertCompanyAccess(companyAdmin("company-a"), "company-b"),
    /Company scope mismatch/
  );
});

test("company admins without a membership fail closed", () => {
  assert.throws(
    () => assertCompanyAccess(companyAdmin(null), "company-a"),
    /Company scope mismatch/
  );
  assert.throws(
    () => assertCompanyAccess(companyAdmin("company-a"), null),
    /Company scope mismatch/
  );
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

test("admin-only guards accept both admin roles and reject staff", () => {
  assert.equal(requireAdmin(superAdmin).userId, "admin-1");
  assert.equal(requireAdmin(companyAdmin("company-a")).userId, "company-admin-1");
  assert.throws(() => requireAdmin(staff("company-a")), /Admin role required/);
});

test("token roles map to the right admin flags", async () => {
  const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
  const previousPublicKey = process.env.NEXXAUTH_PUBLIC_KEY;
  const previousMainBaseUrl = process.env.MAIN_BASE_URL;
  process.env.NEXXAUTH_PUBLIC_KEY = publicKey.export({ format: "der", type: "spki" }).toString("base64");
  // No MAIN_BASE_URL → no membership lookup; companyId stays null for both.
  delete process.env.MAIN_BASE_URL;
  try {
    const admin = await buildAuthContext(`Bearer ${makeToken(["ADMIN"], privateKey)}`);
    assert.equal(admin.authenticated, true);
    assert.equal(admin.isAdmin, true);
    assert.equal(admin.isSuperAdmin, false);

    const superuser = await buildAuthContext(`Bearer ${makeToken(["SUPER_ADMIN"], privateKey)}`);
    assert.equal(superuser.authenticated, true);
    assert.equal(superuser.isAdmin, true);
    assert.equal(superuser.isSuperAdmin, true);
  } finally {
    if (previousPublicKey === undefined) delete process.env.NEXXAUTH_PUBLIC_KEY;
    else process.env.NEXXAUTH_PUBLIC_KEY = previousPublicKey;
    if (previousMainBaseUrl === undefined) delete process.env.MAIN_BASE_URL;
    else process.env.MAIN_BASE_URL = previousMainBaseUrl;
  }
});
