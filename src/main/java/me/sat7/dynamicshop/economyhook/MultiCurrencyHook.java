package me.sat7.dynamicshop.economyhook;

import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.constants.Constants;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import static me.sat7.dynamicshop.utilities.LangUtil.n;

/**
 * Optional MultiCurrency integration, guarded like the Jobs/PlayerPoints hooks.
 *
 * This class deliberately contains no MultiCurrency API types, so it can be loaded (and unit-tested) when
 * MultiCurrency is not installed. Everything that touches the API lives in {@link MultiCurrencyBridge}, which is
 * only reached after {@link #multiCurrencyActive} proved the plugin is enabled.
 *
 * DynamicShop only uses MultiCurrency's public API (multicurrency-api): currency lookup, withdraw, deposit,
 * balance (display only) and history (outcome confirmation). It never touches its database.
 */
public final class MultiCurrencyHook
{
    private MultiCurrencyHook()
    {

    }

    public static boolean multiCurrencyActive = false;

    /** Recorded as the actor of every ledger entry DynamicShop creates. */
    public static final String ACTOR_NAME = "DynamicShop";

    public record CurrencyInfo(String id, String displayName, String symbol, boolean enabled, int scale)
    {
    }

    /**
     * Result of one API call.
     *
     * @param failureReason MultiCurrency's FailureReason name, or "EXCEPTION" when the call/future failed so the
     *                      outcome cannot be known; null on success
     */
    public record Attempt(boolean success, String failureReason, BigDecimal balanceAfter)
    {
        public static Attempt exception()
        {
            return new Attempt(false, REASON_EXCEPTION, null);
        }
    }

    public static final String REASON_EXCEPTION = "EXCEPTION";
    public static final String REASON_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    private static final Pattern CURRENCY_ID = Pattern.compile("[a-z0-9_]{1,32}");

    public static boolean IsValidCurrencyId(String id)
    {
        return id != null && CURRENCY_ID.matcher(id).matches();
    }

    public static void Setup()
    {
        multiCurrencyActive = false;
        if (!Bukkit.getPluginManager().isPluginEnabled("MultiCurrency"))
        {
            DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'MultiCurrency' Not Found");
            return;
        }

        try
        {
            multiCurrencyActive = MultiCurrencyBridge.IsAvailable();
        }
        catch (Throwable t) // NoClassDefFoundError/LinkageError: incompatible or broken MultiCurrency install
        {
            multiCurrencyActive = false;
            DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'MultiCurrency' found but its API could not be loaded: " + t);
            return;
        }

        DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + (multiCurrencyActive
                ? " 'MultiCurrency' Found"
                : " 'MultiCurrency' is enabled but its API is not registered"));
    }

    /** Plugin present and its API service currently registered. */
    public static boolean IsAvailable()
    {
        if (!multiCurrencyActive)
            return false;
        try
        {
            return MultiCurrencyBridge.IsAvailable();
        }
        catch (Throwable t)
        {
            return false;
        }
    }

    /** @return the currency, or null when MultiCurrency is unavailable or no such currency exists */
    public static CurrencyInfo GetCurrency(String currencyId)
    {
        if (!IsValidCurrencyId(currencyId) || !IsAvailable())
            return null;
        try
        {
            return MultiCurrencyBridge.GetCurrency(currencyId);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    public static List<String> GetCurrencyIds()
    {
        if (!IsAvailable())
            return Collections.emptyList();
        try
        {
            return MultiCurrencyBridge.GetCurrencyIds();
        }
        catch (Throwable t)
        {
            return Collections.emptyList();
        }
    }

    /**
     * Converts a DynamicShop price (double) to an amount MultiCurrency accepts (at most {@code scale} decimals;
     * MultiCurrency rejects, never rounds, excess precision). Floating-point noise is removed first, then the
     * value is rounded with {@code mode}: CEILING for charges (never undercharge), FLOOR for payouts (never
     * overpay).
     *
     * @return null for NaN/infinite values
     */
    public static BigDecimal ToAmount(double value, int scale, RoundingMode mode)
    {
        if (Double.isNaN(value) || Double.isInfinite(value))
            return null;
        int safeScale = Math.max(0, Math.min(scale, 8));
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP).setScale(safeScale, mode);
    }

    public static String FormatAmount(String currencyId, BigDecimal amount)
    {
        if (amount == null)
            return "?";
        if (IsAvailable())
        {
            try
            {
                String s = MultiCurrencyBridge.Format(currencyId, amount);
                if (s != null)
                    return s;
            }
            catch (Throwable ignored)
            {
            }
        }
        return amount.toPlainString();
    }

    /** Formats a DynamicShop price the way it would be charged (CEILING) or paid out (FLOOR). */
    public static String FormatAmount(String currencyId, double value, RoundingMode mode)
    {
        CurrencyInfo info = GetCurrency(currencyId);
        if (info == null)
            return n(value);
        return FormatAmount(currencyId, ToAmount(value, info.scale(), mode));
    }

    public static CompletableFuture<Attempt> Withdraw(UUID player, String currencyId, BigDecimal amount, String reason, String idempotencyKey)
    {
        return Mutate(false, player, currencyId, amount, reason, idempotencyKey);
    }

    public static CompletableFuture<Attempt> Deposit(UUID player, String currencyId, BigDecimal amount, String reason, String idempotencyKey)
    {
        return Mutate(true, player, currencyId, amount, reason, idempotencyKey);
    }

    private static CompletableFuture<Attempt> Mutate(boolean deposit, UUID player, String currencyId, BigDecimal amount, String reason, String idempotencyKey)
    {
        if (!multiCurrencyActive)
            return CompletableFuture.completedFuture(new Attempt(false, REASON_SERVICE_UNAVAILABLE, null));
        try
        {
            return MultiCurrencyBridge.Mutate(deposit, player, currencyId, amount, reason, idempotencyKey);
        }
        catch (Throwable t)
        {
            // We cannot tell whether the call reached MultiCurrency: treat as unknown, never as "not charged".
            return CompletableFuture.completedFuture(Attempt.exception());
        }
    }

    /**
     * Looks the idempotency key up in the player's recent MultiCurrency ledger.
     *
     * @return TRUE if a committed transaction with that key was found, FALSE if not, null if it could not be read
     */
    public static CompletableFuture<Boolean> HistoryContainsKey(UUID player, String currencyId, String idempotencyKey)
    {
        if (!IsAvailable())
            return CompletableFuture.completedFuture(null);
        try
        {
            return MultiCurrencyBridge.HistoryContainsKey(player, currencyId, idempotencyKey, 100);
        }
        catch (Throwable t)
        {
            return CompletableFuture.completedFuture(null);
        }
    }

    // ---------------- balance display cache (advisory only, never used to decide a trade) ----------------

    private static final Map<String, BigDecimal> balanceCache = new ConcurrentHashMap<>();
    private static final Map<String, Long> balanceRequested = new ConcurrentHashMap<>();
    private static final long BALANCE_REFRESH_MS = 2000;

    /** Last known balance, formatted. Triggers an async refresh; the per-second UI refresh picks the new value up. */
    public static String GetDisplayBalance(Player player, String currencyId)
    {
        String key = player.getUniqueId() + ":" + currencyId;
        long now = System.currentTimeMillis();
        Long last = balanceRequested.get(key);
        if ((last == null || now - last > BALANCE_REFRESH_MS) && IsAvailable())
        {
            balanceRequested.put(key, now);
            try
            {
                MultiCurrencyBridge.Balance(player.getUniqueId(), currencyId).whenComplete((bal, err) ->
                {
                    if (err == null && bal != null)
                        balanceCache.put(key, bal);
                });
            }
            catch (Throwable ignored)
            {
            }
        }

        BigDecimal cached = balanceCache.get(key);
        return cached == null ? "..." : FormatAmount(currencyId, cached);
    }

    public static void UpdateCachedBalance(UUID player, String currencyId, BigDecimal balance)
    {
        if (balance != null)
            balanceCache.put(player + ":" + currencyId, balance);
    }

    public static void OnPlayerQuit(UUID player)
    {
        String prefix = player + ":";
        balanceCache.keySet().removeIf(k -> k.startsWith(prefix));
        balanceRequested.keySet().removeIf(k -> k.startsWith(prefix));
    }
}
