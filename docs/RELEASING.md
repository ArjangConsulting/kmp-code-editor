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

   Preflight checks secret presence, App access and permissions, the full CI task list, and signing
   for all five Maven publications. It does not create a tag, upload artifacts, or publish anything.
   Secret presence does not verify the Central token or namespace; confirm those in the Portal.
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
