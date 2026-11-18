#!/usr/bin/env bash
# Offline checks of this fork's release config (.forgejo/module-ci.conf), made-up values only:
#   bash .forgejo/tests/run.sh
# build() runs through `module-ci.sh build-user` as the build step runs it, with a stub gradlew
# and a git whose fetch is a no-op; release_files() runs as the release step calls it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KT=manager/src/main/kotlin/org/matrix/vector/manager/data/github/GitHubRepository.kt
OLD_URL='get("$API/$REPO/releases?per_page=$CANARY_FETCH", freshness)'
S="$(mktemp -d)"
trap 'rm -rf "$S"' EXIT
fails=0
ok() { printf 'ok   %s\n' "$*"; }
bad() { printf 'FAIL %s\n' "$*"; fails=$((fails + 1)); }
expect() { if [ "$1" = "$2" ]; then ok "$3"; else bad "$3 (got '$1', want '$2')"; fi; }

mkdir -p "$S/bin"
printf '#!/bin/sh\n[ "$1" = fetch ] && exit 0\nexec %s "$@"\n' "$(command -v git)" > "$S/bin/git"
chmod +x "$S/bin/git"
printf 'not-a-real-keystore\n' > "$S/key.expected"

# a workspace as build() sees it: a git tree with the conf, the current GitHubRepository.kt
# and a gradlew stub that records the keystore while "Gradle" runs
mktree() {
    local w="$S/$1"
    mkdir -p "$w/.forgejo" "$w/$(dirname "$KT")" "$w/.ci-out" "$w/.ci-home" "$w/probe"
    cp "$ROOT/.forgejo/module-ci.conf" "$w/.forgejo/"
    cp "$ROOT/$KT" "$w/$KT"
    cat > "$w/gradlew" <<'EOF'
#!/bin/sh
ks="$ORG_GRADLE_PROJECT_androidStoreFile"
stat -c %a "$ks" > probe/mode
cmp -s "$ks" "$PROBE_KEY" && echo same > probe/content
[ "${STUB_GRADLE_FAIL:-0}" = 1 ] && exit 1
mkdir -p zygisk/release && echo zip > zygisk/release/Vector-v9.9-1234-Release.zip
EOF
    (cd "$w" && git init -q && git add -A && git -c user.name=t -c user.email=t@example.invalid commit -qm t)
}
run_build() {
    local w="$S/$1" url="$2"; shift 2
    (umask 022; cd "$w" && env "$@" PATH="$S/bin:$PATH" HOME="$w/.ci-home" PROBE_KEY="$S/key.expected" \
        SERVER_URL="$url" REPOSITORY=owner/repo BASE_SHA="$(git rev-parse HEAD)" JAVA_HOME_21=/nonexistent/jdk21 \
        SIGN_KEYSTORE_B64="$(base64 -w0 < "$S/key.expected")" SIGN_KEY_ALIAS=alias \
        SIGN_STORE_PASSWORD=store-pass SIGN_KEY_PASSWORD=key-pass \
        bash "$ROOT/.forgejo/module-ci.sh" build-user "$w") > "$w/out.log" 2>&1
}

NEW='get("https://forge.example/owner/repo/raw/branch/main/.forgejo/releases.json", freshness)'
grep -qF "$OLD_URL" "$ROOT/$KT" && ok "GitHubRepository.kt still holds the release-list call" \
    || bad "GitHubRepository.kt no longer holds $OLD_URL"

mktree good
if run_build good https://forge.example; then ok "build-user succeeds"; else bad "build-user failed: $(tail -n 3 "$S/good/out.log")"; fi
w="$S/good"
[ -f "$w/.ci-out/module.zip" ] && ok "module.zip left in OUT" || bad "no module.zip in OUT"
expect "$(cat "$w/probe/content" 2>/dev/null)" same "keystore holds the decoded secret"
expect "$(grep -cF "$NEW" "$w/$KT")" 1 "release list redirected to the forge feed"
expect "$(grep -cF "$OLD_URL" "$w/$KT")" 0 "GitHub release-list call gone"
expect "$(diff "$ROOT/$KT" "$w/$KT" | grep -c '^[<>]')" 2 "exactly one line changed"
expect "$(cd "$w" && git ls-files -v -- "$KT" | cut -c1)" h "GitHubRepository.kt marked assume-unchanged"

mktree gradlefail
if run_build gradlefail https://forge.example STUB_GRADLE_FAIL=1; then bad "failing Gradle not reported"; else ok "failing Gradle fails the build"; fi

mktree notfound
sed -i "s|releases?per_page=|releases?per_page=0+|" "$S/notfound/$KT"
if run_build notfound https://forge.example; then bad "missing release-list call not detected"; else ok "missing release-list call fails the build"; fi
grep -q "release list URL not found" "$S/notfound/out.log" && ok "first guard names the cause" || bad "first guard message missing"
[ ! -e "$S/notfound/probe/mode" ] && ok "no Gradle run without the redirect" || bad "Gradle ran without the redirect"

mktree notreplaced
if run_build notreplaced 'https://forge.example/a&b'; then bad "broken replacement not detected"; else ok "broken replacement fails the build"; fi
grep -q "release list URL not replaced" "$S/notreplaced/out.log" && ok "second guard names the cause" || bad "second guard message missing"

# release_files(): newest first, at most 12, a re-released tag replaced, the asset name read by
# GitHubRepository.versionCode() (ZIP_PREFIX asset, last run of 3+ digits)
grep -qF 'private const val ZIP_PREFIX = "Vector-"' "$ROOT/$KT" && grep -qF 'Regex("(\\d{3,})")' "$ROOT/$KT" \
    && ok "versionCode() rule unchanged in GitHubRepository.kt" || bad "versionCode() rule changed: update this test"
mkdir -p "$S/rel/.forgejo"
python3 -I - "$S/rel/.forgejo/releases.json" <<'PY'
import json, sys
rels = [{"id": 3100 - i, "tag_name": f"v9.8-{3100 - i}-ci{20 - i}", "name": f"Vector old {i}", "prerelease": False,
         "assets": [{"id": 3100 - i, "name": f"Vector-v9.8-{3100 - i}-Release.zip", "size": 1,
                     "browser_download_url": f"https://forge.example/owner/repo/{i}.zip"}]} for i in range(13)]
rels[4]["tag_name"] = "v9.9-3200-0123abcd-owner-repo-ci7"
json.dump(rels, open(sys.argv[1], "w"), indent=2)
PY
rf_out="$(cd "$S/rel" && bash -c 'set -euo pipefail
    . "$1"
    SERVER_URL=https://forge.example REPOSITORY=owner/repo TAG=v9.9-3200-0123abcd-owner-repo-ci7
    VERSION="v9.9 (3200-0123abcd-owner-repo)-ci7" VC=3200 SRC_SHA=0123abcd0123abcd0123abcd0123abcd0123abcd
    BODY="Built by module-release #7" ZIP_SIZE=4242 ZIP_URL="https://forge.example/owner/repo/releases/download/$TAG/Vector-$TAG.zip"
    release_files
    printf "%s" "$RELEASE_FILES"' _ "$ROOT/.forgejo/module-ci.conf" 2>&1)" || bad "release_files failed: $rf_out"
expect "$rf_out" .forgejo/releases.json "RELEASE_FILES lists the feed"
if python3 -I - "$S/rel/.forgejo/releases.json" <<'PY'
import json, re, sys
raw = open(sys.argv[1]).read()
rels = json.loads(raw)
tag = "v9.9-3200-0123abcd-owner-repo-ci7"
def vc(r):
    ours = [a for a in r["assets"] if a["name"].lower().startswith("vector-")]
    if not ours:
        return None
    runs = re.findall(r"\d{3,}", ours[0]["name"])
    return int(runs[-1]) if runs else None
checks = [
    ("file ends with a newline", raw.endswith("\n")),
    ("capped at 12", len(rels) == 12),
    ("oldest dropped", 3088 not in [r["id"] for r in rels]),
    ("new release first", rels[0]["tag_name"] == tag),
    ("re-released tag held once", [r["tag_name"] for r in rels].count(tag) == 1),
    ("older order kept, oldest dropped", [r["id"] for r in rels[1:]] == [3100, 3099, 3098, 3097, 3095, 3094, 3093, 3092, 3091, 3090, 3089]),
    ("asset name", rels[0]["assets"][0]["name"] == "Vector-v9.9-3200-Release.zip"),
    ("app reads versionCode 3200", vc(rels[0]) == 3200),
    ("stable, not a canary", rels[0]["prerelease"] is False and not tag.startswith("canary-")),
    ("download url, size, commit", rels[0]["assets"][0]["browser_download_url"].endswith(f"/{tag}/Vector-{tag}.zip")
        and rels[0]["assets"][0]["size"] == 4242 and rels[0]["target_commitish"] == "0123abcd" * 5),
    ("release page", rels[0]["html_url"] == f"https://forge.example/owner/repo/releases/tag/{tag}"),
]
bad = 0
for name, good in checks:
    print(("ok   " if good else "FAIL ") + "releases.json: " + name)
    bad += not good
sys.exit(1 if bad else 0)
PY
then :; else fails=$((fails + 1)); fi

echo
if [ "$fails" = 0 ]; then echo "all checks passed"; else echo "$fails check(s) failed"; exit 1; fi
