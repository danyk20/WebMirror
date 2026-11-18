#!/usr/bin/env bash
# Stores the release signing key as GitHub Actions secrets for this repository.
# Usage: scripts/setup-release-secrets.sh <keystore> [alias]
# Run it yourself: it asks for the password and never writes it to disk.
set -euo pipefail

# SHA-256 of the release certificate that apps already installed were signed with.
EXPECTED_SHA256="ABA60DE08391E40EF4C41EB6EE630D4AC58F39DD6DBED43F44690EBD4C81EB6A"

KEYSTORE="${1:-}"
ALIAS="${2:-webmirror}"
[[ -n "$KEYSTORE" ]] || { echo "Usage: $0 <keystore> [alias]" >&2; exit 1; }
[[ -f "$KEYSTORE" ]] || { echo "Keystore not found: $KEYSTORE" >&2; exit 1; }

for tool in keytool gh base64; do
    command -v "$tool" >/dev/null || { echo "Missing tool: $tool" >&2; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo "Log in to GitHub first: gh auth login" >&2; exit 1; }
REPO="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

echo "Keystore: $KEYSTORE (alias $ALIAS)"
echo "Repository: $REPO"
read -rs -p "Keystore password: " WEBMIRROR_PW; echo
export WEBMIRROR_PW

# Fails here, before anything is uploaded, if the password doesn't open the key.
listing="$(keytool -list -keystore "$KEYSTORE" -storepass:env WEBMIRROR_PW -alias "$ALIAS" 2>&1)" \
    || { echo "The password doesn't open $KEYSTORE with alias $ALIAS." >&2; exit 1; }

actual="$(printf '%s\n' "$listing" | sed -n 's/.*SHA-256): *//p;s/.*SHA256: *//p' | head -n 1 | tr -d ': ' | tr 'a-f' 'A-F')"
if [[ "$actual" != "$EXPECTED_SHA256" ]]; then
    echo "WARNING: the key fingerprint is ${actual:-unknown}," >&2
    echo "         expected $EXPECTED_SHA256." >&2
    echo "         Releases signed with a different key can't update existing installs." >&2
    read -r -p "Upload it anyway? [y/N] " answer
    [[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "Aborted." >&2; exit 1; }
fi

echo "Uploading secrets to ${REPO}..."
base64 < "$KEYSTORE" | gh secret set SIGNING_KEYSTORE_BASE64 --repo "$REPO"
# printf is a shell builtin, so the password never appears in the process list.
printf '%s' "$WEBMIRROR_PW" | gh secret set SIGNING_STORE_PASSWORD --repo "$REPO"
# PKCS12 keystores use the same password for the key.
printf '%s' "$WEBMIRROR_PW" | gh secret set SIGNING_KEY_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set SIGNING_KEY_ALIAS --repo "$REPO"
unset WEBMIRROR_PW

echo
gh secret list --repo "$REPO"
echo
echo "Done. Keep a backup of the keystore and the password in a safe place."
