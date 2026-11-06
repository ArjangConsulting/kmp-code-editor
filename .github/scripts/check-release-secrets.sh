set -euo pipefail

missing=0
for name in MAVEN_CENTRAL_USERNAME MAVEN_CENTRAL_PASSWORD SIGNING_IN_MEMORY_KEY SIGNING_IN_MEMORY_KEY_PASSWORD "$@"; do
  if [ -z "${!name:-}" ]; then
    echo "::error::$name is not set in the secrets environment."
    missing=1
  fi
done
exit "$missing"
