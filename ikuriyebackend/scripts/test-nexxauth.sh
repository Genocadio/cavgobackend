#!/usr/bin/env bash
set -euo pipefail

# ─────────────────────────────────────────────────────────────────────────────
#  Nexxauth end-to-end smoke test: register → login → verify token → sync user
#
#  Reads everything from the backend's .env (the same vars the backend needs):
#    NEXXAUTH_BASE_URL          fixed base URL incl. platform slug (required)
#    NEXXAUTH_ORGANISATION_ID   numeric org id — optional here: when unset it is
#                               auto-discovered from the login token's orgId claim
#    NEXXAUTH_CLIENT_ID         SERVER client id (X-Client-Id header) (required)
#    NEXXAUTH_CLIENT_TOKEN      SERVER static token (nx_…) (required)
#    NEXXAUTH_PUBLIC_KEY        org public key (base64 DER SPKI) — optional;
#                               if unset the active key is fetched from the
#                               public keys endpoint for the signature check
#
#  Usage: bash scripts/test-nexxauth.sh
#  Requires: curl, jq, openssl, python3, base64
# ─────────────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${NEXXAUTH_ENV_FILE:-$SCRIPT_DIR/../.env}"

if [[ ! -f "$ENV_FILE" ]]; then
    echo "❌ .env not found at $ENV_FILE" >&2
    exit 1
fi

# ── load .env (plain KEY=VALUE lines, # comments) ───────────────────────────
# Source directly (process substitution is unreliable in some shells). Errors
# from non-assignment lines are tolerated; required vars are validated below.
set +e +u -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set -e -u +a

BASE="${NEXXAUTH_BASE_URL%/}"
ORG_ID="${NEXXAUTH_ORGANISATION_ID:-}"
CLIENT_ID="${NEXXAUTH_CLIENT_ID}"
CLIENT_TOKEN="${NEXXAUTH_CLIENT_TOKEN}"
PUBLIC_KEY="${NEXXAUTH_PUBLIC_KEY:-}"

for var in NEXXAUTH_BASE_URL NEXXAUTH_CLIENT_ID NEXXAUTH_CLIENT_TOKEN; do
    if [[ -z "${!var}" ]]; then
        echo "❌ $var is not set in $ENV_FILE" >&2
        exit 1
    fi
done

TS="$(date +%s)"
TEST_EMAIL="nexx-test-${TS}@ikuriye.test"
TEST_PASSWORD="${NEXXAUTH_TEST_PASSWORD:-NexxTestPass!2026}"

echo "🔎 Nexxauth smoke test"
echo "   base:   $BASE"
echo "   org id: $ORG_ID"
echo "   email:  $TEST_EMAIL"
echo

# ── helpers ──────────────────────────────────────────────────────────────────

# The SERVER client always authenticates (docs §3) — every request carries its
# static token + X-Client-Id, exactly like the backend's NexxauthClient.
# call METHOD URL [JSON_BODY]  → sets HTTP_CODE + HTTP_BODY
call() {
    local method="$1" url="$2" body="${3:-}"
    local args=(-sS -X "$method" "$url"
                -H "X-Client-Id: $CLIENT_ID"
                -H "Authorization: Bearer $CLIENT_TOKEN"
                -H "Content-Type: application/json")
    if [[ -n "$body" ]]; then args+=(-d "$body"); fi
    local out
    out="$(curl "${args[@]}" -w $'\n%{http_code}')"
    HTTP_CODE="${out##*$'\n'}"
    HTTP_BODY="${out%$'\n'*}"
}

err_msg() { # prints server error message from HTTP_BODY (or raw body)
    jq -r '.message // .error // empty' <<<"$HTTP_BODY" 2>/dev/null | grep -v '^$' || echo "$HTTP_BODY"
}

decode_b64url() { # base64url → raw bytes (any padding)
    python3 -c "import sys,base64;sys.stdout.buffer.write(base64.urlsafe_b64decode(sys.argv[1]+'='*(-len(sys.argv[1])%4)))" "$1"
}

# ── 1. Register ──────────────────────────────────────────────────────────────
echo "── 1. Register ─────────────────────────────────────────────"
REG_BODY="$(jq -nc --arg fn "Nexx" --arg ln "Test" --arg em "$TEST_EMAIL" --arg pw "$TEST_PASSWORD" \
    '{firstName:$fn,lastName:$ln,email:$em,password:$pw}')"
call POST "$BASE/auth/register" "$REG_BODY"
if [[ "$HTTP_CODE" == "201" ]]; then
    echo "✅ registered — user id: $(jq -r '.user.id' <<<"$HTTP_BODY")"
elif [[ "$HTTP_CODE" == "409" ]]; then
    echo "ℹ️  already exists — falling back to login"
else
    echo "❌ register failed ($HTTP_CODE): $(err_msg)" >&2
    exit 1
fi

# ── 2. Login ─────────────────────────────────────────────────────────────────
echo "── 2. Login ─────────────────────────────────────────────────"
LOGIN_BODY="$(jq -nc --arg id "$TEST_EMAIL" --arg pw "$TEST_PASSWORD" \
    '{identifier:$id,identifierType:"EMAIL",authType:"PASSWORD",password:$pw}')"
call POST "$BASE/auth/login" "$LOGIN_BODY"
if [[ "$HTTP_CODE" != "200" ]]; then
    echo "❌ login failed ($HTTP_CODE): $(err_msg)" >&2
    exit 1
fi
ACCESS_TOKEN="$(jq -r '.accessToken' <<<"$HTTP_BODY")"
REFRESH_TOKEN="$(jq -r '.refreshToken' <<<"$HTTP_BODY")"
USER_ID="$(jq -r '.user.id' <<<"$HTTP_BODY")"
echo "✅ logged in — user id: $USER_ID, roles: $(jq -c '.user.roles' <<<"$HTTP_BODY")"

# The org is resolved through the client for auth; the numeric org id used by
# the org API lives in the token's orgId claim. Discover it when not configured.
if [[ -z "$ORG_ID" ]]; then
    ORG_ID="$(decode_b64url "$(cut -d. -f2 <<<"$ACCESS_TOKEN")" | jq -r '.orgId')"
    echo "ℹ️  NEXXAUTH_ORGANISATION_ID not set in .env — discovered orgId $ORG_ID from the token"
fi

# ── 3. Verify token (claims + RS256 signature) ───────────────────────────────
echo "── 3. Verify token (claims + RS256 signature) ───────────────"
H="$(cut -d. -f1 <<<"$ACCESS_TOKEN")"
P="$(cut -d. -f2 <<<"$ACCESS_TOKEN")"
S="$(cut -d. -f3 <<<"$ACCESS_TOKEN")"
CLAIMS="$(decode_b64url "$P" | python3 -c "import sys,json;print(json.dumps(json.load(sys.stdin)))")"

ISS="$(jq -r '.iss' <<<"$CLAIMS")"
TYPE="$(jq -r '.type' <<<"$CLAIMS")"
TOKEN_SUB="$(jq -r '.sub' <<<"$CLAIMS")"
echo "   sub: $TOKEN_SUB   orgId: $(jq -r '.orgId' <<<"$CLAIMS")   orgSlug: $(jq -r '.orgSlug' <<<"$CLAIMS")"
echo "   roles: $(jq -r '.roles | join(", ")' <<<"$CLAIMS")   type: $TYPE   iss: $ISS"

[[ "$ISS" == "nexxauth" ]]   || { echo "❌ unexpected iss: $ISS" >&2; exit 1; }
[[ "$TYPE" == "org-access" ]] || { echo "❌ unexpected type: $TYPE" >&2; exit 1; }
[[ "$TOKEN_SUB" == "$USER_ID" ]] || { echo "❌ token sub ($TOKEN_SUB) != login user id ($USER_ID)" >&2; exit 1; }
echo "✅ claims OK (sub matches login user, iss/type correct)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
printf '%s.%s' "$H" "$P" > "$TMP/token-data"
decode_b64url "$S" > "$TMP/token-sig"

if [[ -n "$PUBLIC_KEY" ]]; then
    echo "$PUBLIC_KEY" | base64 -d > "$TMP/pub.der"
    echo "ℹ️  using NEXXAUTH_PUBLIC_KEY from .env"
else
    curl -sS "$BASE/organisations/$ORG_ID/keys" \
        | jq -r '.[] | select(.active == true) | .publicKey' \
        | base64 -d > "$TMP/pub.der"
    echo "ℹ️  NEXXAUTH_PUBLIC_KEY unset — fetched active key from the public keys endpoint"
fi
openssl pkey -inform DER -pubin -in "$TMP/pub.der" -out "$TMP/pub.pem" 2>/dev/null
if openssl dgst -sha256 -verify "$TMP/pub.pem" -signature "$TMP/token-sig" "$TMP/token-data" >/dev/null 2>&1; then
    echo "✅ RS256 signature verified against the org public key"
else
    echo "❌ signature verification FAILED" >&2
    exit 1
fi

# ── 4. Sync user (SERVER client, from the verified token's sub) ──────────────
echo "── 4. Sync user (SERVER client, from verified token sub) ────"
call GET "$BASE/organisations/$ORG_ID/users/$TOKEN_SUB"
if [[ "$HTTP_CODE" != "200" ]]; then
    echo "❌ sync failed ($HTTP_CODE): $(err_msg)" >&2
    exit 1
fi
echo "   id: $(jq -r '.id' <<<"$HTTP_BODY")   firstName: $(jq -r '.firstName' <<<"$HTTP_BODY")   email: $(jq -r '.email' <<<"$HTTP_BODY")   enabled: $(jq -r '.enabled' <<<"$HTTP_BODY")"
echo "   roles: $(jq -r '.roles | join(", ")' <<<"$HTTP_BODY")"
echo "✅ user synced from Nexxauth (id from token matches the org API)"

# ── 5. Logout (revoke the refresh token) ─────────────────────────────────────
echo "── 5. Logout (revoke refresh token) ─────────────────────────"
LOGOUT_BODY="$(jq -nc --arg rt "$REFRESH_TOKEN" '{refreshToken:$rt}')"
call POST "$BASE/auth/logout" "$LOGOUT_BODY"
if [[ "$HTTP_CODE" == "204" || "$HTTP_CODE" == "200" ]]; then
    echo "✅ refresh token revoked"
else
    echo "⚠️  logout returned $HTTP_CODE: $(err_msg)"
fi

echo
echo "🎉 All Nexxauth checks passed."
echo "   Test user: $TEST_EMAIL (id $USER_ID) — delete it in the console if unwanted."
if [[ -z "${NEXXAUTH_ORGANISATION_ID:-}" ]]; then
    echo "⚠️  .env is missing NEXXAUTH_ORGANISATION_ID — the backend needs it; add: NEXXAUTH_ORGANISATION_ID=$ORG_ID"
fi
if [[ -z "$PUBLIC_KEY" ]]; then
    echo "⚠️  .env is missing NEXXAUTH_PUBLIC_KEY — the backend fails to start without it; paste the active key from GET $BASE/organisations/$ORG_ID/keys"
fi
