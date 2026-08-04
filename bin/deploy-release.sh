#!/usr/bin/env bash
# Deploy all condensed-data artifacts to Maven Central.
# Run from the repo root. Requires:
#   - GPG key for signing (non-expired key in gpg --list-secret-keys)
#   - ~/.m2/settings.xml with <id>ossrh</id> server credentials
#   - JMC core SNAPSHOTs installed locally (for the jmc-test profile):
#       cd /tmp/jmc && git clone --depth=1 --branch sap https://github.com/parttimenerd/jmc.git .
#       cd core && mvn install -DskipTests -Dmaven.javadoc.skip=true
#
# Usage:
#   ./bin/deploy-release.sh            # deploy current version
#   ./bin/deploy-release.sh 0.1.4      # bump to given version, then deploy
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REPO_ROOT"

if [[ $# -ge 1 ]]; then
    NEW_VERSION="$1"
    echo "==> Bumping version to $NEW_VERSION"
    sed -i'' "s|<version>[0-9]*\.[0-9]*\.[0-9]*</version>|<version>$NEW_VERSION</version>|" pom.xml
    # Verify exactly one version in the project block was changed
    COUNT=$(grep -c "<version>$NEW_VERSION</version>" pom.xml)
    if [[ "$COUNT" -ne 1 ]]; then
        echo "ERROR: expected 1 version line, found $COUNT — check pom.xml manually"
        exit 1
    fi
    echo "    pom.xml updated to $NEW_VERSION"
fi

CURRENT_VERSION=$(grep -m1 '<version>' pom.xml | sed 's|.*<version>\(.*\)</version>.*|\1|')
echo "==> Deploying condensed-data $CURRENT_VERSION"
echo ""

echo "--- [1/3] Main jar + sources + javadoc ---"
mvn -Ppublication deploy -DskipTests -P'!jmc-test'
echo ""

echo "--- [2/3] -jmc classifier jar ---"
mvn -Pjmc-publication deploy -DskipTests -P'!jmc-test'
echo ""

echo "--- [3/3] -reader classifier jar ---"
mvn -Preader-publication deploy -DskipTests -P'!jmc-test'
echo ""

echo "==> All artifacts deployed for condensed-data $CURRENT_VERSION"
echo ""
echo "Next steps:"
echo "  1. Wait ~10-30 min for Maven Central to sync"
echo "  2. In parttimenerd/jmc (sap branch), update:"
echo "       releng/third-party/pom.xml  ->  <condensed-data.version>$CURRENT_VERSION</condensed-data.version>"
echo "       platform-definition-*.target -> <unit id=\"me.bechberger.condensed.data\" version=\"$CURRENT_VERSION\"/>"
echo "  3. Rebuild the p2 repo and refresh target platform in Eclipse"
