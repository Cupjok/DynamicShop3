package me.sat7.dynamicshop.transactions;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.sat7.dynamicshop.DynaShopAPI;
import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.constants.Constants;
import me.sat7.dynamicshop.economyhook.MultiCurrencyHook;
import me.sat7.dynamicshop.events.ShopBuySellEvent;
import me.sat7.dynamicshop.files.CustomConfig;
import me.sat7.dynamicshop.guis.InGameUI;
import me.sat7.dynamicshop.guis.UIManager;
import me.sat7.dynamicshop.utilities.*;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import static me.sat7.dynamicshop.utilities.LangUtil.t;

/**
 * Buy/sell/payout flow for shops whose currency is "MultiCurrency:<id>".
 *
 * Rules (see CLAUDE.md "MultiCurrency integration"):
 * - No balance check before charging: MultiCurrency's withdraw checks and debits atomically.
 * - Every order has a UUID created once and journaled (MultiCurrencyOrders.yml, atomic write) BEFORE the first
 *   API call; its idempotency key is derived from it, so retries - also after a restart - reuse the same key.
 * - Items are handed out only after the payment is definitively APPLIED. OUTCOME_UNKNOWN is never read as
 *   "failed": the same request is retried until MultiCurrency answers definitively.
 * - Journal mutations and Bukkit calls run on server threads (global/entity scheduler), never on the
 *   MultiCurrency database thread that completes the futures.
 */
public final class MultiCurrencyTrade
{
    private MultiCurrencyTrade()
    {

    }

    private static final String JOURNAL_FILE = "MultiCurrencyOrders.yml";
    /** Delays between the quick retries of an OUTCOME_UNKNOWN (1s, 3s, 10s); afterwards the resolver takes over. */
    static final long[] RETRY_DELAYS_TICKS = {20L, 60L, 200L};
    private static final long RESOLVER_PERIOD_TICKS = 20L * 60L;
    private static final long JOIN_DELIVERY_DELAY_TICKS = 40L;

    /** Identifies this server run; stock is only given back for orders placed in the same run (see RestoreStock). */
    private static final String SESSION = UUID.randomUUID().toString();

    private static final Map<String, MultiCurrencyOrder> orders = new ConcurrentHashMap<>();
    /** Orders with an API call (or a scheduled retry) outstanding. */
    private static final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    private static ScheduledTask resolverTask;
    private static volatile boolean enabled;

    // =========================================================================================== lifecycle

    public static void OnEnable()
    {
        LoadJournal();
        enabled = true;

        boolean changed = false;
        for (MultiCurrencyOrder o : orders.values())
        {
            if (o.state == MultiCurrencyOrder.State.GIVING)
            {
                // The server stopped while items were being handed out. Whether the player kept them depends on
                // when their player file was last saved, so neither re-giving (duplication) nor dropping (loss) is safe.
                o.state = MultiCurrencyOrder.State.REVIEW;
                changed = true;
            }
            if (o.state == MultiCurrencyOrder.State.REVIEW)
                LogReview(o, "order was interrupted while items were being given; check the player's inventory");
        }
        if (changed)
            SaveJournal();

        if (!orders.isEmpty())
            DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " MultiCurrency: " + orders.size() + " unfinished order(s) loaded from " + JOURNAL_FILE);

        if (resolverTask != null)
            resolverTask.cancel();
        resolverTask = SchedulerUtil.runGlobalTimer(MultiCurrencyTrade::ResolvePending, 100L, RESOLVER_PERIOD_TICKS);
    }

    public static void OnDisable()
    {
        if (!enabled)
            return; // never initialised (e.g. no Vault economy): do not touch the journal file
        enabled = false;
        if (resolverTask != null)
        {
            resolverTask.cancel();
            resolverTask = null;
        }
        // In-flight calls may still complete inside MultiCurrency; their orders stay PENDING in the journal and are
        // resolved with the same idempotency key on the next start.
        SaveJournal();
        inFlight.clear();
    }

    public static void OnPlayerJoin(Player player)
    {
        for (MultiCurrencyOrder o : orders.values())
        {
            if (o.state == MultiCurrencyOrder.State.OWE_ITEMS && player.getUniqueId().equals(o.player))
                SchedulerUtil.runForEntityDelayed(player, () -> Deliver(player, o), null, JOIN_DELIVERY_DELAY_TICKS);
        }
    }

    // =========================================================================================== checks

    /** @return the currency when trading is possible right now, else null (and the player was told why) */
    public static MultiCurrencyHook.CurrencyInfo CheckCanTrade(CommandSender sender, String currency)
    {
        if (!enabled || !MultiCurrencyHook.IsAvailable())
        {
            sender.sendMessage(DynamicShop.dsPrefix(sender) + t(sender, "ERR.MULTICURRENCY_NOT_FOUND"));
            return null;
        }

        String id = ShopUtil.GetMultiCurrencyId(currency);
        MultiCurrencyHook.CurrencyInfo info = MultiCurrencyHook.GetCurrency(id);
        if (info == null || !info.enabled())
        {
            sender.sendMessage(DynamicShop.dsPrefix(sender) + t(sender, "ERR.MULTICURRENCY_UNKNOWN_CURRENCY").replace("{currency}", id));
            return null;
        }
        return info;
    }

    // =========================================================================================== buy

    /** Replaces Buy.buy() for MultiCurrency shops. Same quantity/stock/limit rules, minus the balance pre-check. */
    public static void Buy(String currency, Player player, String shopName, String tradeIdx, ItemStack itemStack, double priceSum, boolean infiniteStock)
    {
        MultiCurrencyHook.CurrencyInfo info = CheckCanTrade(player, currency);
        if (info == null)
            return;

        CustomConfig data = ShopUtil.shopConfigFiles.get(shopName);

        int tradeAmount = 0;
        int stockOld = data.get().getInt(tradeIdx + ".stock");
        double priceBuyOld = Calc.getCurrentPrice(shopName, tradeIdx, true);
        double priceSellOld = DynaShopAPI.getSellPrice(shopName, itemStack);

        int tradeIdxInt = Integer.parseInt(tradeIdx);
        int tradeLimitPerPlayer = ShopUtil.GetBuyLimitPerPlayer(shopName, tradeIdxInt);
        String hash = HashUtil.GetItemHash(itemStack);
        int playerTradingVolume = UserUtil.GetPlayerTradingVolume(player, shopName, hash);

        // Same loop as Buy.buy(), without "priceSum + price > playerBalance": the player's balance is checked by
        // MultiCurrency inside the withdrawal itself. Reading it here first would be check-then-act.
        for (int i = 0; i < itemStack.getAmount(); i++)
        {
            if (tradeLimitPerPlayer > 0 && tradeLimitPerPlayer <= playerTradingVolume + tradeAmount)
            {
                if (tradeAmount == 0)
                    return;
                break;
            }

            if (!infiniteStock && stockOld <= tradeAmount + 1)
                break;

            priceSum += Calc.getCurrentPrice(shopName, tradeIdx, true, true);

            if (!infiniteStock)
                data.get().set(tradeIdx + ".stock", data.get().getInt(tradeIdx + ".stock") - 1);

            tradeAmount++;
        }

        if (tradeAmount <= 0 || (!infiniteStock && stockOld <= tradeAmount))
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.OUT_OF_STOCK"));
            data.get().set(tradeIdx + ".stock", stockOld);
            return;
        }

        if (data.get().contains("Options.flag.integeronly"))
            priceSum = Math.ceil(priceSum);

        BigDecimal amount = MultiCurrencyHook.ToAmount(priceSum, info.scale(), RoundingMode.CEILING);
        if (amount == null || amount.signum() < 0)
        {
            data.get().set(tradeIdx + ".stock", stockOld);
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.MC_PAYMENT_FAILED").replace("{reason}", "INVALID_AMOUNT"));
            return;
        }

        MultiCurrencyOrder o = MultiCurrencyOrder.create(MultiCurrencyOrder.Type.BUY);
        Fill(o, player, info.id(), amount, shopName, tradeIdx, itemStack, tradeAmount);
        o.priceSum = amount.doubleValue();
        o.stockReserved = !infiniteStock;
        o.reopenGui = true;
        o.hasOldValues = true;
        o.priceBuyOld = priceBuyOld;
        o.priceSellOld = priceSellOld;
        o.stockOld = stockOld;

        // Counted now (like the stock) so parallel orders cannot exceed the per-player limit; undone if not paid.
        if (tradeLimitPerPlayer < Integer.MAX_VALUE)
        {
            UserUtil.OnPlayerTradeLimitedItem(player, shopName, hash, tradeAmount, false);
            o.tradeLimitRecorded = true;
        }

        ShopUtil.shopDirty.put(shopName, true);

        if (amount.signum() == 0)
        {
            // Free item: nothing to charge.
            o.state = MultiCurrencyOrder.State.OWE_ITEMS;
            orders.put(o.journalId(), o);
            if (!SaveJournal())
            {
                AbortBeforeSend(o, player);
                return;
            }
            Deliver(player, o);
            return;
        }

        if (!Start(o))
            AbortBeforeSend(o, player);
    }

    // =========================================================================================== sell

    /**
     * Called by Sell.sell()/quickSellItem() for MultiCurrency shops after their checks and price calculation.
     *
     * @param removeItems takes the items out of the player's inventory (the caller's existing removal logic)
     * @return the expected payout (like quickSellItem's return value), or 0 when nothing was started
     */
    public static double SubmitSell(Player player, String currency, String shopName, int tradeIdx, ItemStack itemStack, int tradeAmount,
                                    double priceSum, double tax, boolean addStockOnSuccess, boolean trackTradeLimit,
                                    boolean reopenGui, String sound, Runnable removeItems,
                                    double priceBuyOld, double priceSellOld, int stockOld)
    {
        MultiCurrencyHook.CurrencyInfo info = CheckCanTrade(player, currency);
        if (info == null)
            return 0;

        BigDecimal amount = MultiCurrencyHook.ToAmount(priceSum, info.scale(), RoundingMode.FLOOR);
        if (amount == null || amount.signum() <= 0)
        {
            // MultiCurrency only accepts positive deposits (e.g. the delivery charge ate the whole price).
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.MC_PRICE_TOO_LOW"));
            return 0;
        }

        // Copy first: quick sell passes the live inventory stack, which the removal below changes.
        ItemStack template = itemStack.clone();

        MultiCurrencyOrder o = MultiCurrencyOrder.create(MultiCurrencyOrder.Type.SELL);
        Fill(o, player, info.id(), amount, shopName, String.valueOf(tradeIdx), template, tradeAmount);
        o.priceSum = amount.doubleValue();
        o.tax = tax;
        o.addStockOnSuccess = addStockOnSuccess;
        o.reopenGui = reopenGui;
        o.sound = sound;
        o.hasOldValues = true;
        o.priceBuyOld = priceBuyOld;
        o.priceSellOld = priceSellOld;
        o.stockOld = stockOld;

        // Take the items and persist the player's inventory before any money moves, so a crash can never leave
        // the player with both the items and the payment.
        removeItems.run();
        player.updateInventory();
        player.saveData();

        if (trackTradeLimit)
        {
            UserUtil.OnPlayerTradeLimitedItem(player, shopName, HashUtil.GetItemHash(o.item), tradeAmount, true);
            o.tradeLimitRecorded = true;
        }

        if (!Start(o))
        {
            orders.remove(o.journalId());
            UndoTradeLimit(o);
            GiveItems(player, o.item, o.itemAmount);
            player.saveData();
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.MC_PAYMENT_FAILED").replace("{reason}", "JOURNAL_WRITE_FAILED"));
            return 0;
        }
        return priceSum;
    }

    // =========================================================================================== payout

    /** Shop account -> player, for MultiCurrency shops. The shop balance is reduced only once the deposit is applied. */
    public static void SubmitPayout(CommandSender sender, Player target, String shopName, String currency, double value)
    {
        MultiCurrencyHook.CurrencyInfo info = CheckCanTrade(sender, currency);
        if (info == null)
            return;

        BigDecimal amount = MultiCurrencyHook.ToAmount(value, info.scale(), RoundingMode.FLOOR);
        if (amount == null || amount.signum() <= 0)
        {
            sender.sendMessage(DynamicShop.dsPrefix(sender) + t(sender, "ERR.WRONG_DATATYPE"));
            return;
        }

        MultiCurrencyOrder o = MultiCurrencyOrder.create(MultiCurrencyOrder.Type.PAYOUT);
        Fill(o, target, info.id(), amount, shopName, "", null, 0);
        o.priceSum = amount.doubleValue();
        o.requester = sender instanceof Player ? sender.getName() : "";

        if (!Start(o))
            sender.sendMessage(DynamicShop.dsPrefix(sender) + t(sender, "MESSAGE.MC_PAYMENT_FAILED").replace("{reason}", "JOURNAL_WRITE_FAILED"));
    }

    // =========================================================================================== engine

    private static void Fill(MultiCurrencyOrder o, Player player, String currencyId, BigDecimal amount, String shopName,
                             String tradeIdx, ItemStack item, int itemAmount)
    {
        o.player = player.getUniqueId();
        o.playerName = player.getName();
        o.currencyId = currencyId;
        o.amount = amount;
        o.shopName = shopName;
        o.tradeIdx = tradeIdx;
        if (item != null)
        {
            o.item = item.clone();
            o.item.setAmount(1);
        }
        o.itemAmount = itemAmount;
        o.session = SESSION;
        o.created = System.currentTimeMillis();
    }

    /** Journals the order (write-ahead), then sends the first attempt. False when the journal could not be written. */
    private static boolean Start(MultiCurrencyOrder o)
    {
        orders.put(o.journalId(), o);
        if (!SaveJournal())
        {
            orders.remove(o.journalId());
            return false;
        }

        inFlight.add(o.journalId());
        Attempt(o, 0, false);
        return true;
    }

    /** BUY could not be journaled: nothing was sent, put stock/limit back. */
    private static void AbortBeforeSend(MultiCurrencyOrder o, Player player)
    {
        orders.remove(o.journalId());
        RestoreStock(o);
        UndoTradeLimit(o);
        player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.MC_PAYMENT_FAILED").replace("{reason}", "JOURNAL_WRITE_FAILED"));
    }

    private static void Attempt(MultiCurrencyOrder o, int retryIndex, boolean mayAlreadyBeApplied)
    {
        if (!enabled)
            return;

        String reason = BuildReason(o);
        CompletableFuture<MultiCurrencyHook.Attempt> future = o.type.isCredit()
                ? MultiCurrencyHook.Deposit(o.player, o.currencyId, o.amount, reason, o.idempotencyKey())
                : MultiCurrencyHook.Withdraw(o.player, o.currencyId, o.amount, reason, o.idempotencyKey());

        future.whenComplete((a, err) -> Dispatch(() ->
                OnAttemptResult(o, (err != null || a == null) ? MultiCurrencyHook.Attempt.exception() : a, retryIndex, mayAlreadyBeApplied)));
    }

    /** Hops from the MultiCurrency database thread back to the server. */
    private static void Dispatch(Runnable task)
    {
        if (!enabled)
            return; // disabling: the order stays in the journal and is resolved on the next start
        try
        {
            SchedulerUtil.runGlobal(task);
        }
        catch (Throwable t)
        {
            // plugin is being disabled - same as above
        }
    }

    private static void OnAttemptResult(MultiCurrencyOrder o, MultiCurrencyHook.Attempt a, int retryIndex, boolean mayAlreadyBeApplied)
    {
        if (!orders.containsKey(o.journalId()))
        {
            inFlight.remove(o.journalId());
            return;
        }

        MultiCurrencyOutcome outcome = MultiCurrencyOutcome.Classify(a.success(), a.failureReason(), mayAlreadyBeApplied);

        if (outcome == MultiCurrencyOutcome.NOT_APPLIED && mayAlreadyBeApplied)
        {
            // Before cancelling an order whose earlier attempt might have committed, double-check the player's
            // MultiCurrency ledger for our key. Only a definite "not there" cancels it.
            MultiCurrencyHook.HistoryContainsKey(o.player, o.currencyId, o.idempotencyKey()).whenComplete((found, err) -> Dispatch(() ->
            {
                if (err == null && Boolean.TRUE.equals(found))
                    Finish(o, MultiCurrencyOutcome.APPLIED, a);
                else if (err == null && Boolean.FALSE.equals(found))
                    Finish(o, MultiCurrencyOutcome.NOT_APPLIED, a);
                else
                    Unresolved(o, retryIndex);
            }));
            return;
        }

        if (outcome == MultiCurrencyOutcome.UNKNOWN)
        {
            DynamicShop.plugin.getLogger().warning("MultiCurrency " + o.type + " " + o.idempotencyKey() + " for " + o.playerName
                    + ": outcome unknown (" + a.failureReason() + "), retrying with the same idempotency key");
            Unresolved(o, retryIndex);
            return;
        }

        Finish(o, outcome, a);
    }

    private static void Unresolved(MultiCurrencyOrder o, int retryIndex)
    {
        if (retryIndex < RETRY_DELAYS_TICKS.length)
        {
            SchedulerUtil.runGlobalDelayed(() -> Attempt(o, retryIndex + 1, true), RETRY_DELAYS_TICKS[retryIndex]);
            return;
        }

        // Give up the quick retries; ResolvePending() keeps trying the same key every minute (and on every start).
        inFlight.remove(o.journalId());
        if (!o.unknownNotified)
        {
            o.unknownNotified = true;
            Player p = Bukkit.getPlayer(o.player);
            if (p != null)
                SendToPlayer(p, DynamicShop.dsPrefix(p) + t(p, "MESSAGE.MC_OUTCOME_UNKNOWN"));
        }
    }

    /** Periodic: retries every unresolved order with its original idempotency key. */
    private static void ResolvePending()
    {
        if (!enabled || !MultiCurrencyHook.IsAvailable())
            return;

        for (MultiCurrencyOrder o : orders.values())
        {
            if (o.state == MultiCurrencyOrder.State.PENDING && inFlight.add(o.journalId()))
                Attempt(o, RETRY_DELAYS_TICKS.length, true); // one try per round; any earlier attempt may have committed
        }
    }

    private static void Finish(MultiCurrencyOrder o, MultiCurrencyOutcome outcome, MultiCurrencyHook.Attempt a)
    {
        inFlight.remove(o.journalId());
        if (orders.get(o.journalId()) != o || o.state != MultiCurrencyOrder.State.PENDING)
            return;

        boolean applied = outcome == MultiCurrencyOutcome.APPLIED;
        o.balanceAfter = a.balanceAfter();
        MultiCurrencyHook.UpdateCachedBalance(o.player, o.currencyId, a.balanceAfter());
        Player p = Bukkit.getPlayer(o.player);

        switch (o.type)
        {
            case BUY:
                if (applied)
                {
                    o.state = MultiCurrencyOrder.State.OWE_ITEMS;
                    SaveJournal();
                    if (p != null)
                        SchedulerUtil.runForEntity(p, () -> Deliver(p, o), null);
                }
                else
                {
                    orders.remove(o.journalId());
                    SaveJournal();
                    RestoreStock(o);
                    UndoTradeLimit(o);
                    if (p != null)
                        SendToPlayer(p, FailureMessage(p, o, a.failureReason()));
                }
                break;

            case SELL:
                if (applied)
                {
                    orders.remove(o.journalId());
                    SaveJournal();
                    ApplySellEffects(o);
                }
                else
                {
                    o.state = MultiCurrencyOrder.State.OWE_ITEMS; // give the items back
                    SaveJournal();
                    UndoTradeLimit(o);
                    if (p != null)
                    {
                        SendToPlayer(p, DynamicShop.dsPrefix(p) + t(p, "MESSAGE.MC_SELL_FAILED").replace("{reason}", ReasonText(a.failureReason())));
                        SchedulerUtil.runForEntity(p, () -> Deliver(p, o), null);
                    }
                }
                break;

            case REFUND:
                if (applied)
                {
                    orders.remove(o.journalId());
                    SaveJournal();
                    if (p != null)
                        SendToPlayer(p, DynamicShop.dsPrefix(p) + t(p, "MESSAGE.MC_REFUNDED").replace("{price}", MultiCurrencyHook.FormatAmount(o.currencyId, o.amount)));
                }
                else
                {
                    o.state = MultiCurrencyOrder.State.REVIEW;
                    SaveJournal();
                    LogReview(o, "refund was refused by MultiCurrency (" + a.failureReason() + ")");
                }
                break;

            case PAYOUT:
                orders.remove(o.journalId());
                SaveJournal();
                if (applied && ShopUtil.shopConfigFiles.containsKey(o.shopName))
                {
                    ShopUtil.addShopBalance(o.shopName, o.priceSum * -1);
                    ShopUtil.shopConfigFiles.get(o.shopName).save();
                }
                NotifyRequester(o, applied
                        ? t(null, "MESSAGE.TRANSFER_SUCCESS")
                        : t(null, "MESSAGE.MC_PAYMENT_FAILED").replace("{reason}", ReasonText(a.failureReason())));
                if (applied && p != null)
                    SendToPlayer(p, DynamicShop.dsPrefix(p) + t(p, "MESSAGE.MC_PAYOUT_RECEIVED").replace("{price}", MultiCurrencyHook.FormatAmount(o.currencyId, o.amount)));
                break;
        }
    }

    // =========================================================================================== delivery

    /** Gives the items an OWE_ITEMS order owes. Must run on the player's entity thread. */
    private static void Deliver(Player player, MultiCurrencyOrder o)
    {
        if (!player.isOnline() || orders.get(o.journalId()) != o || o.state != MultiCurrencyOrder.State.OWE_ITEMS)
            return;

        if (o.item == null || o.item.getType().isAir() || o.itemAmount <= 0)
        {
            if (o.type == MultiCurrencyOrder.Type.BUY && o.amount.signum() > 0)
            {
                // Paid but the item cannot be rebuilt: give the money back instead (separate idempotency key).
                MultiCurrencyOrder refund = new MultiCurrencyOrder(MultiCurrencyOrder.Type.REFUND, o.baseId);
                refund.player = o.player;
                refund.playerName = o.playerName;
                refund.currencyId = o.currencyId;
                refund.amount = o.amount;
                refund.shopName = o.shopName;
                refund.tradeIdx = o.tradeIdx;
                refund.session = SESSION;
                refund.created = System.currentTimeMillis();
                orders.remove(o.journalId());
                if (Start(refund))
                    return;
                orders.put(o.journalId(), o);
            }
            o.state = MultiCurrencyOrder.State.REVIEW;
            SaveJournal();
            LogReview(o, "the order's item could not be read, nothing was given");
            return;
        }

        o.state = MultiCurrencyOrder.State.GIVING;
        if (!SaveJournal())
        {
            o.state = MultiCurrencyOrder.State.OWE_ITEMS; // try again on next join; do not give without a record
            return;
        }

        GiveItems(player, o.item, o.itemAmount);
        player.saveData();

        orders.remove(o.journalId());
        SaveJournal();

        try
        {
            if (o.type == MultiCurrencyOrder.Type.BUY)
                ApplyBuyEffects(player, o);
            else
                SendItemMessage(player, DynamicShop.dsPrefix(player) + "{msg}", "MESSAGE.MC_ITEMS_RETURNED", o, null);
        }
        catch (RuntimeException e)
        {
            DynamicShop.plugin.getLogger().log(Level.WARNING, "MultiCurrency order " + o.idempotencyKey() + " delivered, but a follow-up step failed", e);
        }
    }

    /** Adds items like Buy.buy() does (full stacks, leftovers dropped at the player's feet). */
    private static void GiveItems(Player player, ItemStack template, int amount)
    {
        int left = amount;
        int maxStackSize = Math.max(1, template.getMaxStackSize());
        while (left > 0)
        {
            int give = Math.min(maxStackSize, left);
            ItemStack stack = template.clone();
            stack.setAmount(give);

            HashMap<Integer, ItemStack> leftOver = player.getInventory().addItem(stack);
            if (!leftOver.isEmpty())
            {
                player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.INVENTORY_FULL"));
                for (ItemStack rest : leftOver.values())
                    player.getWorld().dropItem(player.getLocation(), rest);
            }
            left -= give;
        }
    }

    // =========================================================================================== effects

    /** What Buy.buy() does after a successful payment, now that the items are delivered. */
    private static void ApplyBuyEffects(Player player, MultiCurrencyOrder o)
    {
        String currency = Constants.S_MULTICURRENCY_PREFIX + o.currencyId;
        boolean shopExists = ShopUtil.shopConfigFiles.containsKey(o.shopName);
        ItemStack itemStack = o.item.clone();
        itemStack.setAmount(Math.min(o.itemAmount, Math.max(1, itemStack.getMaxStackSize())));

        LogUtil.addLog(o.shopName, o.item.getType().toString(), o.itemAmount, o.priceSum, currency, player.getName());

        if (SESSION.equals(o.session))
            SendItemMessage(player, DynamicShop.dsPrefix(player) + "{msg}", "MESSAGE.MC_BUY_SUCCESS", o, o.balanceAfter);
        else
            SendItemMessage(player, DynamicShop.dsPrefix(player) + "{msg}", "MESSAGE.MC_ITEMS_DELIVERED", o, o.balanceAfter);

        SoundUtil.playerSoundEffect(player, "buy");

        if (!shopExists)
            return;

        CustomConfig data = ShopUtil.shopConfigFiles.get(o.shopName);
        if (data.get().contains("Options.Balance"))
            ShopUtil.addShopBalance(o.shopName, o.priceSum);

        Buy.RunBuyCommand(data, player.getName(), o.shopName, itemStack, o.itemAmount, o.priceSum);
        ShopUtil.shopDirty.put(o.shopName, true);

        if (o.reopenGui && UIManager.GetPlayerCurrentUIType(player) == InGameUI.UI_TYPE.ItemTrade)
            DynaShopAPI.openItemTradeGui(player, o.shopName, o.tradeIdx);

        if (o.hasOldValues)
        {
            ShopBuySellEvent event = new ShopBuySellEvent(true, o.priceBuyOld, Calc.getCurrentPrice(o.shopName, o.tradeIdx, true),
                    o.priceSellOld, DynaShopAPI.getSellPrice(o.shopName, itemStack), o.stockOld, DynaShopAPI.getStock(o.shopName, itemStack),
                    DynaShopAPI.getMedian(o.shopName, itemStack), o.shopName, itemStack, player);
            Bukkit.getPluginManager().callEvent(event);
        }
    }

    /** What Sell.sell()/quickSellItem() do after a successful payment. Runs on the global thread. */
    private static void ApplySellEffects(MultiCurrencyOrder o)
    {
        String currency = Constants.S_MULTICURRENCY_PREFIX + o.currencyId;
        boolean shopExists = ShopUtil.shopConfigFiles.containsKey(o.shopName);
        Player player = Bukkit.getPlayer(o.player);

        LogUtil.addLog(o.shopName, o.item == null ? "?" : o.item.getType().toString(), -o.itemAmount, o.priceSum, currency, o.playerName);

        if (shopExists)
        {
            CustomConfig data = ShopUtil.shopConfigFiles.get(o.shopName);
            if (data.get().contains("Options.Balance"))
                ShopUtil.addShopBalance(o.shopName, o.priceSum * -1);

            if (o.addStockOnSuccess && SameItemAtIndex(data, o))
            {
                int stock = data.get().getInt(o.tradeIdx + ".stock");
                if (stock > 0)
                    data.get().set(o.tradeIdx + ".stock", MathUtil.SafeAdd(stock, o.itemAmount));
            }

            ItemStack itemStack = o.item == null ? null : o.item.clone();
            if (itemStack != null)
                itemStack.setAmount(Math.min(o.itemAmount, Math.max(1, itemStack.getMaxStackSize())));

            if (itemStack != null)
                Sell.RunSellCommand(data, o.playerName, o.shopName, itemStack, o.itemAmount, o.priceSum, o.tax);
            ShopUtil.shopDirty.put(o.shopName, true);

            if (player != null && itemStack != null && o.hasOldValues)
            {
                ShopBuySellEvent event = new ShopBuySellEvent(false, o.priceBuyOld, Calc.getCurrentPrice(o.shopName, o.tradeIdx, true),
                        o.priceSellOld, DynaShopAPI.getSellPrice(o.shopName, itemStack), o.stockOld, DynaShopAPI.getStock(o.shopName, itemStack),
                        DynaShopAPI.getMedian(o.shopName, itemStack), o.shopName, itemStack, player);
                Bukkit.getPluginManager().callEvent(event);
            }
        }

        if (player == null)
            return;

        SchedulerUtil.runForEntity(player, () ->
        {
            SendItemMessage(player, DynamicShop.dsPrefix(player) + "{msg}", "MESSAGE.MC_SELL_SUCCESS", o, o.balanceAfter);
            if ("sell".equals(o.sound))
                SoundUtil.playerSoundEffect(player, "sell");
            else if ("orb".equals(o.sound))
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);

            if (o.reopenGui && shopExists && UIManager.GetPlayerCurrentUIType(player) == InGameUI.UI_TYPE.ItemTrade)
                DynaShopAPI.openItemTradeGui(player, o.shopName, o.tradeIdx);
        }, null);
    }

    /** Puts reserved stock back for a BUY that was not paid (same server run only: see below). */
    private static void RestoreStock(MultiCurrencyOrder o)
    {
        // Across a restart we cannot know whether the reservation reached the shop file (shops are saved every
        // ~10s), so adding it back could create stock out of thin air. Only restore within the same run.
        if (!o.stockReserved || !SESSION.equals(o.session) || !ShopUtil.shopConfigFiles.containsKey(o.shopName))
            return;

        CustomConfig data = ShopUtil.shopConfigFiles.get(o.shopName);
        if (!SameItemAtIndex(data, o))
            return;

        int stock = data.get().getInt(o.tradeIdx + ".stock");
        if (stock > 0)
        {
            data.get().set(o.tradeIdx + ".stock", MathUtil.SafeAdd(stock, o.itemAmount));
            ShopUtil.shopDirty.put(o.shopName, true);
        }
    }

    private static void UndoTradeLimit(MultiCurrencyOrder o)
    {
        if (!o.tradeLimitRecorded || o.item == null || !SESSION.equals(o.session))
            return;
        Player p = Bukkit.getPlayer(o.player);
        if (p == null)
            return;
        // OnPlayerTradeLimitedItem(isSell) subtracts, so the opposite direction undoes the recorded volume.
        UserUtil.OnPlayerTradeLimitedItem(p, o.shopName, HashUtil.GetItemHash(o.item), o.itemAmount, o.type == MultiCurrencyOrder.Type.BUY);
        o.tradeLimitRecorded = false;
    }

    private static boolean SameItemAtIndex(CustomConfig data, MultiCurrencyOrder o)
    {
        return o.item != null && o.item.getType().name().equals(data.get().getString(o.tradeIdx + ".mat"));
    }

    // =========================================================================================== messages

    private static String BuildReason(MultiCurrencyOrder o)
    {
        String what = o.item == null ? "" : " " + o.itemAmount + "x " + o.item.getType();
        return "DynamicShop " + o.type.name().toLowerCase() + what + " @ " + o.shopName + " (" + o.playerName + ")";
    }

    private static String ReasonText(String failureReason)
    {
        return failureReason == null ? "UNKNOWN" : failureReason;
    }

    private static String FailureMessage(Player p, MultiCurrencyOrder o, String failureReason)
    {
        if ("INSUFFICIENT_FUNDS".equals(failureReason))
            return DynamicShop.dsPrefix(p) + t(p, "MESSAGE.MC_NOT_ENOUGH").replace("{price}", MultiCurrencyHook.FormatAmount(o.currencyId, o.amount));
        return DynamicShop.dsPrefix(p) + t(p, "MESSAGE.MC_PAYMENT_FAILED").replace("{reason}", ReasonText(failureReason));
    }

    private static void SendItemMessage(Player player, String wrapper, String key, MultiCurrencyOrder o, BigDecimal balance)
    {
        ItemStack item = o.item;
        boolean itemHasCustomName = item != null && item.getItemMeta() != null && item.getItemMeta().hasDisplayName();
        boolean useLocalizedName = item != null && !itemHasCustomName && ConfigUtil.GetLocalizedItemName();

        String message = wrapper.replace("{msg}", t(player, key, !useLocalizedName))
                .replace("{amount}", Integer.toString(o.itemAmount))
                .replace("{price}", MultiCurrencyHook.FormatAmount(o.currencyId, o.amount))
                .replace("{bal}", MultiCurrencyHook.FormatAmount(o.currencyId, balance));

        if (useLocalizedName)
        {
            LangUtil.sendMessageWithLocalizedItemName(player, message.replace("{item}", "<item>"), item.getType());
        }
        else
        {
            String itemName = item == null ? "?" : itemHasCustomName ? item.getItemMeta().getDisplayName() : ItemsUtil.getBeautifiedName(item.getType());
            player.sendMessage(message.replace("{item}", itemName));
        }
    }

    private static void SendToPlayer(Player p, String message)
    {
        SchedulerUtil.runForEntity(p, () -> p.sendMessage(message), null);
    }

    private static void NotifyRequester(MultiCurrencyOrder o, String message)
    {
        Player requester = o.requester.isEmpty() ? null : Bukkit.getPlayerExact(o.requester);
        if (requester != null)
            SendToPlayer(requester, DynamicShop.dsPrefix(requester) + message);
        else
            DynamicShop.console.sendMessage(DynamicShop.dsPrefix_ + message);
    }

    private static void LogReview(MultiCurrencyOrder o, String why)
    {
        DynamicShop.plugin.getLogger().severe("MultiCurrency order needs an admin: " + why + ". type=" + o.type + " key=" + o.idempotencyKey()
                + " player=" + o.playerName + " (" + o.player + ") currency=" + o.currencyId + " amount=" + o.amount.toPlainString()
                + " shop=" + o.shopName + " item=" + (o.item == null ? "?" : o.item.getType()) + " x" + o.itemAmount
                + ". The entry stays in " + JOURNAL_FILE + " (state REVIEW); delete it there once handled.");
    }

    // =========================================================================================== journal

    private static File JournalFile()
    {
        return new File(DynamicShop.plugin.getDataFolder(), JOURNAL_FILE);
    }

    private static volatile boolean journalLoaded;

    private static synchronized void LoadJournal()
    {
        orders.clear();
        journalLoaded = true;
        File file = JournalFile();
        if (!file.exists())
            return;

        YamlConfiguration yml = new YamlConfiguration();
        try
        {
            yml.load(file);
        }
        catch (IOException | InvalidConfigurationException e)
        {
            // Never overwrite an unreadable journal: move it aside and make noise.
            File aside = new File(file.getParentFile(), JOURNAL_FILE + ".unreadable-" + System.currentTimeMillis());
            boolean moved = file.renameTo(aside);
            DynamicShop.plugin.getLogger().log(Level.SEVERE, JOURNAL_FILE + " could not be read" + (moved ? " and was moved to " + aside.getName() : "")
                    + ". Unfinished MultiCurrency orders in it must be checked by hand.", e);
            return;
        }

        if (yml.getConfigurationSection("orders") == null)
            return;

        for (String id : yml.getConfigurationSection("orders").getKeys(false))
        {
            MultiCurrencyOrder o = MultiCurrencyOrder.readFrom(yml.getConfigurationSection("orders." + id));
            if (o == null)
            {
                DynamicShop.plugin.getLogger().severe(JOURNAL_FILE + ": entry '" + id + "' is not a valid order and is ignored (left untouched in a backup copy).");
                BackupJournalOnce(file);
                continue;
            }
            orders.put(o.journalId(), o);
        }
    }

    private static boolean backedUp;

    private static void BackupJournalOnce(File file)
    {
        if (backedUp)
            return;
        backedUp = true;
        try
        {
            Files.copy(file.toPath(), new File(file.getParentFile(), JOURNAL_FILE + ".bak-" + System.currentTimeMillis()).toPath());
        }
        catch (IOException ignored)
        {
        }
    }

    /** Writes every open order atomically (temp file + fsync + atomic rename). */
    static synchronized boolean SaveJournal()
    {
        // Only after LoadJournal(): saving an empty map before that would erase unfinished orders on disk.
        if (DynamicShop.plugin == null || !journalLoaded)
            return false;

        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(List.of(
                "Unfinished DynamicShop <-> MultiCurrency orders. Managed by DynamicShop - do not edit while the server runs.",
                "Entries in state REVIEW need an admin (see the server log); delete them once handled."));
        yml.set("version", 1);
        List<MultiCurrencyOrder> snapshot = new ArrayList<>(orders.values());
        for (MultiCurrencyOrder o : snapshot)
            o.writeTo(yml.createSection("orders." + o.journalId()));

        try
        {
            AtomicWrite(JournalFile().toPath(), yml.saveToString());
            return true;
        }
        catch (IOException | RuntimeException e)
        {
            DynamicShop.plugin.getLogger().log(Level.SEVERE, "Could not write " + JOURNAL_FILE, e);
            return false;
        }
    }

    static void AtomicWrite(Path target, String content) throws IOException
    {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE))
        {
            channel.force(true);
        }
        try
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (java.nio.file.AtomicMoveNotSupportedException e)
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
