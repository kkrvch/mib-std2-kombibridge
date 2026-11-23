# tools/jxe2jar — vendored JXE → JAR converter

`build.sh` uses this to turn the unit's `MIBHMI.jxe` (IBM J9 ROM image) into `jar/lib/MIBHMI.jar`,
the compile classpath of the HMI mod. It runs inside the project's Docker toolchain image
(Python 3, `bitstring` 4.2 — version 5 dropped `BitStream`).

Origin: the Python 3 jxe2jar converter (`src/` of a jxe2jar fork) based on
https://github.com/moradek/jxe2jar and https://github.com/andrewleech/jxe2jar — thanks to their
authors and contributors. No licence file was found upstream; the files are kept unchanged except
`constpool.py`, which carries the owner's fix for decoding J9 "long" constant-pool slots as
field/method references (`_try_decode_ref_from_long`).

Why not `ghcr.io/adi961/jxe2jar` any more: that conversion corrupted int constants (for example the
`NavigationMapAdapter` map-visibility events came out as -154660800 / -171438016 instead of
1074841846 / 1074841845) and dropped `InnerClasses` / `throws` information, so nested classes had
to be named with `$` in sources and constants read from the jar could not be trusted.

Usage outside the build: `python3 jxe2jar.py MIBHMI.jxe MIBHMI.jar` (several minutes).
