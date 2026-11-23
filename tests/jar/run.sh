#!/bin/sh
# Host tests of the jar: the AA -> cluster lookup tables in AANavReader (NavMappingTest) and the
# small decisions of the mod's own classes (cluster state line, wheel keys, log mirror, media
# session). Needs jar/build/classes (run the jar build first) and MIBHMI.jar on the classpath so
# the classes under test resolve their framework types.
# Never part of the shipped jar: the build compiles jar/src only, and this tree is outside it.
#
# The sources live in the package of the class they test so they can reach its package-private
# methods; build output goes under jar/build, which is already ignored.
set -e
cd "$(dirname "$0")/../.."
[ -d jar/build/classes ] || { echo "run the jar build first (./build.sh <jxe> <so>)"; exit 1; }
CP="jar/build/classes:jar/lib/MIBHMI.jar"
rm -rf jar/build/test && mkdir -p jar/build/test
find tests/jar -name '*.java' > jar/build/test-sources.txt
"${JAVAC:-javac}" -nowarn -encoding UTF-8 -cp "$CP" -d jar/build/test @jar/build/test-sources.txt
rc=0
for t in de.vw.mib.bap.mqbab2.navsd.functions.NavMappingTest \
         de.aatokombi.MediaViewModeTest \
         de.vw.mib.bap.mqbab2.navsd.functions.MediaViewRoutingTest \
         de.aatokombi.ModStateTest \
         de.adi961.miblogger.MIBLoggerTest \
         de.vw.mib.asl.internal.androidauto.target.MediaReaderTest; do
  echo "== $t"
  "${JAVA:-java}" -Xverify:all -cp "jar/build/test:$CP" "$t" || rc=1
done
[ "$rc" = 0 ] && echo "ALL JAR TESTS PASSED" || echo "JAR TESTS FAILED"
exit $rc
