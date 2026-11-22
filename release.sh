#!/bin/zsh
# Veröffentlicht die aktuelle Version als GitHub-Release – installierte Apps aktualisieren sich daraus selbst.
#   1. In app/build.gradle.kts versionCode erhöhen und versionName anpassen
#   2. ./release.sh "Was ist neu (ein, zwei Sätze für die Familie)"
set -euo pipefail
cd "$(dirname "$0")"
NOTES="${1:?Bitte Änderungstext angeben: ./release.sh \"Was ist neu\"}"
CODE=$(grep -E '^\s*versionCode\s*=' app/build.gradle.kts | grep -oE '[0-9]+')
NAME=$(grep -E '^\s*versionName\s*=' app/build.gradle.kts | grep -oE '"[^"]+"' | tr -d '"')
TAG="v$NAME"
[[ -z "$(git status --porcelain)" ]] || { echo "Erst alle Änderungen committen."; exit 1; }
git rev-parse "$TAG" >/dev/null 2>&1 && { echo "Version $NAME gibt es schon – versionCode/versionName erhöhen."; exit 1; }

./build-apk.sh
APK="Familienplaner-$NAME.apk"
cp build/Familienplaner.apk "build/$APK"
SHA=$(shasum -a 256 "build/$APK" | cut -d' ' -f1)
MINSDK=$(grep -E '^\s*minSdk\s*=' app/build.gradle.kts | grep -oE '[0-9]+' | head -1)
python3 - "$CODE" "$NAME" "$APK" "$SHA" "$NOTES" "$MINSDK" > build/update.json <<'PY'
import json, sys
c, n, a, s, notes, m = sys.argv[1:]
print(json.dumps({"versionCode": int(c), "versionName": n, "minSdk": int(m), "apk": a, "sha256": s, "notes": notes}, ensure_ascii=False, indent=2))
PY

# Aktuelle APK im Ordner Install mit veröffentlichen (fester Download-Ort im Repository)
git add -f Install
git commit -q -m "Install: Familienplaner $NAME" -m "Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>" || true
git tag -a "$TAG" -m "Version $NAME"
git push origin HEAD "$TAG"
gh release create "$TAG" "build/$APK" build/update.json --title "Familienplaner $NAME" --notes "$NOTES"
echo "Veröffentlicht: $TAG (versionCode $CODE)"
