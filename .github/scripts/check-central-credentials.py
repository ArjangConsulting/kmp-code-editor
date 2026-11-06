import base64
import os
import urllib.error
import urllib.request

credentials = f"{os.environ['MAVEN_CENTRAL_USERNAME']}:{os.environ['MAVEN_CENTRAL_PASSWORD']}"
authorization = base64.b64encode(credentials.encode()).decode()
request = urllib.request.Request(
    "https://central.sonatype.com/api/v1/publisher/deployments?size=1",
    headers={"Authorization": f"Bearer {authorization}"},
)
try:
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status != 200:
            raise SystemExit(f"::error::Central credential check returned HTTP {response.status}.")
except urllib.error.HTTPError as error:
    raise SystemExit(f"::error::Central credential check returned HTTP {error.code}.") from None
except urllib.error.URLError:
    raise SystemExit("::error::Central credential check could not reach the Publisher API.") from None
print("Central accepted the publishing token for a read-only API request.")
print("Namespace ownership and publication validation still require Central Portal review.")
