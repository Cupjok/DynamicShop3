package me.sat7.dynamicshop.utilities;

import me.sat7.dynamicshop.DynamicShop;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Command items: shop entries that run console commands when bought instead of giving the shown item.
 *
 * Per-item keys in the shop file (next to value/stock/...):
 * - itemType: COMMAND   (absent = normal item)
 * - commands: [list]    run from the console once per unit bought; {player}, {uuid}, {shop} are replaced
 * - cmdName:  string    optional display name override (same colour syntax as shop names, see ShopNameFormatter)
 * - cmdLore:  [list]    optional lore lines shown above the price info
 *
 * The shown item stays the lookup key (mat + itemStack meta) so hashes, trade limits and findItemFromShop keep working;
 * the name/lore override is applied to display copies only.
 */
public final class CommandItemUtil
{
    private CommandItemUtil()
    {

    }

    public static final String TYPE_COMMAND = "COMMAND";
    public static final int MAX_COMMANDS = 45;
    public static final int MAX_LORE_LINES = 20;

    private static final Pattern LORE_LINE_BREAK = Pattern.compile(Pattern.quote("\\n"));

    public static boolean IsCommandItem(FileConfiguration data, String idx)
    {
        return data != null && TYPE_COMMAND.equalsIgnoreCase(data.getString(idx + ".itemType", ""));
    }

    public static void SetCommandItem(FileConfiguration data, String idx, boolean commandItem)
    {
        // commands/name/lore are kept when switching back to a normal item, so switching again restores them
        data.set(idx + ".itemType", commandItem ? TYPE_COMMAND : null);
    }

    public static List<String> GetCommands(FileConfiguration data, String idx)
    {
        return new ArrayList<>(data.getStringList(idx + ".commands"));
    }

    public static void SetCommands(FileConfiguration data, String idx, List<String> commands)
    {
        data.set(idx + ".commands", commands.isEmpty() ? null : new ArrayList<>(commands));
    }

    public static String GetName(FileConfiguration data, String idx)
    {
        return data.getString(idx + ".cmdName", "");
    }

    public static void SetName(FileConfiguration data, String idx, String name)
    {
        data.set(idx + ".cmdName", name == null || name.isEmpty() ? null : name);
    }

    public static List<String> GetLore(FileConfiguration data, String idx)
    {
        return new ArrayList<>(data.getStringList(idx + ".cmdLore"));
    }

    public static void SetLore(FileConfiguration data, String idx, List<String> lore)
    {
        data.set(idx + ".cmdLore", lore == null || lore.isEmpty() ? null : new ArrayList<>(lore));
    }

    /** Chat input for a command: surrounding spaces and one leading '/' removed. Empty = invalid. */
    public static String NormalizeCommandInput(String input)
    {
        if (input == null)
            return "";
        String s = input.trim();
        if (s.startsWith("/"))
            s = s.substring(1).trim();
        return s;
    }

    /** Chat input for lore: a literal \n starts a new line. */
    public static List<String> SplitLoreInput(String input)
    {
        List<String> out = new ArrayList<>();
        if (input == null || input.isEmpty())
            return out;
        for (String line : LORE_LINE_BREAK.split(input, -1))
        {
            if (out.size() >= MAX_LORE_LINES)
                break;
            out.add(line);
        }
        return out;
    }

    public static String BuildCommand(String template, String playerName, UUID playerUuid, String shopName)
    {
        return NormalizeCommandInput(template)
                .replace("{player}", playerName)
                .replace("{uuid}", String.valueOf(playerUuid))
                .replace("{shop}", shopName);
    }

    /**
     * Runs the item's commands from the console, once per unit bought.
     * Commands are dispatched on the global thread (required on Folia); on Paper/Purpur that is the main thread,
     * so when called from it they run immediately.
     */
    public static void RunCommands(FileConfiguration data, String shopName, String idx, String playerName, UUID playerUuid, int times)
    {
        List<String> built = new ArrayList<>();
        for (String template : GetCommands(data, idx))
        {
            String cmd = BuildCommand(template, playerName, playerUuid, shopName);
            if (!cmd.isEmpty())
                built.add(cmd);
        }
        if (built.isEmpty() || times <= 0)
            return;

        Runnable run = () ->
        {
            for (int i = 0; i < times; i++)
            {
                for (String cmd : built)
                {
                    try
                    {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                    }
                    catch (RuntimeException e)
                    {
                        DynamicShop.plugin.getLogger().log(Level.WARNING, "Command item " + shopName + "/" + idx + " failed to run: " + cmd, e);
                    }
                }
            }
        };

        if (Bukkit.isGlobalTickThread())
            run.run();
        else
            SchedulerUtil.runGlobal(run);
    }

    /** Applies the name/lore override of a command item to a display meta. Custom lore goes above the existing lore. */
    public static void ApplyDisplay(ItemMeta meta, FileConfiguration data, String idx)
    {
        if (meta == null || !IsCommandItem(data, idx))
            return;

        boolean bareHex = ConfigUtil.GetUseHexColorCode();

        String name = GetName(data, idx);
        if (!name.isEmpty())
            meta.displayName(ShopNameFormatter.formatItemName(name, bareHex));

        List<String> lore = GetLore(data, idx);
        if (!lore.isEmpty())
        {
            List<Component> combined = new ArrayList<>(ShopNameFormatter.formatLore(lore, bareHex));
            List<Component> existing = meta.lore();
            if (existing != null)
                combined.addAll(existing);
            meta.lore(combined);
        }
    }

    /** Copy of the item carrying the command item's display name, for purchase messages. */
    public static ItemStack WithDisplayName(ItemStack itemStack, FileConfiguration data, String idx)
    {
        ItemStack copy = itemStack.clone();
        String name = GetName(data, idx);
        if (name.isEmpty())
            return copy;

        ItemMeta meta = copy.getItemMeta();
        if (meta == null)
            return copy;
        meta.displayName(ShopNameFormatter.formatItemName(name, ConfigUtil.GetUseHexColorCode()));
        copy.setItemMeta(meta);
        return copy;
    }
}
