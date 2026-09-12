package me.sat7.dynamicshop.guis;

import me.sat7.dynamicshop.DynaShopAPI;
import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.events.OnChat;
import me.sat7.dynamicshop.files.CustomConfig;
import me.sat7.dynamicshop.models.DSItem;
import me.sat7.dynamicshop.utilities.CommandItemUtil;
import me.sat7.dynamicshop.utilities.RotationUtil;
import me.sat7.dynamicshop.utilities.ShopUtil;
import me.sat7.dynamicshop.utilities.SoundUtil;
import me.sat7.dynamicshop.utilities.UserUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static me.sat7.dynamicshop.constants.Constants.P_ADMIN_SHOP_EDIT;
import static me.sat7.dynamicshop.utilities.LangUtil.t;

// 명령어 상품 편집기: 구매 시 실행할 명령어 목록, 표시 이름, 설명
public final class CommandItemEditor extends InGameUI
{
    public CommandItemEditor()
    {
        uiType = UI_TYPE.CommandItemEditor;
    }

    private final int CLOSE = 0;
    private final int PREVIEW = 2;
    private final int RENAME = 3;
    private final int LORE = 4;
    private final int ADD_COMMAND = 5;
    private final int HELP = 8;
    private final int COMMAND_START = 9;

    private enum InputMode { NAME, LORE, ADD, EDIT }

    private record PendingInput(String shopName, int idx, InputMode mode, int commandIndex) {}

    /** Where the back button returns to: the item settings screen with its unsaved values. */
    private record ReturnTarget(String shopName, int idx, DSItem dsItem, int tab, int timerOffset) {}

    private static final Map<UUID, PendingInput> pendingInputs = new ConcurrentHashMap<>();
    private static final Map<UUID, ReturnTarget> returnTargets = new ConcurrentHashMap<>();

    private String shopName;
    private int idx;

    public static void SetReturnTarget(Player player, String shopName, int idx, DSItem dsItem, int tab, int timerOffset)
    {
        returnTargets.put(player.getUniqueId(), new ReturnTarget(shopName, idx, dsItem, tab, timerOffset));
    }

    public static void OnPlayerQuit(UUID uuid)
    {
        pendingInputs.remove(uuid);
        returnTargets.remove(uuid);
    }

    public Inventory getGui(Player player, String shopName, int idx)
    {
        this.shopName = shopName;
        this.idx = idx;

        FileConfiguration data = ShopUtil.shopConfigFiles.get(shopName).get();
        String key = String.valueOf(idx);

        inventory = Bukkit.createInventory(player, 54, t(player, "CMD_ITEM.TITLE") + "§7 | §8" + shopName);

        CreateCloseButton(player, CLOSE);

        // 미리보기
        Material mat = Material.getMaterial(data.getString(key + ".mat", "STONE"));
        if (mat == null || mat.isAir())
            mat = Material.PAPER;
        ItemStack preview = new ItemStack(mat);
        if (data.get(key + ".itemStack") instanceof ItemMeta savedMeta)
            preview.setItemMeta(savedMeta);
        ItemMeta previewMeta = preview.getItemMeta();
        if (previewMeta != null)
        {
            previewMeta.lore(null);
            CommandItemUtil.ApplyDisplay(previewMeta, data, key);
            previewMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            preview.setItemMeta(previewMeta);
        }
        inventory.setItem(PREVIEW, preview);

        String name = CommandItemUtil.GetName(data, key);
        CreateButton(RENAME, Material.NAME_TAG, t(player, "CMD_ITEM.RENAME"),
                t(player, "CMD_ITEM.RENAME_LORE").replace("{name}", name.isEmpty() ? "-" : name.replace('§', '&')));

        List<String> loreLines = CommandItemUtil.GetLore(data, key);
        ArrayList<String> loreBtn = new ArrayList<>();
        for (String line : loreLines)
            loreBtn.add("§7" + line.replace('§', '&'));
        loreBtn.add(t(player, "CMD_ITEM.LORE_LORE"));
        CreateButton(LORE, Material.BOOK, t(player, "CMD_ITEM.LORE"), loreBtn);

        CreateButton(ADD_COMMAND, Material.COMMAND_BLOCK, t(player, "CMD_ITEM.ADD_COMMAND"), t(player, "CMD_ITEM.ADD_COMMAND_LORE"));
        CreateButton(HELP, Material.OAK_SIGN, t(player, "CMD_ITEM.HELP"), t(player, "CMD_ITEM.HELP_LORE"));

        List<String> commands = CommandItemUtil.GetCommands(data, key);
        for (int i = 0; i < commands.size() && i < CommandItemUtil.MAX_COMMANDS; i++)
        {
            ArrayList<String> lore = new ArrayList<>();
            lore.add("§7/" + commands.get(i).replace('§', '&'));
            lore.add(t(player, "CMD_ITEM.COMMAND_LORE"));
            CreateButton(COMMAND_START + i, Material.PAPER, t(player, "CMD_ITEM.COMMAND").replace("{num}", String.valueOf(i + 1)), lore);
        }

        return inventory;
    }

    @Override
    public void OnClickUpperInventory(InventoryClickEvent e)
    {
        Player player = (Player) e.getWhoClicked();
        if (!player.hasPermission(P_ADMIN_SHOP_EDIT))
            return;

        CustomConfig data = ShopUtil.shopConfigFiles.get(shopName);
        String key = String.valueOf(idx);
        if (data == null || !data.get().contains(key + ".value"))
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "ERR.INVALID_TRANSACTION"));
            player.closeInventory();
            return;
        }

        int slot = e.getSlot();
        if (slot == CLOSE)
        {
            GoBack(player);
        }
        else if (slot == RENAME)
        {
            if (e.isRightClick())
            {
                CommandItemUtil.SetName(data.get(), key, null);
                SaveAndRefresh(player, data);
            }
            else
            {
                RequestInput(player, InputMode.NAME, -1, "CMD_ITEM.ENTER_NAME");
            }
        }
        else if (slot == LORE)
        {
            if (e.isRightClick())
            {
                CommandItemUtil.SetLore(data.get(), key, null);
                SaveAndRefresh(player, data);
            }
            else
            {
                RequestInput(player, InputMode.LORE, -1, "CMD_ITEM.ENTER_LORE");
            }
        }
        else if (slot == ADD_COMMAND)
        {
            if (CommandItemUtil.GetCommands(data.get(), key).size() >= CommandItemUtil.MAX_COMMANDS)
            {
                player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "CMD_ITEM.LIST_FULL"));
                return;
            }
            RequestInput(player, InputMode.ADD, -1, "CMD_ITEM.ENTER_COMMAND");
        }
        else if (slot >= COMMAND_START && slot < COMMAND_START + CommandItemUtil.MAX_COMMANDS)
        {
            int commandIndex = slot - COMMAND_START;
            List<String> commands = CommandItemUtil.GetCommands(data.get(), key);
            if (commandIndex >= commands.size())
                return;

            if (e.isRightClick())
            {
                commands.remove(commandIndex);
                CommandItemUtil.SetCommands(data.get(), key, commands);
                SaveAndRefresh(player, data);
            }
            else
            {
                RequestInput(player, InputMode.EDIT, commandIndex, "CMD_ITEM.ENTER_COMMAND");
            }
        }
    }

    private void GoBack(Player player)
    {
        ReturnTarget target = returnTargets.remove(player.getUniqueId());
        if (target != null && target.shopName().equals(shopName) && target.idx() == idx)
            DynaShopAPI.openItemSettingGui(player, shopName, idx, target.tab(), target.dsItem(), target.timerOffset());
        else
            DynaShopAPI.openShopGui(player, shopName, idx / 45 + 1);
    }

    private void RequestInput(Player player, InputMode mode, int commandIndex, String messageKey)
    {
        pendingInputs.put(player.getUniqueId(), new PendingInput(shopName, idx, mode, commandIndex));
        player.sendMessage(DynamicShop.dsPrefix(player) + t(player, messageKey));
        ShopUtil.closeInventoryWithDelay(player);
        UserUtil.userTempData.put(player.getUniqueId(), "waitforCmdItem");
        OnChat.WaitForInput(player);
    }

    private void SaveAndRefresh(Player player, CustomConfig data)
    {
        data.save();
        RotationUtil.UpdateCurrentRotationData(shopName, idx);
        SoundUtil.playerSoundEffect(player, "editItem");
        DynaShopAPI.openCommandItemEditor(player, shopName, idx);
    }

    /** True while the player is asked to type a command (add/edit), so a typed "/cmd" is input, not executed. */
    public static boolean IsWaitingForCommand(UUID uuid)
    {
        PendingInput input = pendingInputs.get(uuid);
        return input != null && (input.mode() == InputMode.ADD || input.mode() == InputMode.EDIT);
    }

    /** Chat input from OnChat. Must run on the player's own thread. */
    public static void OnChatInput(Player player, String message)
    {
        PendingInput input = pendingInputs.remove(player.getUniqueId());
        if (input == null || !player.hasPermission(P_ADMIN_SHOP_EDIT))
            return;

        CustomConfig data = ShopUtil.shopConfigFiles.get(input.shopName());
        String key = String.valueOf(input.idx());
        if (data == null || !data.get().contains(key + ".value"))
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "ERR.INVALID_TRANSACTION"));
            return;
        }

        if (message.trim().equalsIgnoreCase("cancel"))
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.INPUT_CANCELED"));
            DynaShopAPI.openCommandItemEditor(player, input.shopName(), input.idx());
            return;
        }

        FileConfiguration config = data.get();
        switch (input.mode())
        {
            case NAME -> CommandItemUtil.SetName(config, key, message);
            case LORE -> CommandItemUtil.SetLore(config, key, CommandItemUtil.SplitLoreInput(message));
            case ADD, EDIT ->
            {
                String cmd = CommandItemUtil.NormalizeCommandInput(message);
                if (cmd.isEmpty())
                {
                    player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.INPUT_CANCELED"));
                    break;
                }

                List<String> commands = CommandItemUtil.GetCommands(config, key);
                if (input.mode() == InputMode.EDIT && input.commandIndex() < commands.size())
                    commands.set(input.commandIndex(), cmd);
                else if (commands.size() < CommandItemUtil.MAX_COMMANDS)
                    commands.add(cmd);
                CommandItemUtil.SetCommands(config, key, commands);
            }
        }

        data.save();
        RotationUtil.UpdateCurrentRotationData(input.shopName(), input.idx());
        DynaShopAPI.openCommandItemEditor(player, input.shopName(), input.idx());
    }
}
