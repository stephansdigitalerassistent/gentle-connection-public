#!/usr/bin/env bash
set -euo pipefail

# scripts/bootstrap-owner.sh: Bootstrap the initial owner for a device.
#
# Looks up the Google-authenticated caregiver and registers their UID in
# devices/<deviceId>/access/owners using Application Default Credentials.
#
# Usage:
#   FIREBASE_PROJECT_ID=my-project scripts/bootstrap-owner.sh [--dry-run] <deviceId> <google-email>

PROJECT_ID="${FIREBASE_PROJECT_ID:-YOUR_PROJECT_ID}"

DRY_RUN=false
POSITIONAL=()

for arg in "$@"; do
  case "$arg" in
    --dry-run)
      DRY_RUN=true
      ;;
    -h|--help)
      echo "Usage: $0 [--dry-run] <deviceId> <google-email>"
      exit 0
      ;;
    *)
      POSITIONAL+=("$arg")
      ;;
  esac
done

if [[ ${#POSITIONAL[@]} -ne 2 ]]; then
  echo "Usage: $0 [--dry-run] <deviceId> <google-email>" >&2
  exit 1
fi

DEVICE_ID="${POSITIONAL[0]}"
EMAIL="${POSITIONAL[1]}"

# Require dependencies
for cmd in gcloud curl jq; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "Error: Required command '$cmd' is not installed or not in PATH." >&2
    exit 1
  fi
done

# Obtain access token via Application Default Credentials
# (NOT gcloud auth print-access-token)
if ! TOKEN=$(gcloud auth application-default print-access-token 2>/dev/null) || [[ -z "$TOKEN" ]]; then
  echo "Error: Failed to obtain Application Default Credentials token." >&2
  echo "Please run: gcloud auth application-default login" >&2
  exit 1
fi

# Step 1: Look up user by email in Identity Toolkit
LOOKUP_URL="https://identitytoolkit.googleapis.com/v1/projects/${PROJECT_ID}/accounts:lookup"
LOOKUP_RESP=$(curl -s -w "\n%{http_code}" -X POST "$LOOKUP_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Goog-User-Project: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d "$(jq -n --arg email "$EMAIL" '{email: [$email]}')")

LOOKUP_HTTP=$(echo "$LOOKUP_RESP" | tail -n1)
LOOKUP_BODY=$(echo "$LOOKUP_RESP" | sed '$d')

if [[ "$LOOKUP_HTTP" -lt 200 || "$LOOKUP_HTTP" -ge 300 ]]; then
  echo "Error looking up user '$EMAIL' (HTTP $LOOKUP_HTTP):" >&2
  echo "$LOOKUP_BODY" | jq -r '.error.message // .' >&2
  exit 1
fi

USER_COUNT=$(echo "$LOOKUP_BODY" | jq -r '.users // [] | length')
if [[ "$USER_COUNT" -ne 1 ]]; then
  echo "Error: Found $USER_COUNT users for email '$EMAIL'. Expected exactly 1." >&2
  echo "Please sign in with Google in the portal first." >&2
  exit 1
fi

OWNER_UID=$(echo "$LOOKUP_BODY" | jq -r '.users[0].localId // empty')
if [[ -z "$OWNER_UID" ]]; then
  echo "Error: No localId found for user '$EMAIL'." >&2
  exit 1
fi

HAS_GOOGLE=$(echo "$LOOKUP_BODY" | jq -r '[.users[0].providerUserInfo[]? | select(.providerId == "google.com")] | length')
if [[ "$HAS_GOOGLE" -eq 0 ]]; then
  echo "Error: User '$EMAIL' (UID: $OWNER_UID) does not have a linked google.com provider." >&2
  echo "Please sign in with Google in the portal first." >&2
  exit 1
fi

# Step 2: Read devices/<deviceId> via Firestore REST
DEVICE_URL="https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/devices/${DEVICE_ID}"
DEVICE_RESP=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Goog-User-Project: ${PROJECT_ID}" \
  "$DEVICE_URL")

DEVICE_HTTP=$(echo "$DEVICE_RESP" | tail -n1)
DEVICE_BODY=$(echo "$DEVICE_RESP" | sed '$d')

if [[ "$DEVICE_HTTP" -eq 404 ]]; then
  echo "Error: Device '$DEVICE_ID' not found in Firestore." >&2
  exit 1
elif [[ "$DEVICE_HTTP" -lt 200 || "$DEVICE_HTTP" -ge 300 ]]; then
  echo "Error reading device '$DEVICE_ID' (HTTP $DEVICE_HTTP):" >&2
  echo "$DEVICE_BODY" | jq -r '.error.message // .' >&2
  exit 1
fi

IS_CAREGIVER=$(echo "$DEVICE_BODY" | jq -r --arg uid "$OWNER_UID" '
  [.fields.caregiverUids.arrayValue.values[]?.stringValue | select(. == $uid)] | length
')

if [[ "$IS_CAREGIVER" -eq 0 ]]; then
  echo "Error: User UID '$OWNER_UID' ($EMAIL) is not in caregiverUids for device '$DEVICE_ID'." >&2
  echo "Please open the link from her phone first, then press 'Als Verwalter anmelden'." >&2
  exit 1
fi

# Step 3: Read devices/<deviceId>/access/owners (404 = none yet)
OWNERS_URL="https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/devices/${DEVICE_ID}/access/owners"
OWNERS_RESP=$(curl -s -w "\n%{http_code}" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Goog-User-Project: ${PROJECT_ID}" \
  "$OWNERS_URL")

OWNERS_HTTP=$(echo "$OWNERS_RESP" | tail -n1)
OWNERS_BODY=$(echo "$OWNERS_RESP" | sed '$d')

if [[ "$OWNERS_HTTP" -eq 404 ]]; then
  EXISTING_UIDS_JSON="[]"
elif [[ "$OWNERS_HTTP" -ge 200 && "$OWNERS_HTTP" -lt 300 ]]; then
  EXISTING_UIDS_JSON=$(echo "$OWNERS_BODY" | jq '[.fields.uids.arrayValue.values[]?.stringValue // empty]')
else
  echo "Error reading access/owners for device '$DEVICE_ID' (HTTP $OWNERS_HTTP):" >&2
  echo "$OWNERS_BODY" | jq -r '.error.message // .' >&2
  exit 1
fi

# Add UID if missing
NEW_UIDS_JSON=$(echo "$EXISTING_UIDS_JSON" | jq --arg uid "$OWNER_UID" '
  if index($uid) then . else . + [$uid] end
')

NOW_UTC=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

PATCH_PAYLOAD=$(jq -n \
  --argjson uids "$NEW_UIDS_JSON" \
  --arg now "$NOW_UTC" '
  {
    "fields": {
      "uids": {
        "arrayValue": {
          "values": [ $uids[] | { "stringValue": . } ]
        }
      },
      "updatedAt": {
        "timestampValue": $now
      }
    }
  }
')

if [[ "$DRY_RUN" == true ]]; then
  echo "[dry-run] Verified user $EMAIL ($OWNER_UID) and caregiver status for device $DEVICE_ID."
  echo "[dry-run] Would write to: ${OWNERS_URL}?updateMask.fieldPaths=uids&updateMask.fieldPaths=updatedAt"
  echo "[dry-run] Payload:"
  echo "$PATCH_PAYLOAD" | jq .
  echo ""
  echo "[dry-run] Resulting owner UIDs:"
  echo "$NEW_UIDS_JSON" | jq -r '.[]'
  exit 0
fi

# Step 4: Write with PATCH
PATCH_URL="${OWNERS_URL}?updateMask.fieldPaths=uids&updateMask.fieldPaths=updatedAt"
WRITE_RESP=$(curl -s -w "\n%{http_code}" -X PATCH "$PATCH_URL" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Goog-User-Project: ${PROJECT_ID}" \
  -H "Content-Type: application/json" \
  -d "$PATCH_PAYLOAD")

WRITE_HTTP=$(echo "$WRITE_RESP" | tail -n1)
WRITE_BODY=$(echo "$WRITE_RESP" | sed '$d')

if [[ "$WRITE_HTTP" -lt 200 || "$WRITE_HTTP" -ge 300 ]]; then
  echo "Error writing owners document (HTTP $WRITE_HTTP):" >&2
  echo "$WRITE_BODY" | jq -r '.error.message // .' >&2
  exit 1
fi

# Step 5: Print resulting uid list
echo "Successfully updated owner access for device $DEVICE_ID."
echo "Owner UIDs:"
echo "$NEW_UIDS_JSON" | jq -r '.[]'
