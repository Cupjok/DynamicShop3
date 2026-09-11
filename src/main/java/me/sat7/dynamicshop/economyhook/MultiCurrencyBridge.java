package me.sat7.dynamicshop.economyhook;

import me.cupjok.multicurrency.api.Actor;
import me.cupjok.multicurrency.api.Currency;
import me.cupjok.multicurrency.api.MultiCurrencyApi;
import me.cupjok.multicurrency.api.MultiCurrencyProvider;
import me.cupjok.multicurrency.api.TransactionContext;
import me.cupjok.multicurrency.api.TransactionResult;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * The only class that references the MultiCurrency API. Loaded lazily, and only after
 * {@link MultiCurrencyHook#multiCurrencyActive} is true, so a server without MultiCurrency never links it.
 * Returned futures complete on a MultiCurrency database thread - callers must hop back to a server thread.
 */
final class MultiCurrencyBridge
{
    private MultiCurrencyBridge()
    {

    }

    private static MultiCurrencyApi Api()
    {
        MultiCurrencyApi api = null;
        try
        {
            api = Bukkit.getServicesManager().load(MultiCurrencyApi.class);
        }
        catch (RuntimeException ignored)
        {
        }
        return api != null ? api : MultiCurrencyProvider.find().orElse(null);
    }

    static boolean IsAvailable()
    {
        return Api() != null;
    }

    static MultiCurrencyHook.CurrencyInfo GetCurrency(String currencyId)
    {
        MultiCurrencyApi api = Api();
        if (api == null)
            return null;
        Optional<Currency> c = api.currency(currencyId);
        return c.map(x -> new MultiCurrencyHook.CurrencyInfo(x.id(), x.displayName(), x.symbol(), x.enabled(), x.scale())).orElse(null);
    }

    static List<String> GetCurrencyIds()
    {
        MultiCurrencyApi api = Api();
        List<String> ids = new ArrayList<>();
        if (api != null)
        {
            for (Currency c : api.currencies())
                ids.add(c.id());
        }
        return ids;
    }

    static String Format(String currencyId, BigDecimal amount)
    {
        MultiCurrencyApi api = Api();
        if (api == null)
            return null;
        return api.currency(currencyId).map(c -> c.format(amount)).orElse(null);
    }

    static CompletableFuture<MultiCurrencyHook.Attempt> Mutate(boolean deposit, UUID player, String currencyId, BigDecimal amount,
                                                              String reason, String idempotencyKey)
    {
        MultiCurrencyApi api = Api();
        if (api == null)
        {
            // The request never reached MultiCurrency.
            return CompletableFuture.completedFuture(new MultiCurrencyHook.Attempt(false, MultiCurrencyHook.REASON_SERVICE_UNAVAILABLE, null));
        }

        TransactionContext context = new TransactionContext(Actor.plugin(MultiCurrencyHook.ACTOR_NAME), reason, idempotencyKey);

        CompletableFuture<TransactionResult> future;
        try
        {
            future = deposit
                    ? api.deposit(player, currencyId, amount, context)
                    : api.withdraw(player, currencyId, amount, context);
        }
        catch (RuntimeException e)
        {
            return CompletableFuture.completedFuture(MultiCurrencyHook.Attempt.exception());
        }

        return future.handle((r, err) ->
        {
            if (err != null || r == null)
                return MultiCurrencyHook.Attempt.exception();
            return new MultiCurrencyHook.Attempt(r.success(), r.failureReason() == null ? null : r.failureReason().name(), r.balanceAfter());
        });
    }

    static CompletableFuture<Boolean> HistoryContainsKey(UUID player, String currencyId, String idempotencyKey, int limit)
    {
        MultiCurrencyApi api = Api();
        if (api == null)
            return CompletableFuture.completedFuture(null);

        return api.history(player, currencyId, limit).handle((records, err) ->
        {
            if (err != null || records == null)
                return null;
            return records.stream().anyMatch(r -> idempotencyKey.equals(r.idempotencyKey()));
        });
    }

    static CompletableFuture<BigDecimal> Balance(UUID player, String currencyId)
    {
        MultiCurrencyApi api = Api();
        if (api == null)
            return CompletableFuture.completedFuture(null);
        return api.balance(player, currencyId);
    }
}
