#!/bin/sh
# Host test of tools/config-get.sh: the build reads Config.ClusterMap.ENABLED to decide whether the
# libgal gets the AA map (CLUSTER_SINK), and an explicit CLUSTER_SINK in the environment wins.
cd "$(dirname "$0")/../.."
. tools/config-get.sh
failed=0
check() {   # what, got, want
  if [ "$2" = "$3" ]; then printf '%-48s ok\n' "$1"; else printf '%-48s FAIL (got "%s", want "%s")\n' "$1" "$2" "$3"; failed=1; fi
}
T="${TMPDIR:-/tmp}/kb_config_get_$$.java"
cat > "$T" <<'JAVA'
public final class Config {
    public static final class Navigation {
        public static final boolean ENABLED = false;
    }
    public static final class ClusterMap {
        /** ENABLED in a comment: public static final boolean ENABLED = false; */
        public static final boolean ENABLED = true;
        public static final int    MODE_KEY  = 2 ;
        public static final class ModeKey {
            public static final int NONE = 0;
        }
    }
}
JAVA
check "group decides (ClusterMap.ENABLED)"       "$(config_get ClusterMap ENABLED "$T")" "true"
check "another group's same name"               "$(config_get Navigation ENABLED "$T")" "false"
check "spaces around the value"                 "$(config_get ClusterMap MODE_KEY "$T")" "2"
check "absent name prints nothing"              "$(config_get ClusterMap NOPE "$T")" ""
unset CLUSTER_SINK; cluster_sink_choice "$T"
check "from Config: true -> 1"                  "$CLUSTER_SINK" "1"
sed 's/ENABLED = true/ENABLED = false/' "$T" > "$T.2"
unset CLUSTER_SINK; cluster_sink_choice "$T.2"
check "from Config: false -> 0"                 "$CLUSTER_SINK" "0"
CLUSTER_SINK=0; cluster_sink_choice "$T"
check "environment overrides Config"            "$CLUSTER_SINK" "0"
check "the real Config.java has the switch"     "$(config_get ClusterMap ENABLED | grep -cE '^(true|false)$')" "1"
printf '    public static final class ClusterMap {\n        public static final boolean HIDE_PLATE_ON_AA_MAP = true;\n    }\n' > "$T.3"
check "plate hidden -> CLUSTER_PLATE_HIDDEN=1"  "$(cluster_plate_defines "$T.3")" "-DCLUSTER_PLATE_HIDDEN=1"
sed 's/= true/= false/' "$T.3" > "$T.4"
check "plate shown -> no define"                "$(cluster_plate_defines "$T.4")" ""
check "the real Config.java has the plate switch" "$(config_get ClusterMap HIDE_PLATE_ON_AA_MAP | grep -cE '^(true|false)$')" "1"
printf '    public static final class Advanced {\n        public static final boolean DESKTOP_HEAD_UNIT = true;\n    }\n' > "$T.5"
check "DHU on -> CLUSTER_DHU=1"                 "$(cluster_dhu_defines "$T.5")" "-DCLUSTER_DHU=1"
sed 's/= true/= false/' "$T.5" > "$T.6"
check "DHU off -> no define"                    "$(cluster_dhu_defines "$T.6")" ""
check "the plate switch is not the DHU switch"  "$(cluster_dhu_defines "$T.3")" ""
check "the real Config.java has the DHU switch" "$(config_get Advanced DESKTOP_HEAD_UNIT | grep -cE '^(true|false)$')" "1"
rm -f "$T" "$T.2" "$T.3" "$T.4" "$T.5" "$T.6"
[ "$failed" = 0 ] && echo "all tests passed"
exit $failed
