package me.sat7.dynamicshop.guis;

import java.util.ArrayList;
import java.util.Arrays;

import me.sat7.dynamicshop.DynaShopAPI;
import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.files.CustomConfig;
import me.sat7.dynamicshop.utilities.RandomPriceUtil;
import me.sat7.dynamicshop.utilities.ShopUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import static me.sat7.dynamicshop.utilities.LangUtil.t;
import static me.sat7.dynamicshop.utilities.MathUtil.Clamp;

// 랜덤 가격 설정 화면
public final class RandomPriceSettings extends InGameUI
{
    public RandomPriceSettings()
    {
        uiType = UI_TYPE.RandomPriceSettings;
    }

    private final int ENABLE_TOGGLE = 0;
    private final int TARGET = 1;
    private final int MIN_PERCENT = 2;
    private final int MAX_PERCENT = 3;
    private final int REROLL = 5;

    private final int TIMER_TOGGLE = 9;
    private final int PERIOD = 10;
    private final int HOUR = 11;
    private final int MINUTE = 12;
    private final int DAY_OF_WEEK = 13;
    private final int DAY_OF_MONTH = 14;
    private final int NEXT_RESET = 16;

    private final int CLOSE = 45;

    private String shopName;

    public Inventory getGui(Player player, String shopName)
    {
        this.shopName = shopName;

        inventory = Bukkit.createInventory(player, 54, t(player, "RANDOM_PRICE.TITLE") + "§7 | §8" + shopName);

        FileConfiguration data = ShopUtil.shopConfigFiles.get(shopName).get();

        CreateButton(CLOSE, InGameUI.GetCloseButtonIconMat(), t(player, "RANDOM_PRICE.BACK"), t(player, "RANDOM_PRICE.BACK_LORE"));

        boolean enabled = RandomPriceUtil.IsEnabled(data);
        ArrayList<String> enableLore = new ArrayList<>(Arrays.asList(
                t(player, "RANDOM_PRICE.ENABLE_LORE"),
                "§9" + t(player, "CUR_STATE") + ": " + (enabled ? t(player, "ON") : t(player, "OFF")),
                "§e" + t(player, "CLICK") + ": " + (enabled ? t(player, "OFF") : t(player, "ON"))));
        CreateButton(ENABLE_TOGGLE, enabled ? Material.GREEN_STAINED_GLASS : Material.RED_STAINED_GLASS, t(player, "RANDOM_PRICE.ENABLE"), enableLore);

        String target = RandomPriceUtil.GetTarget(data);
        ArrayList<String> targetLore = new ArrayList<>(Arrays.asList(
                t(player, "RANDOM_PRICE.TARGET_LORE"),
                "§9" + t(player, "CUR_STATE") + ": §f" + t(player, "RANDOM_PRICE.TARGET_" + target),
                "§e" + t(player, "CLICK") + ": " + t(player, "RANDOM_PRICE.TARGET_" + NextTarget(target))));
        CreateButton(TARGET, Material.HOPPER, t(player, "RANDOM_PRICE.TARGET"), targetLore);

        int min = RandomPriceUtil.GetMinPercent(data);
        int max = RandomPriceUtil.GetMaxPercent(data);

        ArrayList<String> minLore = new ArrayList<>(Arrays.asList(
                t(player, "RANDOM_PRICE.MIN_LORE"),
                "§9" + t(player, "CUR_STATE") + ": §f" + min + "%",
                "§e" + t(player, "SHOP_SETTING.L_R_SHIFT")));
        CreateButton(MIN_PERCENT, Material.REDSTONE, t(player, "RANDOM_PRICE.MIN"), minLore, Clamp(Math.abs(min), 1, 64));

        ArrayList<String> maxLore = new ArrayList<>(Arrays.asList(
                t(player, "RANDOM_PRICE.MAX_LORE"),
                "§9" + t(player, "CUR_STATE") + ": §f" + max + "%",
                "§e" + t(player, "SHOP_SETTING.L_R_SHIFT")));
        CreateButton(MAX_PERCENT, Material.GLOWSTONE_DUST, t(player, "RANDOM_PRICE.MAX"), maxLore, Clamp(Math.abs(max), 1, 64));

        CreateButton(REROLL, Material.SUNFLOWER, t(player, "RANDOM_PRICE.REROLL"), t(player, "RANDOM_PRICE.REROLL_LORE"));

        boolean timerEnabled = RandomPriceUtil.IsTimerEnabled(data);
        ArrayList<String> timerLore = new ArrayList<>(Arrays.asList(
                t(player, "RANDOM_PRICE.TIMER_LORE"),
                "§9" + t(player, "CUR_STATE") + ": " + (timerEnabled ? t(player, "ON") : t(player, "OFF")),
                "§e" + t(player, "CLICK") + ": " + (timerEnabled ? t(player, "OFF") : t(player, "ON"))));
        CreateButton(TIMER_TOGGLE, timerEnabled ? Material.GREEN_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE, t(player, "RANDOM_PRICE.TIMER"), timerLore);

        String period = RandomPriceUtil.GetPeriod(data);
        ArrayList<String> periodLore = new ArrayList<>(Arrays.asList(
                t(player, "RANDOM_PRICE.PERIOD_LORE"),
                "§9" + t(player, "CUR_STATE") + ": §f" + t(player, "RANDOM_PRICE.PERIOD_" + period),
                "§e" + t(player, "CLICK") + ": " + t(player, "RANDOM_PRICE.PERIOD_" + NextPeriod(period))));
        CreateButton(PERIOD, Material.CLOCK, t(player, "RANDOM_PRICE.PERIOD"), periodLore);

        int hour = RandomPriceUtil.GetHour(data);
        int minute = RandomPriceUtil.GetMinute(data);

        ArrayList<String> hourLore = new ArrayList<>(Arrays.asList(
                t(player, "RANDOM_PRICE.HOUR_LORE"),
                "§9" + t(player, "CUR_STATE") + ": §f" + String.format("%02d:%02d", hour, minute),
                "§e" + t(player, "SHOP_SETTING.L_R_SHIFT")));
        CreateButton(HOUR, Material.CLOCK, t(player, "RANDOM_PRICE.HOUR"), hourLore, Clamp(hour + 1, 1, 64));

        ArrayList<String> minuteLore = new ArrayList<>(Arrays.asList(
                t(player, "RANDOM_PRICE.MINUTE_LORE"),
                "§9" + t(player, "CUR_STATE") + ": §f" + String.format("%02d:%02d", hour, minute),
                "§e" + t(player, "SHOP_SETTING.L_R_SHIFT")));
        CreateButton(MINUTE, Material.CLOCK, t(player, "RANDOM_PRICE.MINUTE"), minuteLore, Clamp(minute + 1, 1, 64));

        if (period.equals(RandomPriceUtil.PERIOD_WEEKLY))
        {
            int dayOfWeek = RandomPriceUtil.GetDayOfWeek(data);
            ArrayList<String> dowLore = new ArrayList<>(Arrays.asList(
                    t(player, "RANDOM_PRICE.DAY_OF_WEEK_LORE"),
                    "§9" + t(player, "CUR_STATE") + ": §f" + t(player, "RANDOM_PRICE.WEEKDAY_" + dayOfWeek),
                    "§e" + t(player, "SHOP_SETTING.L_R_SHIFT")));
            CreateButton(DAY_OF_WEEK, Material.PAPER, t(player, "RANDOM_PRICE.DAY_OF_WEEK"), dowLore, dayOfWeek);
        }
        else if (period.equals(RandomPriceUtil.PERIOD_MONTHLY))
        {
            int dayOfMonth = RandomPriceUtil.GetDayOfMonth(data);
            ArrayList<String> domLore = new ArrayList<>(Arrays.asList(
                    t(player, "RANDOM_PRICE.DAY_OF_MONTH_LORE"),
                    "§9" + t(player, "CUR_STATE") + ": §f" + dayOfMonth,
                    "§e" + t(player, "SHOP_SETTING.L_R_SHIFT")));
            CreateButton(DAY_OF_MONTH, Material.PAPER, t(player, "RANDOM_PRICE.DAY_OF_MONTH"), domLore, Clamp(dayOfMonth, 1, 64));
        }

        long next = RandomPriceUtil.GetNextResetTime(data);
        String nextStr = (timerEnabled && next != 0) ? RandomPriceUtil.FormatTime(next) : t(player, "OFF");
        CreateButton(NEXT_RESET, Material.BOOK, t(player, "RANDOM_PRICE.NEXT_RESET"),
                "§9" + t(player, "CUR_STATE") + ": §f" + nextStr);

        return inventory;
    }

    private String NextTarget(String target)
    {
        if (target.equals(RandomPriceUtil.TARGET_BOTH))
            return RandomPriceUtil.TARGET_BUY;
        if (target.equals(RandomPriceUtil.TARGET_BUY))
            return RandomPriceUtil.TARGET_SELL;

        return RandomPriceUtil.TARGET_BOTH;
    }

    private String NextPeriod(String period)
    {
        if (period.equals(RandomPriceUtil.PERIOD_DAILY))
            return RandomPriceUtil.PERIOD_WEEKLY;
        if (period.equals(RandomPriceUtil.PERIOD_WEEKLY))
            return RandomPriceUtil.PERIOD_MONTHLY;

        return RandomPriceUtil.PERIOD_DAILY;
    }

    @Override
    public void OnClickUpperInventory(InventoryClickEvent e)
    {
        Player player = (Player) e.getWhoClicked();

        CustomConfig config = ShopUtil.shopConfigFiles.get(shopName);
        FileConfiguration data = config.get();

        int slot = e.getSlot();

        if (slot == CLOSE)
        {
            DynaShopAPI.openShopSettingGui(player, shopName);
            return;
        }
        else if (slot == ENABLE_TOGGLE)
        {
            boolean enabled = RandomPriceUtil.IsEnabled(data);
            data.set(RandomPriceUtil.ROOT + ".enable", !enabled);
            config.save();

            if (!enabled)
            {
                RandomPriceUtil.SetDefaults(shopName);
                RandomPriceUtil.Reroll(shopName);
                if (RandomPriceUtil.IsTimerEnabled(data))
                    RandomPriceUtil.ScheduleNextReset(shopName);
            }
        }
        else if (slot == TARGET)
        {
            data.set(RandomPriceUtil.ROOT + ".target", NextTarget(RandomPriceUtil.GetTarget(data)));
            config.save();
        }
        else if (slot == MIN_PERCENT || slot == MAX_PERCENT)
        {
            String key = slot == MIN_PERCENT ? ".min" : ".max";
            int oldValue = slot == MIN_PERCENT ? RandomPriceUtil.GetMinPercent(data) : RandomPriceUtil.GetMaxPercent(data);

            int edit = e.isRightClick() ? 1 : -1;
            if (e.isShiftClick()) edit *= 5;

            int newValue = Clamp(oldValue + edit, RandomPriceUtil.MIN_PERCENT, RandomPriceUtil.MAX_PERCENT);
            data.set(RandomPriceUtil.ROOT + key, newValue);
            config.save();
        }
        else if (slot == REROLL)
        {
            RandomPriceUtil.Reroll(shopName);
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "RANDOM_PRICE.REROLLED").replace("{shop}", shopName));
        }
        else if (slot == TIMER_TOGGLE)
        {
            boolean timerEnabled = RandomPriceUtil.IsTimerEnabled(data);
            data.set(RandomPriceUtil.ROOT + ".timer.enable", !timerEnabled);
            config.save();

            if (!timerEnabled)
                RandomPriceUtil.ScheduleNextReset(shopName);
        }
        else if (slot == PERIOD)
        {
            data.set(RandomPriceUtil.ROOT + ".timer.period", NextPeriod(RandomPriceUtil.GetPeriod(data)));
            config.save();

            if (RandomPriceUtil.IsTimerEnabled(data))
                RandomPriceUtil.ScheduleNextReset(shopName);
        }
        else if (slot == HOUR || slot == MINUTE || slot == DAY_OF_WEEK || slot == DAY_OF_MONTH)
        {
            int edit = e.isRightClick() ? 1 : -1;
            if (e.isShiftClick()) edit *= 5;

            if (slot == HOUR)
            {
                data.set(RandomPriceUtil.ROOT + ".timer.hour", Clamp(RandomPriceUtil.GetHour(data) + edit, 0, 23));
            }
            else if (slot == MINUTE)
            {
                data.set(RandomPriceUtil.ROOT + ".timer.minute", Clamp(RandomPriceUtil.GetMinute(data) + edit, 0, 59));
            }
            else if (slot == DAY_OF_WEEK)
            {
                if (!RandomPriceUtil.GetPeriod(data).equals(RandomPriceUtil.PERIOD_WEEKLY))
                    return;

                data.set(RandomPriceUtil.ROOT + ".timer.dayOfWeek", Clamp(RandomPriceUtil.GetDayOfWeek(data) + edit, 1, 7));
            }
            else
            {
                if (!RandomPriceUtil.GetPeriod(data).equals(RandomPriceUtil.PERIOD_MONTHLY))
                    return;

                data.set(RandomPriceUtil.ROOT + ".timer.dayOfMonth", Clamp(RandomPriceUtil.GetDayOfMonth(data) + edit, 1, 31));
            }

            config.save();

            if (RandomPriceUtil.IsTimerEnabled(data))
                RandomPriceUtil.ScheduleNextReset(shopName);
        }
        else
        {
            return;
        }

        DynaShopAPI.openRandomPriceSettingGui(player, shopName);
    }
}
