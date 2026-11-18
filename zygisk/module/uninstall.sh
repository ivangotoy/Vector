# VECTOR_TEST_ROOT is set only by the offline test; on a device it is empty
root="${VECTOR_TEST_ROOT:-}"
# the daemon records its /data/misc/<uuid> directory here as one line; any other content is ignored
misc_file="$root/data/adb/lspd/misc_path"
if [ -f "$misc_file" ]; then
    misc="$(cat "$misc_file")"
    if [ "$(printf '%s' "$misc" | wc -l)" -eq 0 ] \
        && printf '%s\n' "$misc" | grep -Eqx '/data/misc/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'; then
        rm -rf "$root$misc"
    fi
fi
rm -rf "$root/data/adb/lspd"
