#!/bin/sh
# Host test for the AA -> cluster lookup tables in AANavReader. Needs jar/build/classes (run the jar
# build first) and MIBHMI.jar on the classpath so the class under test resolves its framework types.
# Never part of the shipped jar: the build compiles jar/src only, and this tree is outside it.
#
# The sources live in their real package path so they can reach the package-private methods they
# test; build output goes under jar/build, which is already ignored.
set -e
cd "$(dirname "$0")/../.."
[ -d jar/build/classes ] || { echo "run the jar build first (./build.sh <jxe> <so>)"; exit 1; }
CP="jar/build/classes:jar/lib/MIBHMI.jar"
rm -rf jar/build/test && mkdir -p jar/build/test
find tests/jar -name '*.java' > jar/build/test-sources.txt
"${JAVAC:-javac}" -nowarn -cp "$CP" -d jar/build/test @jar/build/test-sources.txt
"${JAVA:-java}" -Xverify:all -cp "jar/build/test:$CP" de.vw.mib.bap.mqbab2.navsd.functions.NavMappingTest
"${JAVA:-java}" -Xverify:all -cp "jar/build/test:$CP" de.aatokombi.MediaViewModeTest
"${JAVA:-java}" -Xverify:all -cp "jar/build/test:$CP" de.vw.mib.bap.mqbab2.navsd.functions.MediaViewRoutingTest
