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
expect "$(cat "$w/probe/mode" 2>/dev/null)" 600 "keystore mode while Gradle runs (umask 022)"
expect "$(cat "$w/probe/content" 2>/dev/null)" same "keystore holds the decoded secret"
[ ! -e "$w/.ci-home/key.jks" ] && ok "keystore removed after the build" || bad "keystore left at \$HOME/key.jks"
expect "$(grep -cF "$NEW" "$w/$KT")" 1 "release list redirected to the forge feed"
expect "$(grep -cF "$OLD_URL" "$w/$KT")" 0 "GitHub release-list call gone"
expect "$(diff "$ROOT/$KT" "$w/$KT" | grep -c '^[<>]')" 2 "exactly one line changed"
expect "$(cd "$w" && git ls-files -v -- "$KT" | cut -c1)" h "GitHubRepository.kt marked assume-unchanged"

mktree gradlefail
if run_build gradlefail https://forge.example STUB_GRADLE_FAIL=1; then bad "failing Gradle not reported"; else ok "failing Gradle fails the build"; fi
expect "$(cat "$S/gradlefail/probe/mode" 2>/dev/null)" 600 "keystore mode on the failing build"
[ ! -e "$S/gradlefail/.ci-home/key.jks" ] && ok "keystore removed after a failed build" || bad "keystore left after a failed build"

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
grep -qF 'private const val SHA256_PREFIX = "sha256:"' "$ROOT/$KT" && grep -qF '"digest"] = "sha256:" + zip_sha' "$ROOT/.forgejo/module-ci.conf" \
    && ok "feed digest prefix matches the one the manager checks" || bad "feed digest prefix and GitHubRepository.kt differ"
mkdir -p "$S/rel/.forgejo"
python3 -I - "$S/rel/.forgejo/releases.json" <<'PY'
import json, sys
rels = [{"id": 3100 - i, "tag_name": f"v9.8-{3100 - i}-ci{20 - i}", "name": f"Vector old {i}", "prerelease": False,
         "assets": [{"id": 3100 - i, "name": f"Vector-v9.8-{3100 - i}-Release.zip", "size": 1,
                     "browser_download_url": f"https://forge.example/owner/repo/{i}.zip"}]} for i in range(13)]
rels[4]["tag_name"] = "v9.9-3200-0123abcd-owner-repo-ci7"
json.dump(rels, open(sys.argv[1], "w"), indent=2)
PY
# run_rf <dir> [version]: release_files() in <dir> as the release step calls it (after publish)
run_rf() {
    (cd "$1" && bash -c 'set -euo pipefail
    . "$1"
    SERVER_URL=https://forge.example REPOSITORY=owner/repo TAG=v9.9-3200-0123abcd-owner-repo-ci7
    VERSION="$2" VC=3200 SRC_SHA=0123abcd0123abcd0123abcd0123abcd0123abcd
    ZIP_SHA="${RF_ZIP_SHA-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef}"
    BODY="Built by module-release #7" ZIP_SIZE=4242 ZIP_URL="https://forge.example/owner/repo/releases/download/$TAG/Vector-$TAG.zip"
    release_files
    printf "%s" "$RELEASE_FILES"' _ "$ROOT/.forgejo/module-ci.conf" "${2:-v9.9 (3200-0123abcd-owner-repo)-ci7}" 2>"$1/err.log")
}
rf_out="$(run_rf "$S/rel")" || bad "release_files failed: $(cat "$S/rel/err.log")"
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
    ("asset digest", rels[0]["assets"][0].get("digest") == "sha256:" + "0123456789abcdef" * 4),
]
bad = 0
for name, good in checks:
    print(("ok   " if good else "FAIL ") + "releases.json: " + name)
    bad += not good
sys.exit(1 if bad else 0)
PY
then :; else fails=$((fails + 1)); fi
[ -z "$(find "$S/rel/.forgejo" -name '.releases.*')" ] && ok "releases.json: no temp file left" || bad "releases.json: temp file left"

# bad data must not stop release_files() after publish: a missing, unreadable, non-list or
# mixed feed starts over (logged), and a version without the v prefix is used whole
feed_case() {
    local name="$1" content="$2" version="${3:-}" want="$4" w="$S/rf-$1"
    mkdir -p "$w/.forgejo"
    [ "$content" = "-" ] || printf '%s' "$content" > "$w/.forgejo/releases.json"
    if run_rf "$w" ${version:+"$version"} >/dev/null; then
        if python3 -I - "$w/.forgejo/releases.json" "$want" <<'PY'
import json, re, sys
rels = json.load(open(sys.argv[1]))
want = json.loads(sys.argv[2])
a = rels[0]["assets"][0]["name"]
runs = re.findall(r"\d{3,}", a)
ok = (len(rels) == want["n"] and rels[0]["id"] == 3200 and a.lower().startswith("vector-")
      and runs and int(runs[-1]) == 3200 and a == want.get("asset", a))
sys.exit(0 if ok else 1)
PY
        then ok "release_files: $name"; else bad "release_files: $name (wrong feed)"; fi
    else
        bad "release_files: $name (failed: $(tail -n 1 "$w/err.log"))"
    fi
}
feed_case "missing feed" - "" '{"n": 1}'
feed_case "malformed feed" '[{"id": 1,' "" '{"n": 1}'
feed_case "feed not a list" '{"id": 1}' "" '{"n": 1}'
feed_case "non-dict entries dropped" '[1, "x", null, {"id": 3100, "tag_name": "v9.8-3100", "assets": []}]' "" '{"n": 2}'
feed_case "version without v prefix" '[]' "9.9 (3200-0123abcd-owner-repo)-ci7" \
    '{"n": 1, "asset": "Vector-9.9 (3200-0123abcd-owner-repo)-ci7-3200-Release.zip"}'
mkdir -p "$S/rf-badsha/.forgejo"
if RF_ZIP_SHA="not-a-sha" run_rf "$S/rf-badsha" >/dev/null \
    && python3 -I -c 'import json,sys; sys.exit("digest" in json.load(open(sys.argv[1]))[0]["assets"][0])' "$S/rf-badsha/.forgejo/releases.json" \
    && grep -q "no digest in the feed" "$S/rf-badsha/err.log"; then
    ok "release_files: a ZIP_SHA that is not a sha256 is left out (logged)"
else
    bad "release_files: bad ZIP_SHA not handled"
fi
grep -q "starting a new feed" "$S/rf-malformed feed/err.log" && ok "release_files: unreadable feed logged" \
    || bad "release_files: unreadable feed not logged"

# the /data/local/tmp/daemon.apk override is honoured only where the build set debug="true"
for f in daemon cli; do
    m="$ROOT/zygisk/module/$f"
    if grep -qx 'debug="@DEBUG@"' "$m" && grep -qF 'if [ "$debug" = "true" ] && [ -r "$tmpDaemonApk" ]; then' "$m" \
        && [ "$(grep -c 'tmpDaemonApk" ]' "$m")" = 1 ]; then
        ok "zygisk/module/$f: daemon.apk override only in debug builds"
    else
        bad "zygisk/module/$f: daemon.apk override not limited to debug builds"
    fi
done
grep -qxF "          (umask 077; printf '%s' \"\$KEY_STORE\" | base64 --decode > key.jks)" "$ROOT/.github/workflows/core.yml" \
    && ok "core.yml writes key.jks under umask 077" || bad "core.yml writes key.jks under the default umask"
grep -q 'include("daemon", "cli")' "$ROOT/zygisk/build.gradle.kts" && ok "build fills @DEBUG@ in daemon and cli" \
    || bad "build does not fill @DEBUG@ in cli"

# uninstall.sh: removes the recorded /data/misc/<uuid> only when the record is exactly that shape;
# /data/adb/lspd goes in every case. Run with sh under a fake root.
U=0123abcd-4567-89ab-cdef-0123456789ab
uninstall_case() {
    local name="$1" record="$2" want_gone="$3" r="$S/un-$1"
    mkdir -p "$r/data/adb/lspd/config" "$r/data/misc/$U" "$r/data/misc/keep" "$r/data/adb/other"
    touch "$r/data/misc/$U/prefs" "$r/data/misc/keep/f" "$r/data/adb/other/f"
    [ "$record" = "-" ] || printf '%b' "$record" > "$r/data/adb/lspd/misc_path"
    VECTOR_TEST_ROOT="$r" sh "$ROOT/zygisk/module/uninstall.sh"
    local got=kept
    [ -e "$r/data/misc/$U" ] || got=gone
    if [ "$got" = "$want_gone" ] && [ ! -e "$r/data/adb/lspd" ] && [ -f "$r/data/misc/keep/f" ] \
        && [ -f "$r/data/adb/other/f" ] && [ -d "$r/data/misc" ]; then
        ok "uninstall.sh: $name"
    else
        bad "uninstall.sh: $name (misc dir $got, want $want_gone)"
    fi
}
uninstall_case "recorded misc dir removed" "/data/misc/$U\n" gone
uninstall_case "record without final newline" "/data/misc/$U" gone
uninstall_case "no record" - kept
uninstall_case "empty record" "" kept
uninstall_case "dot-dot" "/data/misc/../adb\n" kept
uninstall_case "dot-dot after uuid" "/data/misc/$U/../keep\n" kept
uninstall_case "trailing slash" "/data/misc/$U/\n" kept
uninstall_case "misc root" "/data/misc\n" kept
uninstall_case "upper-case uuid" "/data/misc/0123ABCD-4567-89ab-cdef-0123456789ab\n" kept
uninstall_case "relative path" "data/misc/$U\n" kept
uninstall_case "trailing space" "/data/misc/$U \n" kept
uninstall_case "CRLF" "/data/misc/$U\r\n" kept
uninstall_case "two lines" "/data/misc/$U\n/data/misc/keep\n" kept
uninstall_case "glob" "/data/misc/*\n" kept
grep -qF 'basePath.resolve("misc_path")' "$ROOT/daemon/src/main/kotlin/org/matrix/vector/daemon/data/ConfigCache.kt" \
    && ok "daemon records the misc path where uninstall.sh reads it" || bad "daemon does not record misc_path"

echo
if [ "$fails" = 0 ]; then echo "all checks passed"; else echo "$fails check(s) failed"; exit 1; fi
