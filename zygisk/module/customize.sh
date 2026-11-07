SKIPUNZIP=1

# =========================================================
# Utils functions to extract and verify installation package

TMPDIR_FOR_VERIFY="$TMPDIR/.vunzip"
mkdir "$TMPDIR_FOR_VERIFY"

abort_verify() {
  ui_print "*********************************************************"
  ui_print "! $1"
  ui_print "! This zip may be corrupted, please try downloading again"
  abort    "*********************************************************"
}

# Usage: extract <zip> <file> <target dir> [junk_paths: true|false]
extract() {
  local zip="$1"
  local file="$2"
  local dir="$3"
  local junk_paths="${4:-false}" # Defaults to false if not provided
  local opts="-o"
  local file_path hash_path file_basename

  file_basename=$(basename "$file")

  if [ "$junk_paths" = "true" ]; then
    opts="-oj"
    file_path="$dir/$file_basename"
    hash_path="${TMPDIR_FOR_VERIFY}/$file_basename.sha256"
  else
    file_path="$dir/$file"
    hash_path="${TMPDIR_FOR_VERIFY}/$file.sha256"
  fi

  # Extract the file and its hash
  unzip $opts "$zip" "$file" -d "$dir" >/dev/null 2>&1
  [ -f "$file_path" ] || abort_verify "Extracted $file does not exist"

  unzip $opts "$zip" "$file.sha256" -d "${TMPDIR_FOR_VERIFY}" >/dev/null 2>&1
  [ -f "$hash_path" ] || abort_verify "Hash file $file.sha256 does not exist"

  # Read the expected hash and verify it
  local expected_hash
  read -r expected_hash < "$hash_path"
  expected_hash="${expected_hash%% *}" # Strip anything after the actual hash string

  if ! echo "$expected_hash  $file_path" | sha256sum -c -s -; then
    abort_verify "Failed to verify $file"
  fi

  ui_print "- Verified $file"
}

# Usage: extract_xz <zip entry without the .xz suffix> <target dir> [target name]
#
# The payload is stored as "<entry>.xz" with a "<entry>.xz.sha256" beside it, so the checksum
# covers exactly the bytes that were compressed, and it is verified before anything is
# decompressed. xz carries its own CRC, but that only tells us the stream is intact, not that it
# is the stream this build produced.
extract_xz() {
  local file="$1"
  local dir="$2"
  local name="${3:-$(basename "$file")}"
  local base
  base=$(basename "$file")
  local stage="${TMPDIR_FOR_VERIFY}/xz"
  mkdir -p "$stage"

  unzip -oj "$ZIPFILE" "$file.xz" -d "$stage" >/dev/null 2>&1
  [ -f "$stage/$base.xz" ] || abort_verify "Extracted $file.xz does not exist"

  unzip -oj "$ZIPFILE" "$file.xz.sha256" -d "$stage" >/dev/null 2>&1
  [ -f "$stage/$base.xz.sha256" ] || abort_verify "Hash file $file.xz.sha256 does not exist"

  local expected_hash
  read -r expected_hash < "$stage/$base.xz.sha256"
  expected_hash="${expected_hash%% *}"

  if ! echo "$expected_hash  $stage/$base.xz" | sha256sum -c -s -; then
    abort_verify "Failed to verify $file.xz"
  fi

  mkdir -p "$dir"
  if ! $XZ -d -c "$stage/$base.xz" > "$dir/$name"; then
    rm -f "$dir/$name"
    abort_verify "Failed to decompress $file.xz"
  fi
  rm -f "$stage/$base.xz"
  ui_print "- Verified $file"
}
# =========================================================

# xz ships as an applet of the busybox every supported root provider mounts, but a stripped-down
# recovery busybox may not have it. Find out now rather than half-way through extraction.
XZ="xz"
if ! command -v xz >/dev/null 2>&1; then
  if busybox --list 2>/dev/null | grep -qx xz; then
    XZ="busybox xz"
  else
    abort "! xz is required to unpack this module and was not found in PATH"
  fi
fi

VERSION=$(grep_prop version "${TMPDIR}/module.prop")
ui_print "- Vector version ${VERSION}"

# Disable existing LSPosed installation
LSPOSED_DIR="/data/adb/modules/zygisk_lsposed"
if [ -d "$LSPOSED_DIR" ]; then
    ui_print "*********************************************************"
    ui_print "LSPosed installation detected, disabling it for Vector"
    touch "$LSPOSED_DIR/disable"
    ui_print "*********************************************************"
fi

# 1. Map architecture to standard ABI paths, eliminating duplicate logic
case "$ARCH" in
    arm64)
        ABI="arm64-v8a"
        ;;
    *)
        abort "! Unsupported platform: $ARCH (64-bit ARM only)"
        ;;
esac
ui_print "- Device platform: $ARCH ($ABI)"

ui_print "- Extracting root module files"
for file in module.prop action.sh service.sh uninstall.sh sepolicy.rule cli daemon; do
    extract "$ZIPFILE" "$file" "$MODPATH"
done

ui_print "- Extracting compressed payloads"
extract_xz "framework/vector.dex" "$MODPATH"
extract_xz "daemon.apk" "$MODPATH"
extract_xz "manager.apk" "$MODPATH"

ui_print "- Extracting Zygisk libraries"
mkdir -p "$MODPATH/zygisk"

extract_xz "lib/$ABI/libzygisk.so" "$MODPATH/zygisk" "${ABI}.so"

if [ "$API" -ge 29 ]; then
    ui_print "- Extracting dex2oat binaries"
    mkdir -p "$MODPATH/bin"

    # The daemon resolves these two by name, so the 64 suffix stays even now that there is
    # nothing to tell them apart from.
    extract_xz "bin/$ABI/dex2oat" "$MODPATH/bin" "dex2oat64"
    extract_xz "bin/$ABI/liboat_hook.so" "$MODPATH/bin" "liboat_hook64.so"

    ui_print "- Patching binaries for anti-detection"
    DEV_PATH=$(tr -dc 'a-z0-9' </dev/urandom | head -c 32)
    # Patch only if the file successfully exists
    [ -f "$MODPATH/daemon.apk" ] && sed -i "s/5291374ceda0aef7c5d86cd2a4f6a3ac/$DEV_PATH/g" "$MODPATH/daemon.apk"
    [ -f "$MODPATH/bin/dex2oat64" ] && sed -i "s/5291374ceda0aef7c5d86cd2a4f6a3ac/$DEV_PATH/" "$MODPATH/bin/dex2oat64"
else
    extract "$ZIPFILE" 'system.prop' "$MODPATH"
fi

ui_print "- Setting permissions"
set_perm_recursive "$MODPATH" 0 0 0755 0644
[ -d "$MODPATH/bin" ] && set_perm_recursive "$MODPATH/bin" 0 2000 0755 0755 u:object_r:xposed_file:s0

set_perm "$MODPATH/daemon" 0 0 0744
set_perm "$MODPATH/cli" 0 0 0744

if [ "$(grep_prop ro.maple.enable)" = "1" ]; then
    ui_print "- Add ro.maple.enable=0"
    echo "ro.maple.enable=0" >>"$MODPATH/system.prop"
fi

ui_print "- Welcome to Vector!"
