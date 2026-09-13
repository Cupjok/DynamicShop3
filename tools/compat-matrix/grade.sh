#!/usr/bin/env bash
# Grades one finished test server folder made by run-matrix.sh. Prints one PASS/FAIL line, plus the
# problem lines on FAIL. Usage: grade.sh <server-folder>
dir="$1"
name="$(basename "$dir")"
log="$dir/console.log"
shop="$dir/plugins/DynamicShop/Shop/CompatShop.yml"

booted=$(grep -c 'Done (' "$log" 2>/dev/null)
enabled=$(grep -c 'Enabling DynamicShop' "$log" 2>/dev/null)
items=0
[ -f "$shop" ] && items=$(grep -c -E 'mat: (STONE|DIAMOND|IRON_INGOT)$' "$shop")
replies=$(grep -c -E 'Shop created|Item added|Plugin reloaded' "$log" 2>/dev/null)

# DynamicShop must only be disabled by the final "stop". Anything earlier means it failed to enable or crashed.
stop_line=$(grep -n 'Stopping the server' "$log" | head -1 | cut -d: -f1)
early_disable=$(grep -n 'Disabling DynamicShop' "$log" | cut -d: -f1 | while read l; do
    [ -z "$stop_line" ] || [ "$l" -lt "$stop_line" ] && echo "$l"; done | head -1)

# Stack traces / errors that mention the plugin, and any linkage error (wrong API for this server version).
problems=$(grep -n -E 'NoSuchMethodError|NoSuchFieldError|NoClassDefFoundError|IncompatibleClassChangeError|AbstractMethodError|LinkageError|ClassCastException' "$log"
           grep -n -i -E '(exception|error|severe).*(dynamicshop|sat7|DShop)|(dynamicshop|sat7|DShop).*(exception|error|severe)' "$log")
problems=$(printf '%s\n' "$problems" | grep -v -E '^$' | sort -u -t: -k1,1n | head -8)

gui=""
if [ -f "$dir/bot.log" ]; then
    gui=" gui=FAIL"
    grep -q '^GUI PASS' "$dir/bot.log" && gui=" gui=PASS"
fi

status=PASS
[ "$booted" -ge 1 ] && [ "$enabled" -ge 1 ] && [ "$items" -ge 3 ] && [ "$replies" -ge 5 ] \
    && [ -z "$early_disable" ] && [ -z "$problems" ] && [ "$gui" != " gui=FAIL" ] || status=FAIL

printf '%-18s %s  boot=%s enabled=%s items=%s replies=%s%s%s\n' "$name" "$status" "$booted" "$enabled" "$items" "$replies" \
    "$gui" "$([ -n "$early_disable" ] && echo " early-disable@$early_disable")"
[ "$gui" = " gui=FAIL" ] && grep -E '^(FAIL|kicked|error|timeout)' "$dir/bot.log" | head -5 | sed 's/^/    bot: /'
[ -n "$problems" ] && printf '%s\n' "$problems" | sed 's/^/    /'
[ "$status" = PASS ]
