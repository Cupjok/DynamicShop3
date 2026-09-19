package me.sat7.dynamicshop.commands.shop;

import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.commands.DSCMD;
import me.sat7.dynamicshop.commands.Shop;
import me.sat7.dynamicshop.files.CustomConfig;
import me.sat7.dynamicshop.utilities.RandomPriceUtil;
import me.sat7.dynamicshop.utilities.ShopUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static me.sat7.dynamicshop.constants.Constants.P_ADMIN_SHOP_EDIT;
import static me.sat7.dynamicshop.utilities.LangUtil.t;

public class ResetRandomPrice extends DSCMD
{
    public ResetRandomPrice()
    {
        inGameUseOnly = false;
        permission = P_ADMIN_SHOP_EDIT;
        validArgCount.add(3);
    }

    @Override
    public void SendHelpMessage(Player player)
    {
        player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "HELP.TITLE").replace("{command}", "resetRandomPrice"));
        player.sendMessage(" - " + t(player, "HELP.USAGE") + ": ... resetRandomPrice");

        player.sendMessage("");
    }

    @Override
    public void RunCMD(String[] args, CommandSender sender)
    {
        if(!CheckValid(args, sender))
            return;

        String shopName = Shop.GetShopName(args);
        CustomConfig shopData = ShopUtil.shopConfigFiles.get(shopName);
        if (shopData == null)
        {
            sender.sendMessage(DynamicShop.dsPrefix(sender) + t(sender, "ERR.SHOP_NOT_FOUND"));
            return;
        }

        if (!RandomPriceUtil.IsEnabled(shopData.get()))
        {
            sender.sendMessage(DynamicShop.dsPrefix(sender) + t(sender, "RANDOM_PRICE.NOT_ENABLED").replace("{shop}", shopName));
            return;
        }

        RandomPriceUtil.Reroll(shopName);

        if (RandomPriceUtil.IsTimerEnabled(shopData.get()))
            RandomPriceUtil.ScheduleNextReset(shopName);

        sender.sendMessage(DynamicShop.dsPrefix(sender) + t(sender, "RANDOM_PRICE.REROLLED").replace("{shop}", shopName));
    }
}
