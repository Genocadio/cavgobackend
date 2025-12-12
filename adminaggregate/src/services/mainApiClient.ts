import { config } from "dotenv";
import type { CompanyResponseDto, VehicleResponseDto, CompanyUserResponseDto, TripApiItem, TripApiResponse } from "../types";

console.log("[ENV] Loading environment variables...");
config();

const MAIN_BASE_URL = process.env.MAIN_BASE_URL;
if (!MAIN_BASE_URL) {
  console.error("[ENV] MAIN_BASE_URL is not set!");
  throw new Error("MAIN_BASE_URL must be set in the environment");
}
console.log(`[ENV] MAIN_BASE_URL loaded: ${MAIN_BASE_URL}`);

const TRIPS_BASE_URL = process.env.TRIPS_BASE_URL;
if (!TRIPS_BASE_URL) {
  console.error("[ENV] TRIPS_BASE_URL is not set!");
  throw new Error("TRIPS_BASE_URL must be set in the environment");
}
console.log(`[ENV] TRIPS_BASE_URL loaded: ${TRIPS_BASE_URL}`);

export async function fetchCompanies(): Promise<CompanyResponseDto[]> {
  console.log(`[URL] Constructing URL for companies...`);
  console.log(`[URL] Base URL: ${MAIN_BASE_URL}`);
  const url = `${MAIN_BASE_URL}/main/companies`;
  console.log(`[URL] Final URL: ${url}`);
  console.log(`[FETCH] Starting fetch: ${url}`);
  try {
    const response = await fetch(url);
    console.log(`[FETCH] Response status: ${response.status} ${response.statusText}`);
    if (!response.ok) {
      const errorText = await response.text().catch(() => response.statusText);
      console.error(`[FETCH] Fetch failed: ${response.status} ${response.statusText} - URL: ${url}`);
      throw new Error(`Failed to fetch companies: ${response.status} ${response.statusText} - ${errorText}`);
    }
    const data = await response.json() as CompanyResponseDto[];
    console.log(`[FETCH] Fetch result: ${data.length} companies`);
    return data;
  } catch (error) {
    const cause = (error as any).cause;
    if (cause && (cause.code === 'ECONNREFUSED' || cause.code === 'ENOTFOUND' || cause.code === 'ETIMEDOUT')) {
      console.error(`[FETCH] Fetch failed: ${cause.code} - Cannot connect to ${url}`);
      console.error(`[FETCH] Attempted URL: ${url}`);
    } else if (error instanceof Error) {
      console.error(`[FETCH] Fetch failed: ${error.message} - URL: ${url}`);
    } else {
      console.error(`[FETCH] Fetch failed: ${error} - URL: ${url}`);
    }
    throw error;
  }
}

export async function fetchVehiclesByCompany(
  companyId: number,
  timeLimit?: string
): Promise<VehicleResponseDto[]> {
  console.log(`[URL] Constructing URL for vehicles (companyId: ${companyId})...`);
  console.log(`[URL] Base URL: ${MAIN_BASE_URL}`);
  const url = new URL(`${MAIN_BASE_URL}/main/vehicles/company/${companyId}`);
  if (timeLimit) {
    console.log(`[URL] Adding timeLimit parameter for vehicles: ${timeLimit}`);
    url.searchParams.set("timeLimit", timeLimit);
  } else {
    console.log(`[URL] No timeLimit - fetching all vehicles for company ${companyId}`);
  }
  
  const urlString = url.toString();
  console.log(`[URL] Final URL: ${urlString}`);
  console.log(`[FETCH] Starting fetch: ${urlString}`);
  
  try {
    const response = await fetch(urlString);
    console.log(`[FETCH] Response status: ${response.status} ${response.statusText}`);
    if (!response.ok) {
      const errorText = await response.text().catch(() => response.statusText);
      console.error(`[FETCH] Fetch failed: ${response.status} ${response.statusText}`);
      throw new Error(`Failed to fetch vehicles for company ${companyId}: ${response.status} ${response.statusText} - ${errorText}`);
    }
    const data = await response.json() as VehicleResponseDto[];
    console.log(`[FETCH] Fetch result: ${data.length} vehicles`);
    return data;
  } catch (error) {
    const cause = (error as any).cause;
    if (cause && (cause.code === 'ECONNREFUSED' || cause.code === 'ENOTFOUND' || cause.code === 'ETIMEDOUT')) {
      console.error(`[FETCH] Fetch failed: ${cause.code} - Cannot connect to ${MAIN_BASE_URL}`);
    } else if (error instanceof Error) {
      console.error(`[FETCH] Fetch failed: ${error.message}`);
    } else {
      console.error(`[FETCH] Fetch failed: ${error}`);
    }
    throw error;
  }
}

export async function fetchDriversByCompany(
  companyId: number,
  timeLimit?: string
): Promise<CompanyUserResponseDto[]> {
  console.log(`[URL] Constructing URL for drivers (companyId: ${companyId})...`);
  console.log(`[URL] Base URL: ${MAIN_BASE_URL}`);
  const url = new URL(`${MAIN_BASE_URL}/main/staff/company/${companyId}/drivers`);
  if (timeLimit) {
    console.log(`[URL] Adding timeLimit parameter for drivers: ${timeLimit}`);
    url.searchParams.set("timeLimit", timeLimit);
  } else {
    console.log(`[URL] No timeLimit - fetching all drivers for company ${companyId}`);
  }
  
  const urlString = url.toString();
  console.log(`[URL] Final URL: ${urlString}`);
  console.log(`[FETCH] Starting fetch: ${urlString}`);
  
  try {
    const response = await fetch(urlString);
    console.log(`[FETCH] Response status: ${response.status} ${response.statusText}`);
    if (!response.ok) {
      const errorText = await response.text().catch(() => response.statusText);
      console.error(`[FETCH] Fetch failed: ${response.status} ${response.statusText}`);
      throw new Error(`Failed to fetch drivers for company ${companyId}: ${response.status} ${response.statusText} - ${errorText}`);
    }
    const data = await response.json() as CompanyUserResponseDto[];
    console.log(`[FETCH] Fetch result: ${data.length} drivers`);
    return data;
  } catch (error) {
    const cause = (error as any).cause;
    if (cause && (cause.code === 'ECONNREFUSED' || cause.code === 'ENOTFOUND' || cause.code === 'ETIMEDOUT')) {
      console.error(`[FETCH] Fetch failed: ${cause.code} - Cannot connect to ${MAIN_BASE_URL}`);
    } else if (error instanceof Error) {
      console.error(`[FETCH] Fetch failed: ${error.message}`);
    } else {
      console.error(`[FETCH] Fetch failed: ${error}`);
    }
    throw error;
  }
}

export async function fetchTrips(options?: {
  lastUpdateTime?: string;
  limit?: number;
  offset?: number;
}): Promise<TripApiItem[]> {
  const limit = options?.limit ?? 100;
  const allTrips: TripApiItem[] = [];
  let currentPage = 1;
  let totalPages = 1;

  while (currentPage <= totalPages) {
    const offset = (currentPage - 1) * limit;
    console.log(`[URL] Constructing URL for trips (page: ${currentPage}/${totalPages}, offset: ${offset}, limit: ${limit})...`);
    console.log(`[URL] Base URL: ${TRIPS_BASE_URL}`);
    const url = new URL(`${TRIPS_BASE_URL}/internal/trips`);
    
    if (options?.lastUpdateTime) {
      console.log(`[URL] Adding last_update_time parameter: ${options.lastUpdateTime}`);
      url.searchParams.set("last_update_time", options.lastUpdateTime);
    }
    
    url.searchParams.set("limit", String(limit));
    url.searchParams.set("offset", String(offset));
    
    const urlString = url.toString();
    console.log(`[URL] Final URL: ${urlString}`);
    console.log(`[FETCH] Starting fetch: ${urlString}`);
    
    try {
      const response = await fetch(urlString);
      console.log(`[FETCH] Response status: ${response.status} ${response.statusText}`);
      
      if (!response.ok) {
        const errorText = await response.text().catch(() => response.statusText);
        console.error(`[FETCH] Fetch failed: ${response.status} ${response.statusText}`);
        throw new Error(`Failed to fetch trips: ${response.status} ${response.statusText} - ${errorText}`);
      }
      
      const data = await response.json() as TripApiResponse;
      console.log(`[FETCH] Fetch result: ${data.trips.length} trips (total: ${data.total}, page: ${data.page}/${data.total_pages}, offset: ${data.offset}, limit: ${data.limit})`);
      
      // Update totalPages from response
      totalPages = data.total_pages;
      
      allTrips.push(...data.trips);
      
      // Move to next page
      currentPage++;
    } catch (error) {
      const cause = (error as any).cause;
      if (cause && (cause.code === 'ECONNREFUSED' || cause.code === 'ENOTFOUND' || cause.code === 'ETIMEDOUT')) {
        console.error(`[FETCH] Fetch failed: ${cause.code} - Cannot connect to ${TRIPS_BASE_URL}`);
      } else if (error instanceof Error) {
        console.error(`[FETCH] Fetch failed: ${error.message}`);
      } else {
        console.error(`[FETCH] Fetch failed: ${error}`);
      }
      throw error;
    }
  }
  
  console.log(`[FETCH] Total trips fetched across all pages: ${allTrips.length}`);
  return allTrips;
}
