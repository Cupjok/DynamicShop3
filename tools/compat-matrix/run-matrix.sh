#!/usr/bin/env bash
# DynamicShop compatibility matrix. Boots one throwaway server per server jar, loads the plugin with a Vault
# economy, runs console commands (create shop, add items, enable, reload, add again), stops it and grades the log.
#
# usage: run-matrix.sh <work-dir> <DynamicShop.jar> [name-glob]
#   <work-dir>/_jars/<platform>-<mc version>.jar   server jars, e.g. paper-1.21.4.jar, folia-26.2.jar
#   <work-dir>/_deps/VaultUnlocked.jar             Vault (VaultUnlocked also loads on Folia)
#   <work-dir>/_deps/DSTestEco.jar                 test economy, build with testeco/build.sh
# Java: JAVA21_HOME for 1.21.x servers, JAVA25_HOME (25+) for 26.x servers. Defaults use macOS java_home.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$1" && pwd)"
DS_JAR="$2"
FILTER="${3:-}"
J21="${JAVA21_HOME:-$(/usr/libexec/java_home -v 21)}"
J25="${JAVA25_HOME:-$(/usr/libexec/java_home -v 25+)}"
RESULTS="$ROOT/results.txt"
: > "$RESULTS"

port=25700
for jar in "$ROOT"/_jars/*.jar; do
    name="$(basename "$jar" .jar)"
    [ -n "$FILTER" ] && [[ "$name" != $FILTER ]] && continue
    ver="${name#*-}"
    port=$((port + 1))
    dir="$ROOT/$name"
    mkdir -p "$dir/plugins"
    rm -rf "$dir"/plugins/DynamicShop*.jar "$dir/plugins/DynamicShop" "$dir/console.log"
    cp "$jar" "$dir/server.jar"
    cp "$DS_JAR" "$dir/plugins/DynamicShop.jar"
    cp "$ROOT/_deps/VaultUnlocked.jar" "$ROOT/_deps/DSTestEco.jar" "$dir/plugins/"
    rm -f "$dir/bot.log" "$dir"/plugins/ViaVersion.jar "$dir"/plugins/ViaBackwards.jar
    # The GUI bot speaks 1.21.x. On 26.x servers it joins through ViaVersion + ViaBackwards (optional, _deps/).
    case "$ver" in 1.*) botver="" ;; *) botver="1.21.11"
        [ -n "${BOT_NODE_PATH:-}" ] && cp "$ROOT/_deps/ViaVersion.jar" "$ROOT/_deps/ViaBackwards.jar" "$dir/plugins/" ;; esac
    [ "$ver" = "1.21.7" ] && botver="1.21.8"   # same protocol, not listed separately by minecraft-data
    echo "eula=true" > "$dir/eula.txt"
    cat > "$dir/server.properties" <<EOF
server-port=$port
online-mode=false
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
view-distance=4
simulation-distance=4
EOF
    case "$ver" in 1.*) JAVA="$J21/bin/java" ;; *) JAVA="$J25/bin/java" ;; esac

    log="$dir/console.log"
    pipe="$dir/.in"
    rm -f "$pipe"; mkfifo "$pipe"
    sleep 900 > "$pipe" 2>/dev/null &     # keeps stdin open so the server never reads EOF
    holder=$!
    ( cd "$dir" && exec "$JAVA" -Xms1G -Xmx2G -jar server.jar --nogui < "$pipe" > "$log" 2>&1 ) &
    spid=$!

    booted=0
    for _ in $(seq 300); do
        grep -q 'Done (' "$log" 2>/dev/null && { booted=1; break; }
        kill -0 $spid 2>/dev/null || break
        sleep 1
    done

    if [ $booted = 1 ]; then
        sleep 3
        for cmd in 'ds createshop CompatShop' \
                   'ds shop CompatShop add stone 10 1000 1000' \
                   'ds shop CompatShop add diamond 100 50 200 100 100' \
                   'ds shop CompatShop enable true' \
                   'ds reload' \
                   'ds shop CompatShop add iron_ingot 5 1000 1000'; do
            printf '%s\n' "$cmd" > "$pipe"; sleep 3
        done

        # Optional GUI phase: a Mineflayer bot opens the shop, buys, sells, uses /sell all and the start page.
        if [ -n "${BOT_NODE_PATH:-}" ]; then
            NODE_PATH="$BOT_NODE_PATH" node "$HERE/gui-bot.js" 127.0.0.1 "$port" $botver > "$dir/bot.log" 2>&1 &
            bpid=$!
            for _ in $(seq 60); do grep -q 'DSBot joined the game' "$log" && break; sleep 1; done
            for cmd in 'op DSBot' 'gamemode survival DSBot' 'clear DSBot' 'give DSBot stone 32' 'give DSBot bedrock 5'; do
                printf '%s\n' "$cmd" > "$pipe"; sleep 1
            done
            for _ in $(seq 150); do kill -0 $bpid 2>/dev/null || break; sleep 1; done
            kill $bpid 2>/dev/null
        fi
        printf 'stop\n' > "$pipe"
    fi
    for _ in $(seq 90); do kill -0 $spid 2>/dev/null || break; sleep 1; done
    kill $spid 2>/dev/null; kill $holder 2>/dev/null; rm -f "$pipe"

    "$HERE/grade.sh" "$dir" | tee -a "$RESULTS"
done
