import dotenv from 'dotenv';

dotenv.config();

const API_BASE_URL = process.env.API_BASE_URL || 'http://localhost:8080';

export interface ApiVehicle {
  id: string;
  companyId: string;
  companyCode: string;
  plate: string;
  model: string;
  make: string;
  capacity: number;
  connectionStatus: 'ONLINE' | 'OFFLINE';
  operationalStatus: 'AVAILABLE' | 'MAINTENANCE' | 'OUT_OF_SERVICE' | 'OCCUPIED';
  currentLocation: {
    latitude: number;
    longitude: number;
    address: string | null;
    timestamp: string;
    bearing: number | null;
    speed: number | null;
  } | null;
  lastUpdated: string;
}

export interface ApiWorker {
  id: string;
  name: string;
  phone: string;
  email: string;
  licenseNumber: string | null;
  status: 'ACTIVE' | 'INACTIVE' | 'SUSPENDED' | 'PENDING_VERIFICATION';
  role: 'ADMIN' | 'DRIVER' | 'FLEET_MANAGER' | 'SUPERVISOR';
  vehicle: ApiVehicle | null;
}

class ApiClient {
  private baseUrl: string;

  constructor() {
    this.baseUrl = API_BASE_URL;
  }

  private async fetchWithErrorHandling<T>(url: string): Promise<T> {
    try {
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok) {
        if (response.status === 404) {
          return [] as T;
        }
        throw new Error(`API request failed: ${response.status} ${response.statusText}`);
      }

      return await response.json() as T;
    } catch (error) {
      console.error(`Error fetching from ${url}:`, error);
      throw error;
    }
  }

  async getVehiclesByCompany(companyId: string): Promise<ApiVehicle[]> {
    const url = `${this.baseUrl}/internal/api/vehicles/company/${companyId}`;
    return this.fetchWithErrorHandling<ApiVehicle[]>(url);
  }

  async getVehicleById(id: string): Promise<ApiVehicle | null> {
    const url = `${this.baseUrl}/internal/api/vehicles/${id}`;
    try {
      return await this.fetchWithErrorHandling<ApiVehicle>(url);
    } catch (error) {
      return null;
    }
  }

  async getWorkersByCompany(companyId: string): Promise<ApiWorker[]> {
    const url = `${this.baseUrl}/internal/api/workers/company/${companyId}`;
    return this.fetchWithErrorHandling<ApiWorker[]>(url);
  }

  async getWorkerById(id: string): Promise<ApiWorker | null> {
    const url = `${this.baseUrl}/internal/api/workers/${id}`;
    try {
      return await this.fetchWithErrorHandling<ApiWorker>(url);
    } catch (error) {
      return null;
    }
  }

  /**
   * Reverse geocoding: Get address from coordinates
   * Uses the standard API endpoint for reverse geocoding
   */
  async getAddressFromCoordinates(latitude: number, longitude: number): Promise<string | null> {
    try {
      const url = `${this.baseUrl}/internal/api/geocode/reverse?latitude=${latitude}&longitude=${longitude}`;
      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
        },
      });

      if (!response.ok) {
        // If reverse geocoding fails, return null (address is optional)
        return null;
      }

      const data = await response.json() as { address?: string; formatted_address?: string };
      return data.address || data.formatted_address || null;
    } catch (error) {
      // Address is optional, so we silently fail
      return null;
    }
  }
}

export const apiClient = new ApiClient();

