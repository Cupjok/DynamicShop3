package me.sat7.dynamicshop.guis;

import me.sat7.dynamicshop.DynaShopAPI;
import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.constants.Constants;
import me.sat7.dynamicshop.economyhook.JobsHook;
import me.sat7.dynamicshop.economyhook.MultiCurrencyHook;
import me.sat7.dynamicshop.economyhook.PlayerpointHook;
import me.sat7.dynamicshop.files.CustomConfig;
import me.sat7.dynamicshop.utilities.ShopUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static me.sat7.dynamicshop.constants.Constants.P_ADMIN_SHOP_EDIT;
import static me.sat7.dynamicshop.utilities.LangUtil.t;

/**
 * Shop settings → "Choose currency": every currency this server can use for a shop. The built-in ones (Vault, Exp,
 * JobPoint, PlayerPoint) plus every currency MultiCurrency has configured. Writes the canonical Options.currency values that
 * ShopUtil.GetCurrency() understands (Vault, Exp, JobPoint, PlayerPoint, MultiCurrency:<id>).
 */
public final class CurrencySelector extends InGameUI
{
    public CurrencySelector()
    {
        uiType = UI_TYPE.CurrencySelector;
    }

    private final int CLOSE = 45;
    private final int FIRST_MULTICURRENCY_SLOT = 9;
    private final int LAST_MULTICURRENCY_SLOT = 44;

    private String shopName;
    /** slot -> value written to Options.currency */
    private final Map<Integer, String> slotValue = new HashMap<>();

    public Inventory getGui(Player player, String shopName)
    {
        this.shopName = shopName;
        slotValue.clear();

        inventory = Bukkit.createInventory(player, 54, t(player, "CURRENCY_SELECT_TITLE") + "§7 | §8" + shopName);
        CreateCloseButton(player, CLOSE);

        String current = ShopUtil.GetCurrency(ShopUtil.shopConfigFiles.get(shopName));

        AddCurrency(player, 0, "Vault", t(player, "SHOP_SETTING.VAULT_LORE"), Constants.S_VAULT, current.equalsIgnoreCase(Constants.S_VAULT), true);
        AddCurrency(player, 1, "Exp", t(player, "SHOP_SETTING.EXP_LORE"), Constants.S_EXP, current.equalsIgnoreCase(Constants.S_EXP), true);
        AddCurrency(player, 2, "JobPoint", t(player, "SHOP_SETTING.JOB_POINT_LORE"), Constants.S_JOBPOINT, current.equalsIgnoreCase(Constants.S_JOBPOINT), JobsHook.jobsRebornActive);
        AddCurrency(player, 3, "PlayerPoint", t(player, "SHOP_SETTING.PLAYER_POINT_LORE"), Constants.S_PLAYERPOINT, current.equalsIgnoreCase(Constants.S_PLAYERPOINT), PlayerpointHook.isPPActive);

        if (!MultiCurrencyHook.IsAvailable())
        {
            CreateButton(FIRST_MULTICURRENCY_SLOT, Material.GRAY_STAINED_GLASS_PANE, "§7MultiCurrency", t(player, "ERR.MULTICURRENCY_NOT_FOUND"));
        }
        else
        {
            int slot = FIRST_MULTICURRENCY_SLOT;
            for (String id : MultiCurrencyHook.GetCurrencyIds())
            {
                if (slot > LAST_MULTICURRENCY_SLOT)
                    break;

                MultiCurrencyHook.CurrencyInfo info = MultiCurrencyHook.GetCurrency(id);
                if (info == null)
                    continue;

                String value = Constants.S_MULTICURRENCY_PREFIX + info.id();
                String lore = t(player, "SHOP_SETTING.MULTICURRENCY_LORE")
                        .replace("{id}", info.id())
                        .replace("{symbol}", info.symbol())
                        .replace("{decimals}", String.valueOf(info.scale()));
                AddCurrency(player, slot++, "MultiCurrency: " + info.displayName(), lore, value, current.equalsIgnoreCase(value), info.enabled());
            }
        }

        return inventory;
    }

    private void AddCurrency(Player player, int slot, String name, String lore, String value, boolean selected, boolean available)
    {
        ArrayList<String> loreLines = new ArrayList<>();
        Collections.addAll(loreLines, lore.split("\n"));

        Material icon;
        if (selected)
        {
            icon = Material.YELLOW_STAINED_GLASS_PANE;
            loreLines.add(t(player, "SHOP_SETTING.SELECTED"));
        }
        else if (!available)
        {
            icon = Material.GRAY_STAINED_GLASS_PANE;
        }
        else
        {
            icon = Material.WHITE_STAINED_GLASS_PANE;
        }

        if (!available)
            loreLines.add(t(player, "SHOP_SETTING.CURRENCY_UNAVAILABLE"));
        else if (!selected)
            loreLines.add("§e" + t(player, "CLICK") + ": " + t(player, "SET"));

        CreateButton(slot, icon, t(player, "SHOP_SETTING.CURRENCY") + name, loreLines);
        slotValue.put(slot, value);
    }

    @Override
    public void OnClickUpperInventory(InventoryClickEvent e)
    {
        Player player = (Player) e.getWhoClicked();

        if (e.getSlot() == CLOSE)
        {
            DynaShopAPI.openShopSettingGui(player, shopName);
            return;
        }

        String value = slotValue.get(e.getSlot());
        if (value == null || !player.hasPermission(P_ADMIN_SHOP_EDIT))
            return;

        CustomConfig data = ShopUtil.shopConfigFiles.get(shopName);
        if (data == null)
        {
            player.closeInventory();
            return;
        }

        // same guards as the currency buttons in ShopSettings / the currency command
        if (value.equals(Constants.S_JOBPOINT) && !JobsHook.jobsRebornActive)
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "ERR.JOBS_REBORN_NOT_FOUND"));
            return;
        }
        if (value.equals(Constants.S_PLAYERPOINT) && !PlayerpointHook.isPPActive)
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "ERR.PLAYER_POINTS_NOT_FOUND"));
            return;
        }
        if (ShopUtil.IsMultiCurrency(value))
        {
            String id = ShopUtil.GetMultiCurrencyId(value);
            MultiCurrencyHook.CurrencyInfo info = MultiCurrencyHook.GetCurrency(id);
            if (info == null || !info.enabled())
            {
                player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "ERR.MULTICURRENCY_UNKNOWN_CURRENCY").replace("{currency}", id));
                return;
            }
        }

        if (!ShopUtil.GetCurrency(data).equalsIgnoreCase(ShopUtil.GetCurrency(CurrencyYaml(value))))
        {
            data.get().set("Options.currency", value);
            data.save();
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.CHANGES_APPLIED") + "currency " + value);
        }

        DynaShopAPI.openShopSettingGui(player, shopName);
    }

    private static org.bukkit.configuration.file.YamlConfiguration CurrencyYaml(String value)
    {
        org.bukkit.configuration.file.YamlConfiguration yml = new org.bukkit.configuration.file.YamlConfiguration();
        yml.set("Options.currency", value);
        return yml;
    }

}
