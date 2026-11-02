# AAtoKombi build toolchain — reproducible "one button" build of both artifacts.
#
# Contains everything the two build.sh scripts need:
#   * JDK 8        — javac that still accepts -source/-target 1.3 (jar/build.sh)
#   * clang + lld  — freestanding armv7 cross-compile + ld.lld (shim/build.sh)
#   * python3 + pyelftools — the ELF injector (shim/inject.py)
#   * python3-bitstring (distro package, 4.x) — tools/jxe2jar (MIBHMI.jxe -> MIBHMI.jar);
#     no pip: bitstring from pip pulls bitarray, which needs a C toolchain to build

FROM eclipse-temurin:8-jdk

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      clang \
      lld \
      python3 \
      python3-pyelftools \
      python3-bitstring \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /work
