# Sourced by build.sh and docker/entrypoint.sh (POSIX sh): read a switch out of
# jar/src/de/aatokombi/Config.java, so a build-time choice is made in the same file as every
# other setting.
#
#   config_get <Group> <NAME> [Config.java]   -> prints the value of Config.<Group>.<NAME> as
#                                                written ("true", "3", ...), nothing if absent
#   cluster_sink_choice                       -> sets CLUSTER_SINK (1/0) and CLUSTER_SINK_FROM:
#                                                an explicit CLUSTER_SINK in the environment
#                                                wins, else Config.ClusterMap.ENABLED decides
#
# Relies on the one-line declaration style Config.java asks for
# ("public static final TYPE NAME = value;", the group a class nested at 4 spaces).

config_get() {
  awk -v g="$1" -v n="$2" '
    /^    public static final class [A-Za-z0-9_]+/ {
      for (i = 1; i <= NF; i++) if ($i == "class") { grp = $(i + 1); sub(/[^A-Za-z0-9_].*$/, "", grp) }
      next
    }
    grp == g && $0 ~ ("^        public static final [A-Za-z][A-Za-z0-9_]*[[:space:]]+" n "[[:space:]]*=") {
      v = $0; sub(/^[^=]*=[[:space:]]*/, "", v); sub(/[[:space:]]*;.*$/, "", v); print v; exit
    }' "${3:-jar/src/de/aatokombi/Config.java}"
}

cluster_sink_choice() {
  if [ -n "${CLUSTER_SINK:-}" ]; then
    CLUSTER_SINK_FROM="CLUSTER_SINK=$CLUSTER_SINK in the environment"
  else
    case "$(config_get ClusterMap ENABLED "$@")" in
      true) CLUSTER_SINK=1 ;;
      *)    CLUSTER_SINK=0 ;;
    esac
    CLUSTER_SINK_FROM="Config.ClusterMap.ENABLED"
  fi
}
