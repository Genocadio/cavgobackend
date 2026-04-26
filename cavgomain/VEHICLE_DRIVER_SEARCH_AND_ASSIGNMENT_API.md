# Vehicle and Driver Search + Assignment API

This document is integration-ready and includes expected JSON payloads.

## 1. Search Vehicles by Company (Paginated)

Endpoint:
- GET /main/vehicles/company/{companyId}

Query parameters:
- timeLimit (optional, ISO date-time)
- plate (optional, case-insensitive partial match on licensePlate)
- status (optional, enum: AVAILABLE, MAINTENANCE, OUT_OF_SERVICE, OCCUPIED)
- driverName (optional, case-insensitive partial match on active driver first/last/full name)
- page (optional, default: 0)
- size (optional, default: 20)

Example request:
- GET /main/vehicles/company/12?page=0&size=10&plate=KDN&status=OCCUPIED&driverName=John

Expected response JSON:
```json
{
  "content": [
    {
      "id": 102,
      "companyId": 12,
      "companyName": "Cavgo Logistics",
      "make": "Isuzu",
      "model": "NQR",
      "capacity": 12,
      "licensePlate": "KDN 547P",
      "vehicleType": "TRUCK",
      "status": "AVAILABLE",
      "createdAt": "2026-04-15T10:20:30",
      "updatedAt": "2026-04-16T08:00:00",
      "driver": {
        "id": 55,
        "companyId": 12,
        "companyName": "Cavgo Logistics",
        "firstName": "John",
        "lastName": "Kamau",
        "email": "john.kamau@example.com",
        "phone": "+254700000000",
        "status": "ACTIVE",
        "dateOfBirth": "1992-03-10",
        "address": "Nairobi",
        "role": "DRIVER",
        "licenseNumber": "DL-90001",
        "licenseExpiry": "2028-06-30",
        "createdAt": "2026-01-10T09:00:00",
        "updatedAt": "2026-04-12T07:00:00",
        "vehicle": null
      },
      "initialPassword": null,
      "lastLocation": null,
      "isOnline": true,
      "lastOnlineAt": "2026-04-16T09:14:21"
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 10,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "size": 10,
  "number": 0,
  "sort": { "empty": true, "sorted": false, "unsorted": true },
  "first": true,
  "numberOfElements": 1,
  "empty": false
}
```

## 2. Fetch Company Drivers (Paginated + Name Search)

Endpoint:
- GET /main/staff/company/{companyId}/drivers

Query parameters:
- timeLimit (optional, ISO date-time)
- query (optional, case-insensitive partial match on first/last/full name)
- page (optional, default: 0)
- size (optional, default: 20)

Example request:
- GET /main/staff/company/12/drivers?page=0&size=20&query=kamau

Expected response JSON:
```json
{
  "content": [
    {
      "id": 55,
      "companyId": 12,
      "companyName": "Cavgo Logistics",
      "firstName": "John",
      "lastName": "Kamau",
      "email": "john.kamau@example.com",
      "phone": "+254700000000",
      "status": "ACTIVE",
      "dateOfBirth": "1992-03-10",
      "address": "Nairobi",
      "role": "DRIVER",
      "licenseNumber": "DL-90001",
      "licenseExpiry": "2028-06-30",
      "createdAt": "2026-01-10T09:00:00",
      "updatedAt": "2026-04-12T07:00:00",
      "vehicle": {
        "id": 102,
        "companyId": 12,
        "companyName": "Cavgo Logistics",
        "make": "Isuzu",
        "model": "NQR",
        "capacity": 12,
        "licensePlate": "KDN 547P",
        "vehicleType": "TRUCK",
        "status": "AVAILABLE",
        "createdAt": "2026-04-15T10:20:30",
        "updatedAt": "2026-04-16T08:00:00",
        "driver": null,
        "initialPassword": null,
        "lastLocation": null,
        "isOnline": true,
        "lastOnlineAt": "2026-04-16T09:14:21"
      }
    }
  ],
  "pageable": {
    "pageNumber": 0,
    "pageSize": 20,
    "sort": { "empty": true, "sorted": false, "unsorted": true },
    "offset": 0,
    "paged": true,
    "unpaged": false
  },
  "totalElements": 1,
  "totalPages": 1,
  "last": true,
  "size": 20,
  "number": 0,
  "sort": { "empty": true, "sorted": false, "unsorted": true },
  "first": true,
  "numberOfElements": 1,
  "empty": false
}
```

## 3. Assign Driver to Vehicle

### 3.1 Path-based assignment

Endpoint:
- POST /main/vehicles/{vehicleId}/assign/{driverId}?notes={optional}

Example request:
- POST /main/vehicles/102/assign/55?notes=Assigned%20for%20morning%20route

Expected success response JSON:
```json
{
  "id": 9001,
  "vehicleId": 102,
  "licensePlate": "KDN 547P",
  "driverId": 55,
  "driverName": "John Kamau",
  "assignedDate": "2026-04-16T10:00:00",
  "unassignedDate": null,
  "status": "ACTIVE",
  "notes": "Assigned for morning route",
  "createdAt": "2026-04-16T10:00:00",
  "updatedAt": "2026-04-16T10:00:00"
}
```

### 3.2 DTO-based assignment

Endpoint:
- POST /main/vehicles/assign

Expected request JSON:
```json
{
  "vehicleId": 102,
  "driverId": 55,
  "status": "ACTIVE",
  "notes": "Assigned via dispatch console"
}
```

Expected success response JSON:
```json
{
  "id": 9002,
  "vehicleId": 102,
  "licensePlate": "KDN 547P",
  "driverId": 55,
  "driverName": "John Kamau",
  "assignedDate": "2026-04-16T10:05:00",
  "unassignedDate": null,
  "status": "ACTIVE",
  "notes": "Assigned via dispatch console",
  "createdAt": "2026-04-16T10:05:00",
  "updatedAt": "2026-04-16T10:05:00"
}
```

## 4. Swap Driver Assignment

### 4.1 Swap by vehicle

Endpoint:
- PUT /main/vehicles/{vehicleId}/swap/{newDriverId}

Example request:
- PUT /main/vehicles/102/swap/77

Expected success response JSON:
```json
{
  "id": 9010,
  "vehicleId": 102,
  "licensePlate": "KDN 547P",
  "driverId": 77,
  "driverName": "Mary Wanjiku",
  "assignedDate": "2026-04-16T11:00:00",
  "unassignedDate": null,
  "status": "ACTIVE",
  "notes": "Full swap: Driver 77 from vehicle 205 to vehicle 102, Driver 55 unassigned",
  "createdAt": "2026-04-16T11:00:00",
  "updatedAt": "2026-04-16T11:00:00"
}
```

### 4.2 Swap by current driver

Endpoint:
- PUT /main/vehicles/driver/{currentDriverId}/swap/{newDriverId}

Example request:
- PUT /main/vehicles/driver/55/swap/77

Expected success response JSON:
```json
{
  "id": 9011,
  "vehicleId": 102,
  "licensePlate": "KDN 547P",
  "driverId": 77,
  "driverName": "Mary Wanjiku",
  "assignedDate": "2026-04-16T11:15:00",
  "unassignedDate": null,
  "status": "ACTIVE",
  "notes": "Driver swapped from driver ID: 55",
  "createdAt": "2026-04-16T11:15:00",
  "updatedAt": "2026-04-16T11:15:00"
}
```

## 5. Remove Driver from Vehicle (Unassign)

Endpoint:
- DELETE /main/vehicles/{vehicleId}/unassign

Example request:
- DELETE /main/vehicles/102/unassign

Expected success response JSON:
```json
{
  "id": 9011,
  "vehicleId": 102,
  "licensePlate": "KDN 547P",
  "driverId": 77,
  "driverName": "Mary Wanjiku",
  "assignedDate": "2026-04-16T11:15:00",
  "unassignedDate": "2026-04-16T12:00:00",
  "status": "COMPLETED",
  "notes": "Driver swapped from driver ID: 55",
  "createdAt": "2026-04-16T11:15:00",
  "updatedAt": "2026-04-16T12:00:00"
}
```

## 6. Current Implementation Notes

- Vehicle list fetches are ordered with OCCUPIED vehicles first by default.
- The company vehicle search endpoint supports optional status filtering via status query parameter.
- Legacy non-paginated driver search endpoint has been removed.
- Both paginated endpoints return Spring Page JSON with content and metadata.
- Current assignment behavior in service is preserved:
  - Path-based assign keeps vehicle status as AVAILABLE.
  - DTO-based assign sets vehicle status to OCCUPIED.
  - Swap endpoints keep vehicle status as AVAILABLE.
