import pathlib
import sys
import xml.etree.ElementTree as xml

repository = pathlib.Path(sys.argv[1]) / "com/arjangconsulting/codeeditor"
version = sys.argv[2]
namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
artifacts = ["code-editor", "code-editor-android", "code-editor-jvm", "code-editor-iosarm64", "code-editor-iossimulatorarm64"]
required_metadata = [
    "groupId", "artifactId", "version", "name", "description", "url",
    "licenses/license/name", "licenses/license/url", "developers/developer/name",
    "scm/url", "scm/connection", "scm/developerConnection",
]
signature_count = 0
for artifact in artifacts:
    directory = repository / artifact / version
    pom = directory / f"{artifact}-{version}.pom"
    document = xml.parse(pom)
    for field in required_metadata:
        element = document.find("/".join(f"m:{part}" for part in field.split("/")), namespace)
        if element is None or not element.text or not element.text.strip():
            raise SystemExit(f"::error::{artifact} POM lacks {field}.")
    if document.find("m:version", namespace).text != version:
        raise SystemExit(f"::error::{artifact} POM version differs from {version}.")
    for classifier in ["sources", "javadoc"]:
        if not (directory / f"{artifact}-{version}-{classifier}.jar").is_file():
            raise SystemExit(f"::error::{artifact} lacks its {classifier} archive.")
    publications = [path for path in directory.iterdir() if path.suffix in {".pom", ".module", ".jar", ".aar", ".klib", ".zip"}]
    if not any(path.suffix in {".jar", ".aar", ".klib"} for path in publications):
        raise SystemExit(f"::error::{artifact} has no library artifact.")
    for path in publications:
        signature = pathlib.Path(str(path) + ".asc")
        if not signature.is_file() or signature.stat().st_size == 0:
            raise SystemExit(f"::error::Missing signature for {path.name}.")
        signature_count += 1
print(f"Verified POM metadata and {signature_count} signatures across all {len(artifacts)} publications.")
