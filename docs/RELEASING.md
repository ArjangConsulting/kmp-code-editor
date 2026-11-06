# Releasing

The first release is `0.1.0`, configured through Release Please's `initial-version`. That setting
only applies while the manifest has no entry for `.`; a `0.0.0` entry would be treated as an
already-released version and a first `fix:` would produce `0.0.1`. The manifest therefore stays
empty (`{}`) until the first release PR adds `"." : "0.1.0"`. Builds take `VERSION_NAME` from the
release tag; do not edit versions by hand or create a changelog file.

Before `1.0.0`, features and breaking changes bump the minor version, and fixes bump the patch
version. For example, a breaking change after `0.1.0` produces `0.2.0`. Use `feat!:` or a
`BREAKING CHANGE:` footer and explain consumer migration in the release PR. Update the reviewed
API baseline with `./gradlew :code-editor:updateKotlinAbi`; CI checks it with `checkKotlinAbi`.

## Credentials

Store these six secrets in this repository's GitHub environment named `secrets`:

| Secret | Value |
| --- | --- |
| `MAVEN_CENTRAL_USERNAME` | Username from a Central Portal publishing user token. |
| `MAVEN_CENTRAL_PASSWORD` | Password from the same publishing user token. |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored private GPG signing key. |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | Passphrase that unlocks that key. |
| `RELEASE_PLEASE_APP_CLIENT_ID` | Client ID of the release GitHub App. |
| `RELEASE_PLEASE_APP_PRIVATE_KEY` | PEM private key of that App. |

The Central Portal login password is not a publishing token. Generate or retrieve the user token
through the [Central Portal](https://central.sonatype.com/usertoken). Confirm that the account has
the verified `com.arjangconsulting` namespace before publishing. The signing key's public key must
be available through a public keyserver supported by Maven Central.

The GitHub App must be installed for `ArjangConsulting/kmp-code-editor` with repository permissions
Contents write, Pull requests write, and Workflows write. Token creation requests these permissions
explicitly. Release Please requires the App token so its release PR starts CI; it fails if that
token is unavailable. Both release jobs read the same `secrets` environment.

Use GitHub's environment settings or `gh secret set NAME --env secrets --repo
ArjangConsulting/kmp-code-editor` to enter values through the hidden prompt. GitHub cannot export
secret values from another repository. For existing key files, pass standard input directly:

```bash
gh secret set RELEASE_PLEASE_APP_PRIVATE_KEY --env secrets --repo ArjangConsulting/kmp-code-editor < "$RELEASE_APP_KEY_FILE"
gpg --armor --export-secret-keys "$SIGNING_KEY_FINGERPRINT" | gh secret set SIGNING_IN_MEMORY_KEY --env secrets --repo ArjangConsulting/kmp-code-editor
```

Keep private keys, tokens, and passphrases outside the checkout. Do not print them in logs or put
them in command arguments.

## Initial release preparation

1. Run the local CI checks from a clean checkout:

   ```bash
   bash .github/scripts/verify.sh
   ```

2. Complete the native input, clipboard, composition, and accessibility checks in
   [verification/README.md](../verification/README.md). Unit tests do not establish native keyboard
   behavior or screen-reader task completion.
3. Configure the six environment secrets and the App installation, then run:

   ```bash
   gh workflow run release-preflight.yml --repo ArjangConsulting/kmp-code-editor -f version=0.1.0
   ```

   Preflight runs independent jobs for App access, a read-only Central token check, and the full CI
   task list plus signing. One credential failure does not skip the other jobs. The Central check
   uses the authenticated deployments-list endpoint; it does not upload a deployment or establish
   namespace ownership. See the [Publisher API](https://central.sonatype.com/api-doc).
   Signed publications are written to an isolated temporary Maven repository. Checks require all
   five target POMs, publication metadata, source/documentation archives, and signatures for every
   published artifact. Test reports are uploaded as CI evidence; no release artifacts are uploaded
   to Central, and no tag or release is created.
   Secret presence alone verifies no credentials. A passing App job verifies installation token
   creation with the requested permissions; a passing Central job verifies token authentication;
   a passing signing step verifies that Gradle can unlock and use the key. Confirm namespace
   ownership, supported public-keyserver availability, and Central's publication validation in the
   Portal before publishing. Local POM checks do not replace Central validation.
4. Review the results and the generated release content before enabling releases. The maintainer
   controls the repository variable `RELEASES_ENABLED`; leave it unset or false during preparation.
5. Once the maintainer enables releases, dispatch Release Please. Inspect its PR for version
   `0.1.0`, migration notes, and successful CI, then merge the release PR.
   Release Please creates the bare tag, then the publishing workflow checks the tag against the
   manifest, runs verification, and publishes signed artifacts to Maven Central.
6. Confirm the published coordinates resolve from both consuming repositories. Change the
   pre-release direct-to-main rule in `AGENTS.md` to the branch-and-PR workflow after `0.1.0` ships.

For a failed publish, fix its cause and rerun the Release workflow with the existing tag. Maven
Central releases are immutable; never move a published tag or reuse a published version.

## Current readiness evidence

The `0.1.0` [preflight at `7c04f6e`](https://github.com/ArjangConsulting/kmp-code-editor/actions/runs/42304177088)
completed the release checks, ABI checks, authenticated Central read-only request, and signing.
All five publications passed local POM/archive checks with 29 signature files. The six environment
secrets were present. The App installation accepted its identity but token creation failed because
it had Contents and Pull requests write without Workflows write. Add Workflows write to the App
registration, then approve the updated installation permissions and rerun preflight. See
[GitHub's registration guide](https://docs.github.com/en/apps/maintaining-github-apps/modifying-a-github-app-registration)
and [installation approval guide](https://docs.github.com/en/apps/using-github-apps/approving-updated-permissions-for-a-github-app).

The release gate remains unset. `0.1.0` is absent from Maven Central, so Studio's source fallback
must remain. Central token authentication and local signing do not establish the account's verified
`com.arjangconsulting` namespace, public-keyserver availability, or acceptance by Central's
publication validator. Those remain release prerequisites; no deployment was uploaded to test them.
Native evidence and remaining coverage limitations are in
[benchmarks/VERIFICATION.md](../benchmarks/VERIFICATION.md).
