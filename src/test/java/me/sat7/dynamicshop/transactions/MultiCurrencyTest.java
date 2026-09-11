package me.sat7.dynamicshop.transactions;

import me.sat7.dynamicshop.constants.Constants;
import me.sat7.dynamicshop.economyhook.MultiCurrencyHook;
import me.sat7.dynamicshop.utilities.ShopUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static me.sat7.dynamicshop.transactions.MultiCurrencyOutcome.*;
import static org.junit.Assert.*;

public class MultiCurrencyTest
{
    // ------------------------------------------------------------------ outcome classification

    @Test
    public void successIsApplied()
    {
        assertEquals(APPLIED, Classify(true, null, false));
        assertEquals(APPLIED, Classify(true, null, true));
    }

    @Test
    public void duplicateMeansAnEarlierAttemptCommitted()
    {
        assertEquals(APPLIED, Classify(false, "DUPLICATE_TRANSACTION", true));
        assertEquals(APPLIED, Classify(false, "DUPLICATE_TRANSACTION", false));
    }

    @Test
    public void outcomeUnknownIsNeverTreatedAsFailure()
    {
        assertEquals(UNKNOWN, Classify(false, "OUTCOME_UNKNOWN", false));
        assertEquals(UNKNOWN, Classify(false, "OUTCOME_UNKNOWN", true));
        assertEquals(UNKNOWN, Classify(false, MultiCurrencyHook.REASON_EXCEPTION, false));
        assertEquals(UNKNOWN, Classify(false, null, false));
    }

    @Test
    public void firstAttemptRejectionsAreNotApplied()
    {
        for (String r : new String[]{"INSUFFICIENT_FUNDS", "UNKNOWN_CURRENCY", "CURRENCY_DISABLED", "INVALID_AMOUNT",
                "INVALID_PRECISION", "AMOUNT_TOO_LARGE", "BALANCE_LIMIT_EXCEEDED", "INVALID_ARGUMENT",
                "SERVICE_UNAVAILABLE", "STORAGE_ERROR", "SOME_FUTURE_REASON"})
            assertEquals(r, NOT_APPLIED, Classify(false, r, false));
    }

    @Test
    public void afterAPossibleCommitOnlyInTransactionRejectionsSettleIt()
    {
        // checked inside MultiCurrency's DB transaction after the idempotency-key lookup
        assertEquals(NOT_APPLIED, Classify(false, "INSUFFICIENT_FUNDS", true));
        assertEquals(NOT_APPLIED, Classify(false, "BALANCE_LIMIT_EXCEEDED", true));

        // these only say the retry did nothing - the earlier attempt may still have committed
        for (String r : new String[]{"CURRENCY_DISABLED", "UNKNOWN_CURRENCY", "SERVICE_UNAVAILABLE", "STORAGE_ERROR",
                "INVALID_PRECISION", "SOME_FUTURE_REASON"})
            assertEquals(r, UNKNOWN, Classify(false, r, true));
    }

    // ------------------------------------------------------------------ idempotency keys

    @Test
    public void keyIsDeterministicPerOrderAndDistinctForRefunds()
    {
        MultiCurrencyOrder buy = MultiCurrencyOrder.create(MultiCurrencyOrder.Type.BUY);
        String key = buy.idempotencyKey();
        assertEquals(key, buy.idempotencyKey());
        assertEquals("dynamicshop3:buy:" + buy.baseId, key);

        // a retry, or the same order read back after a restart, uses the same key
        assertEquals(key, new MultiCurrencyOrder(MultiCurrencyOrder.Type.BUY, buy.baseId).idempotencyKey());

        MultiCurrencyOrder refund = new MultiCurrencyOrder(MultiCurrencyOrder.Type.REFUND, buy.baseId);
        assertNotEquals(key, refund.idempotencyKey());
        assertEquals("dynamicshop3:refund:" + buy.baseId, refund.idempotencyKey());
        assertNotEquals(buy.journalId(), refund.journalId());

        // two logical purchases never share a key
        assertNotEquals(key, MultiCurrencyOrder.create(MultiCurrencyOrder.Type.BUY).idempotencyKey());

        for (MultiCurrencyOrder.Type t : MultiCurrencyOrder.Type.values())
            assertTrue(MultiCurrencyOrder.create(t).idempotencyKey().length() <= 128);
    }

    @Test
    public void orderSurvivesTheJournalRoundTrip()
    {
        MultiCurrencyOrder o = MultiCurrencyOrder.create(MultiCurrencyOrder.Type.SELL);
        o.state = MultiCurrencyOrder.State.OWE_ITEMS;
        o.player = UUID.randomUUID();
        o.playerName = "Steve";
        o.currencyId = "gems";
        o.amount = new BigDecimal("12.50");
        o.shopName = "Gems";
        o.tradeIdx = "3";
        o.itemAmount = 16;
        o.addStockOnSuccess = true;
        o.tradeLimitRecorded = true;
        o.priceSum = 12.5;
        o.tax = 1.25;
        o.session = "s1";
        o.created = 123L;

        YamlConfiguration yml = new YamlConfiguration();
        o.writeTo(yml.createSection("orders." + o.journalId()));
        YamlConfiguration reloaded = new YamlConfiguration();
        try
        {
            reloaded.loadFromString(yml.saveToString());
        }
        catch (Exception e)
        {
            throw new AssertionError(e);
        }

        MultiCurrencyOrder r = MultiCurrencyOrder.readFrom(reloaded.getConfigurationSection("orders." + o.journalId()));
        assertNotNull(r);
        assertEquals(o.idempotencyKey(), r.idempotencyKey());
        assertEquals(o.journalId(), r.journalId());
        assertEquals(o.type, r.type);
        assertEquals(o.state, r.state);
        assertEquals(o.player, r.player);
        assertEquals("gems", r.currencyId);
        assertEquals(new BigDecimal("12.50"), r.amount);
        assertEquals(16, r.itemAmount);
        assertTrue(r.addStockOnSuccess);
        assertTrue(r.tradeLimitRecorded);
        assertEquals("s1", r.session);
        assertNull(r.item);
    }

    @Test
    public void unreadableOrderIsRejectedNotGuessed()
    {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("a.type", "BUY");
        yml.set("a.id", "not-a-uuid");
        assertNull(MultiCurrencyOrder.readFrom(yml.getConfigurationSection("a")));

        yml.set("b.type", "NOPE");
        yml.set("b.id", UUID.randomUUID().toString());
        assertNull(MultiCurrencyOrder.readFrom(yml.getConfigurationSection("b")));
    }

    // ------------------------------------------------------------------ amounts

    @Test
    public void chargesRoundUpPayoutsRoundDownToTheCurrencyScale()
    {
        assertEquals(new BigDecimal("12.31"), MultiCurrencyHook.ToAmount(12.301, 2, RoundingMode.CEILING));
        assertEquals(new BigDecimal("12.30"), MultiCurrencyHook.ToAmount(12.301, 2, RoundingMode.FLOOR));
        assertEquals(new BigDecimal("13"), MultiCurrencyHook.ToAmount(12.01, 0, RoundingMode.CEILING));
        assertEquals(new BigDecimal("12"), MultiCurrencyHook.ToAmount(12.99, 0, RoundingMode.FLOOR));
    }

    @Test
    public void floatingPointNoiseIsNotCharged()
    {
        // 0.1 + 0.2 = 0.30000000000000004 must be charged 0.30, not 0.31
        assertEquals(new BigDecimal("0.30"), MultiCurrencyHook.ToAmount(0.1 + 0.2, 2, RoundingMode.CEILING));
        assertEquals(new BigDecimal("12.30"), MultiCurrencyHook.ToAmount(12.300000000001, 2, RoundingMode.CEILING));
        assertEquals(new BigDecimal("12"), MultiCurrencyHook.ToAmount(11.9999999999999, 0, RoundingMode.FLOOR));
    }

    @Test
    public void amountsNeverExceedTheScaleMultiCurrencyAccepts()
    {
        BigDecimal a = MultiCurrencyHook.ToAmount(1.23456789123, 8, RoundingMode.CEILING);
        assertTrue(a.scale() <= 8);
        assertEquals(0, MultiCurrencyHook.ToAmount(5, 0, RoundingMode.CEILING).scale());
        assertNull(MultiCurrencyHook.ToAmount(Double.NaN, 2, RoundingMode.CEILING));
        assertNull(MultiCurrencyHook.ToAmount(Double.POSITIVE_INFINITY, 2, RoundingMode.CEILING));
    }

    @Test
    public void currencyIdValidation()
    {
        assertTrue(MultiCurrencyHook.IsValidCurrencyId("gems"));
        assertTrue(MultiCurrencyHook.IsValidCurrencyId("event_points_2"));
        assertFalse(MultiCurrencyHook.IsValidCurrencyId(""));
        assertFalse(MultiCurrencyHook.IsValidCurrencyId("Gems"));
        assertFalse(MultiCurrencyHook.IsValidCurrencyId("a b"));
        assertFalse(MultiCurrencyHook.IsValidCurrencyId("x".repeat(33)));
        assertFalse(MultiCurrencyHook.IsValidCurrencyId(null));
    }

    // ------------------------------------------------------------------ shop currency setting

    @Test
    public void shopCurrencyParsing()
    {
        YamlConfiguration shop = new YamlConfiguration();
        assertEquals(Constants.S_VAULT, ShopUtil.GetCurrency(shop));

        shop.set("Options.currency", "Vault");
        assertEquals(Constants.S_VAULT, ShopUtil.GetCurrency(shop));
        shop.set("Options.currency", "exp");
        assertEquals(Constants.S_EXP, ShopUtil.GetCurrency(shop));
        shop.set("Options.currency", "jp");
        assertEquals(Constants.S_VAULT, ShopUtil.GetCurrency(shop)); // unchanged legacy behaviour

        shop.set("Options.currency", "multicurrency:Gems");
        assertEquals("MultiCurrency:gems", ShopUtil.GetCurrency(shop));
        assertTrue(ShopUtil.IsMultiCurrency(ShopUtil.GetCurrency(shop)));
        assertEquals("gems", ShopUtil.GetMultiCurrencyId(ShopUtil.GetCurrency(shop)));

        // a MultiCurrency shop with a broken id must not fall back to Vault money
        shop.set("Options.currency", "MultiCurrency:");
        assertTrue(ShopUtil.IsMultiCurrency(ShopUtil.GetCurrency(shop)));
        assertFalse(MultiCurrencyHook.IsValidCurrencyId(ShopUtil.GetMultiCurrencyId(ShopUtil.GetCurrency(shop))));

        assertFalse(ShopUtil.IsMultiCurrency(Constants.S_VAULT));
        assertFalse(ShopUtil.IsMultiCurrency(null));
        assertEquals("", ShopUtil.GetMultiCurrencyId(Constants.S_VAULT));
    }

    // ------------------------------------------------------------------ journal file

    @Test
    public void journalWriteIsAtomicAndLeavesNoTempFile() throws Exception
    {
        Path dir = Files.createTempDirectory("dshop-journal");
        Path file = dir.resolve("MultiCurrencyOrders.yml");

        MultiCurrencyTrade.AtomicWrite(file, "version: 1\n");
        assertEquals("version: 1\n", Files.readString(file));
        MultiCurrencyTrade.AtomicWrite(file, "version: 2\n");
        assertEquals("version: 2\n", Files.readString(file));
        assertFalse(Files.exists(dir.resolve("MultiCurrencyOrders.yml.tmp")));
    }
}
