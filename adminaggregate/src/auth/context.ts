import { createPublicKey, createVerify } from "crypto";
import type { Request, Response } from "express";
import { GraphQLError } from "graphql";
import * as db from "../db";

/**
 * Authentication/authorization for the admin aggregator.
 *
 * Nexxauth org-access tokens are RS256 JWTs verified offline against the base64
 * DER SPKI public key in NEXXAUTH_PUBLIC_KEY — the same mechanism used by the
 * Java services (see NexxauthJwtVerifier). Tokens carry `sub`, `roles`,
 * `iss=nexxauth` and `type=org-access`, but no company id, so the caller's
 * company is read from cavgomain's `/main/staff/me` using the same token and
 * cached briefly per token.
 *
 * Fail closed: if no public key is configured, or the token is missing/invalid,
 * the request is unauthenticated and every resolver guard rejects it.
 *
 * Authorization: ADMIN and SUPER_ADMIN are both accepted as admins. An ordinary
 * ADMIN is still confined to the company resolved from `/main/staff/me`; only a
 * SUPER_ADMIN (which belongs to no company) is exempt from company scoping.
 */

export interface AuthContext {
  authenticated: boolean;
  userId: string | null;
  roles: string[];
  companyId: string | null;
  /** ADMIN or SUPER_ADMIN — may use admin-only guards. */
  isAdmin: boolean;
  /** SUPER_ADMIN only — the sole role exempt from company scoping. */
  isSuperAdmin: boolean;
}

const ANONYMOUS: AuthContext = {
  authenticated: false,
  userId: null,
  roles: [],
  companyId: null,
  isAdmin: false,
  isSuperAdmin: false,
};

interface JwtClaims {
  sub?: string;
  iss?: string;
  type?: string;
  roles?: string[];
  exp?: number;
}

const companyCache = new Map<string, { companyId: string | null; expiresAt: number }>();
const COMPANY_CACHE_TTL_MS = 60_000;

function base64UrlDecode(value: string): Buffer {
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized + "=".repeat((4 - (normalized.length % 4)) % 4);
  return Buffer.from(padded, "base64");
}

function normalizeRoles(roles: string[] | undefined): string[] {
  return (roles ?? [])
    .map((role) => role.replace(/^ROLE_/i, "").toUpperCase())
    .filter((role) => role.length > 0);
}

function verifyToken(token: string, publicKeyB64: string): JwtClaims {
  const parts = token.split(".");
  if (parts.length !== 3) {
    throw new Error("Malformed JWT");
  }
  const [headerPart, payloadPart, signaturePart] = parts;
  if (!headerPart || !payloadPart || !signaturePart) {
    throw new Error("Malformed JWT");
  }

  const header = JSON.parse(base64UrlDecode(headerPart).toString("utf8")) as { alg?: string };
  if (header.alg !== "RS256") {
    throw new Error(`Unsupported JWT algorithm: ${header.alg}`);
  }

  const der = Buffer.from(publicKeyB64.replace(/\s/g, ""), "base64");
  const publicKey = createPublicKey({ key: der, format: "der", type: "spki" });
  const verifier = createVerify("RSA-SHA256");
  verifier.update(`${headerPart}.${payloadPart}`);
  verifier.end();
  if (!verifier.verify(publicKey, base64UrlDecode(signaturePart))) {
    throw new Error("Invalid JWT signature");
  }

  const claims = JSON.parse(base64UrlDecode(payloadPart).toString("utf8")) as JwtClaims;
  if (claims.iss !== "nexxauth") {
    throw new Error(`Unexpected JWT issuer: ${claims.iss}`);
  }
  if (claims.type !== "org-access") {
    throw new Error("JWT is not an org-access token");
  }
  if (claims.exp == null || claims.exp * 1000 <= Date.now()) {
    throw new Error("JWT has expired");
  }
  return claims;
}

async function lookupCompanyId(token: string): Promise<string | null> {
  const cached = companyCache.get(token);
  if (cached && cached.expiresAt > Date.now()) {
    return cached.companyId;
  }

  const mainBaseUrl = process.env.MAIN_BASE_URL;
  if (!mainBaseUrl) {
    return null;
  }

  let companyId: string | null = null;
  try {
    const response = await fetch(`${mainBaseUrl}/main/staff/me`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    if (response.ok) {
      const body = (await response.json()) as { companyId?: number | string | null };
      if (body.companyId != null) {
        companyId = String(body.companyId);
      }
    }
  } catch (error) {
    console.warn("[AUTH] Could not resolve company membership:", error);
  }

  companyCache.set(token, { companyId, expiresAt: Date.now() + COMPANY_CACHE_TTL_MS });
  return companyId;
}

export async function buildAuthContext(authorizationHeader: string | undefined): Promise<AuthContext> {
  const publicKey = process.env.NEXXAUTH_PUBLIC_KEY;
  if (!publicKey || publicKey.trim().length === 0) {
    console.warn("[AUTH] NEXXAUTH_PUBLIC_KEY is not configured — all GraphQL requests are unauthenticated");
    return ANONYMOUS;
  }

  const match = /^Bearer\s+(.+)$/i.exec(authorizationHeader ?? "");
  if (!match) {
    return ANONYMOUS;
  }

  const token = match[1]?.trim();
  if (!token) {
    return ANONYMOUS;
  }
  try {
    const claims = verifyToken(token, publicKey);
    const roles = normalizeRoles(claims.roles);
    const companyId = await lookupCompanyId(token);
    return {
      authenticated: true,
      userId: claims.sub ? String(claims.sub) : null,
      roles,
      companyId,
      isAdmin: roles.includes("ADMIN") || roles.includes("SUPER_ADMIN"),
      isSuperAdmin: roles.includes("SUPER_ADMIN"),
    };
  } catch (error) {
    console.warn("[AUTH] Rejected token:", error instanceof Error ? error.message : String(error));
    return ANONYMOUS;
  }
}

function forbidden(message: string): GraphQLError {
  return new GraphQLError(message, { extensions: { code: "FORBIDDEN" } });
}

export function requireAuth(ctx?: AuthContext): AuthContext {
  if (!ctx?.authenticated) {
    throw new GraphQLError("Unauthenticated", { extensions: { code: "UNAUTHENTICATED" } });
  }
  return ctx;
}

/** Both ADMIN and SUPER_ADMIN pass; company scoping is enforced separately. */
export function requireAdmin(ctx?: AuthContext): AuthContext {
  const auth = requireAuth(ctx);
  if (!auth.isAdmin) {
    throw forbidden("Admin role required");
  }
  return auth;
}

/**
 * SUPER_ADMIN is globally scoped. Every other caller — including an ordinary
 * ADMIN — must belong to the requested company. An admin with no local company
 * membership therefore fails closed rather than seeing every company.
 */
export function assertCompanyAccess(ctx: AuthContext | undefined, companyId: string | null | undefined): void {
  const auth = requireAuth(ctx);
  if (auth.isSuperAdmin) {
    return;
  }
  if (!companyId || auth.companyId == null || String(companyId) !== String(auth.companyId)) {
    throw forbidden("Company scope mismatch");
  }
}

/** Resource-level check: resolve the car's company from the local store. */
export async function assertCarAccess(ctx: AuthContext | undefined, carId: string): Promise<void> {
  const auth = requireAuth(ctx);
  if (auth.isSuperAdmin) {
    return;
  }
  const car = await db.carRepository.getCarById(carId);
  assertCompanyAccess(auth, car?.companyId);
}

/** Resource-level check for driver-scoped queries. */
export async function assertDriverAccess(ctx: AuthContext | undefined, driverId: string): Promise<void> {
  const auth = requireAuth(ctx);
  if (auth.isSuperAdmin) {
    return;
  }
  const driver = await db.driverRepository.getDriverById(driverId);
  assertCompanyAccess(auth, driver?.companyId);
}

/** Resource-level check: a trip inherits its company from the assigned car. */
export async function assertTripAccess(ctx: AuthContext | undefined, tripId: string): Promise<void> {
  const auth = requireAuth(ctx);
  if (auth.isSuperAdmin) {
    return;
  }
  const trip = await db.tripRepository.getTripById(tripId);
  assertCompanyAccess(auth, trip?.carDriver?.car?.companyId);
}

/** Shared-secret guard for the internal push ingestion endpoints. */
export function requireInternalKey(req: Request, res: Response): boolean {
  const expected = process.env.INTERNAL_API_KEY;
  if (!expected) {
    console.error("[PUSH SYNC] INTERNAL_API_KEY is not configured — rejecting push request");
    res.status(503).json({ error: "Internal API key not configured" });
    return false;
  }
  const provided = req.header("x-internal-api-key");
  if (!provided || provided !== expected) {
    res.status(401).json({ error: "Unauthorized" });
    return false;
  }
  return true;
}
