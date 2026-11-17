# AAtoKombi build toolchain — reproducible "one button" build of both artifacts.
#
# Contains everything the two build.sh scripts need:
#   * JDK 8        — javac that still accepts -source/-target 1.3 (jar/build.sh)
#   * clang + lld  — freestanding armv7 cross-compile + ld.lld (shim/build.sh)
#   * python3 + pyelftools — the ELF injector (shim/inject.py)
#   * python3-bitstring (distro package, 4.x) — tools/jxe2jar (MIBHMI.jxe -> MIBHMI.jar);
#     no pip: bitstring from pip pulls bitarray, which needs a C toolchain to build
#   * libc6-dev + libstdc++-13-dev — the host tests (tests/shim, tests/ring) compile with them
#
# The base is pinned to its Ubuntu release (noble, 24.04): that fixes clang/lld 18 and bitstring 4.x
# (22.04 has bitstring 3, which jxe2jar cannot use). Within the release the image still gets
# security updates; entrypoint.sh prints the tool versions of every build. A byte-for-byte pin
# would be `FROM eclipse-temurin:8-jdk-noble@sha256:<digest>` - per architecture, so it is left out.

FROM eclipse-temurin:8-jdk-noble

RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      clang \
      lld \
      python3 \
      python3-pyelftools \
      python3-bitstring \
      libc6-dev \
      libstdc++-13-dev \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /work
