#!/bin/sh
# tools/build-config.sh - print what a build actually IS: every compile-time constant of
# jar/src/de/aatokombi/Config.java. Run from the repository root, by build.sh on the host and by
# build.ps1 inside the toolchain container, so both print the same record.
echo
echo "Build configuration:"
echo "  AA protocol advertised .......... 1.7 (NavigationState: manoeuvres, lane guidance,"
echo "                                    destination distance / travel time, rerouting notice)"
# Every compile-time constant that went into the jar, read back out of the source that produced it.
# The values are baked in and unreadable afterwards, so printing them here is the only record of what
# a given pair of artifacts actually does — and the cheapest way to catch a flag left flipped from
# the previous build. Names are ALL_CAPS by convention, prefixed with their group (a nested class
# declared at 4 spaces; the helper classes with the variants of a choice, nested deeper, are not listed); the value is taken verbatim, so a symbolic
# one (LOG_LEVEL = MIBLogger.INFO) prints as written rather than as a number nobody remembers.
CONSTANTS="$(awk '
  /^    public static final class [A-Za-z0-9_]+/ { for (i = 1; i <= NF; i++) if ($i == "class") { grp = $(i + 1); sub(/[^A-Za-z0-9_].*$/, "", grp) } next }
  /^        public static final [A-Za-z][A-Za-z0-9_]*[[:space:]]+[A-Z][A-Z0-9_]*[[:space:]]*=/ {
    line = $0; sub(/^        public static final [A-Za-z][A-Za-z0-9_]*[[:space:]]+/, "", line)
    name = line; sub(/[[:space:]]*=.*$/, "", name)
    val = line; sub(/^[^=]*=[[:space:]]*/, "", val); sub(/;.*$/, "", val)
    print (grp != "" ? grp "." : "") name " " val }' jar/src/de/aatokombi/Config.java 2>/dev/null)"
if [ -n "$CONSTANTS" ]; then
  echo "  Config.java:"
  printf '%s\n' "$CONSTANTS" \
    | while read -r name value; do printf "    %-44s %s\n" "$name" "$value"; done
  # A diagnostic build keeps logs and trip dumps in the head unit's RAM: fine for a test drive,
  # not what should stay on the unit or be given to anybody else.
  case "$CONSTANTS" in
    *"Advanced.LOG_LEVEL MIBLogger.INFO"*|*"Advanced.LOG_LEVEL MIBLogger.ERROR"*|*"Advanced.LOG_LEVEL MIBLogger.SILENT"*) ;;
    *) echo
       echo "  NOTE: Advanced.LOG_LEVEL is DEBUG/TRACE - a DIAGNOSTIC build (RAM log + trip dumps on)."
       echo "        Set it to MIBLogger.INFO for everyday use." ;;
  esac
else
  # Silence here would read as "no switches", which is never true — say it is the reader that broke.
  echo "  Config.java: could not read the constants (declaration style changed? see tools/build-config.sh)"
fi
