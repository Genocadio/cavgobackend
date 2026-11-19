# Application superuser (`SUPER_ADMIN`)

`SUPER_ADMIN` is the CAVGO application-level superuser. It is a **global token role**
only: a superuser belongs to no company, has no `company_user` membership, and is
never created by the access-request queues.

## What it can do

| Area | Capability |
|---|---|
| Companies | **Only** `SUPER_ADMIN` may `POST /main/companies`, `PUT /main/companies/{id}`, `DELETE /main/companies/{id}`. Reads stay open to any authenticated user. |
| Company scope | Bypasses every `CompanyScopeGuard.requireCompanyScope*` check — can read/manage vehicles, staff, driver requests, company and admin access requests in any company. |
| Company admin gate | `requireCompanyAdminScope` accepts `SUPER_ADMIN` without a local ADMIN membership, so all `/main/company-admin/companies/{companyId}/**` endpoints are available (list/approve/reject admin requests, list/update/remove staff). |
| Admin-management policy | May assign any company role (including `ADMIN`/`SUPERVISOR`), remove supervisors, and change/remove the last company admin. Ordinary company admins keep the existing restrictions. |
| Direct staff writes | `POST /main/staff` and `PUT /main/staff/{id}` are permitted. When the caller is `SUPER_ADMIN` and supplies `nexxauthUserId` + `role`, the same role is also granted in Nexxauth (preserving global roles such as `CUSTOMER`). |
| adminaggregate / ikuriyebackend | Already treat `SUPER_ADMIN` as an admin and bypass company scope — no changes needed there. |

`SUPER_ADMIN` cannot be stored as a `company_user.role`. It is deliberately not a
membership role; attempting to assign it through staff or role-management
endpoints is rejected.

## Provisioning

1. The Nexxauth organisation used by CAVGO must have a role named `super_admin`
   (lowercase; role names are matched case-insensitively). The delivery backend's
   `NEXXAUTH_INTEGRATION.md` already lists `SUPER_ADMIN` among the five roles to
   provision in the organisation.
2. Assign that role to the intended user in Nexxauth (server-client org API, the
   `assignRole` GraphQL mutation, or however org roles are managed). Role names
   from the token are mapped to `ROLE_SUPER_ADMIN` by
   `NexxauthJwtAuthenticationFilter`.
3. The user signs in again so the JWT carries `super_admin`.
4. On the first request, the JWT filter mirrors the identity into cavgomain's
   local `users` row with no company and no role (`UserService.syncUser` never
   derives company/role from the token). This is expected and sufficient.

## Bootstrapping the first company admin

1. `POST /main/companies` as `SUPER_ADMIN` → save the returned `companyCode`.
2. The intended admin registers in `company.gocavgo.com` with that code, which
   creates a `PENDING` admin-access request.
3. `SUPER_ADMIN` lists and approves it:
   ```
   GET  /main/company-admin/companies/{companyId}/admin-access-requests
   POST /main/company-admin/companies/{companyId}/admin-access-requests/{requestId}/approve
   ```
   Approval writes both sides: Nexxauth role `admin` **and** an active local
   `ADMIN` membership. The user signs in again and can now use the admin portal
   and approve everyone else.

Self-approval is still blocked in the request services; a superuser cannot
approve their own admin-access request.
