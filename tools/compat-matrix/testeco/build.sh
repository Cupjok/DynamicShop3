#!/usr/bin/env bash
# Builds DSTestEco.jar (test-only in-memory Vault economy) from the jars in the local Maven repository.
# Run `mvn compile` in the project once first so VaultAPI and paper-api 1.21 are downloaded.
set -e
cd "$(dirname "$0")"
M2="${M2_REPO:-$HOME/.m2/repository}"
CP="$(ls "$M2"/com/github/MilkBowl/VaultAPI/1.7.1/VaultAPI-1.7.1.jar):$(ls "$M2"/io/papermc/paper/paper-api/1.21-R0.1-SNAPSHOT/paper-api-1.21-*.jar | grep -v sources | head -1)"
CP="$CP:$(find "$M2/net/kyori" -name '*.jar' ! -name '*sources*' | tr '\n' ':')"
rm -rf out && mkdir out
printf 'name: DSTestEco\nversion: 1.0\nmain: dstesteco.DSTestEco\napi-version: "1.21"\nfolia-supported: true\ndepend: [Vault]\nload: STARTUP\n' > out/plugin.yml
javac --release 21 -nowarn -cp "$CP" -d out src/dstesteco/DSTestEco.java
(cd out && jar cf ../DSTestEco.jar .)
rm -rf out
echo "built $(pwd)/DSTestEco.jar"
