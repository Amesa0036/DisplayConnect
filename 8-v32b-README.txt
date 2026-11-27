Hecate NEW v3.2b firmware sources backup (reconstruction, label NEW; host tests only, no hardware proof).
Restore: base64 -d 8-v32b-sources.tgz.b64.txt > sdk-cont-v32b-main-sources.tgz ; base64 -d 8-v32b-tests.tgz.b64.txt > tests-host-v3.2-G.tgz ; sha256sum -c below.
b91f065cb31edcb1dc38c9d73d506f97e1fa614f01b93d982977691e471c1b2c  sdk-cont-v32b-main-sources.tgz
8204fff22b313d015c98c6f2b88928e9cdc4d9032ffafc065384883ec3acc842  tests-host-v3.2-G.tgz
Contents: sources tgz = main/ + CMakeLists.txt + sdkconfig.ci of the v3.2b build tree; tests tgz = host harness and outputs.
Changes vs v3.1: hardware_target.h 175G block HUD_OTA_ENABLED 0->1, version 0.28.31-175g-ota1-NEW-v3.2; CMakeLists.txt PROJECT_VER same string.
Unsigned image sha256 f6c32ac8ec54196ca9dd474a84e7a62e52627e07056bae01b1b67033aba8aac1 (1114112 B). Signed image sha256 099578b63a965fc70b1f46e714fb0b7eea598950be728bea46fbd6d878a65af4 (1118208 B), key SPKI ddce1799c6afdeaa25fef1b4928ee1b8b7ddcf5af1048b33eb19307421ee2852.
No binary, key or password here. Not a release.
Build: build-v32.sh style, idf.py -B build-v32 -DSDKCONFIG=sdkconfig.ci, IDF v6.0.1, constraints from dependencies-r3-reference.lock (see Drive copy of sources).
