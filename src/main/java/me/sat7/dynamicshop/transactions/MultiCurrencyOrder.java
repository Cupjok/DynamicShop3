package me.sat7.dynamicshop.transactions;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

/**
 * One logical MultiCurrency trade, persisted in MultiCurrencyOrders.yml before any money moves.
 *
 * The order id ({@link #baseId}) is generated once when the player clicks and is stored before the first API
 * call, so every retry - including after a restart - sends the identical idempotency key.
 */
public final class MultiCurrencyOrder
{
    public enum Type
    {
        BUY,     // withdraw, then give items
        SELL,    // items taken, then deposit (items returned if not paid)
        REFUND,  // deposit back a BUY that was paid but cannot be delivered
        PAYOUT;  // shop account -> player (/ds shop <shop> account transfer <player>)

        boolean isCredit()
        {
            return this != BUY;
        }
    }

    public enum State
    {
        PENDING,   // money call not resolved yet (retried with the same key until it is)
        OWE_ITEMS, // BUY paid, or SELL not paid: items must be given to the player
        GIVING,    // items were being handed out when the server stopped: needs an admin, never automatic
        REVIEW     // cannot be resolved automatically; kept in the file and logged for an admin
    }

    public static final String KEY_NAMESPACE = "dynamicshop3";

    public final Type type;
    public final String baseId;
    public State state = State.PENDING;

    public UUID player;
    public String playerName = "";
    public String currencyId = "";
    public BigDecimal amount = BigDecimal.ZERO;
    public String shopName = "";
    public String tradeIdx = "";
    public ItemStack item;      // one item (amount 1); itemAmount says how many
    public int itemAmount;
    public boolean stockReserved;     // BUY: stock was already decremented when the order was placed
    public boolean addStockOnSuccess; // SELL: add itemAmount to stock once paid
    public boolean tradeLimitRecorded;
    public double priceSum;           // DynamicShop price (log, shop account, commands)
    public double tax;
    public String requester = "";     // PAYOUT: who issued the command
    public String session = "";
    public long created;

    // Runtime only - not persisted.
    transient boolean reopenGui;
    transient String sound;
    transient boolean hasOldValues;
    transient double priceBuyOld;
    transient double priceSellOld;
    transient int stockOld;
    transient BigDecimal balanceAfter;
    transient boolean unknownNotified;

    public MultiCurrencyOrder(Type type, String baseId)
    {
        this.type = type;
        this.baseId = baseId;
    }

    public static MultiCurrencyOrder create(Type type)
    {
        return new MultiCurrencyOrder(type, UUID.randomUUID().toString());
    }

    /** Stable for the whole life of the order; different for BUY and its REFUND. At most 128 chars (API limit). */
    public String idempotencyKey()
    {
        return KEY_NAMESPACE + ":" + type.name().toLowerCase(Locale.ROOT) + ":" + baseId;
    }

    public String journalId()
    {
        return type.name().toLowerCase(Locale.ROOT) + "_" + baseId;
    }

    public void writeTo(ConfigurationSection s)
    {
        s.set("type", type.name());
        s.set("id", baseId);
        s.set("state", state.name());
        s.set("key", idempotencyKey()); // informational, for admins matching the MultiCurrency ledger
        s.set("player", player == null ? null : player.toString());
        s.set("playerName", playerName);
        s.set("currency", currencyId);
        s.set("amount", amount.toPlainString());
        s.set("shop", shopName);
        s.set("tradeIdx", tradeIdx);
        s.set("item", item);
        s.set("itemAmount", itemAmount);
        s.set("stockReserved", stockReserved);
        s.set("addStockOnSuccess", addStockOnSuccess);
        s.set("tradeLimitRecorded", tradeLimitRecorded);
        s.set("priceSum", priceSum);
        s.set("tax", tax);
        s.set("requester", requester);
        s.set("session", session);
        s.set("created", created);
    }

    /** @return the order, or null when the section is not a readable order */
    public static MultiCurrencyOrder readFrom(ConfigurationSection s)
    {
        try
        {
            Type type = Type.valueOf(s.getString("type", ""));
            String id = s.getString("id", "");
            UUID.fromString(id);

            MultiCurrencyOrder o = new MultiCurrencyOrder(type, id);
            o.state = State.valueOf(s.getString("state", State.PENDING.name()));
            o.player = UUID.fromString(s.getString("player", ""));
            o.playerName = s.getString("playerName", "");
            o.currencyId = s.getString("currency", "");
            o.amount = new BigDecimal(s.getString("amount", "0"));
            o.shopName = s.getString("shop", "");
            o.tradeIdx = s.getString("tradeIdx", "");
            try
            {
                o.item = s.getItemStack("item");
            }
            catch (RuntimeException e)
            {
                o.item = null; // handled by the delivery path (refund or admin review)
            }
            o.itemAmount = s.getInt("itemAmount");
            o.stockReserved = s.getBoolean("stockReserved");
            o.addStockOnSuccess = s.getBoolean("addStockOnSuccess");
            o.tradeLimitRecorded = s.getBoolean("tradeLimitRecorded");
            o.priceSum = s.getDouble("priceSum");
            o.tax = s.getDouble("tax");
            o.requester = s.getString("requester", "");
            o.session = s.getString("session", "");
            o.created = s.getLong("created");
            return o;
        }
        catch (RuntimeException e)
        {
            return null;
        }
    }
}
