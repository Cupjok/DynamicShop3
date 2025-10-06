package me.sat7.dynamicshop;

import me.clip.placeholderapi.PlaceholderAPI;
import me.pikamug.localelib.LocaleManager;
import me.sat7.dynamicshop.commands.CMDManager;
import me.sat7.dynamicshop.commands.Optional;
import me.sat7.dynamicshop.commands.Root;
import me.sat7.dynamicshop.commands.Sell;
import me.sat7.dynamicshop.constants.Constants;
import me.sat7.dynamicshop.economyhook.JobsHook;
import me.sat7.dynamicshop.economyhook.PlayerpointHook;
import me.sat7.dynamicshop.events.JoinQuit;
import me.sat7.dynamicshop.events.OnChat;
import me.sat7.dynamicshop.events.OnClick;
import me.sat7.dynamicshop.events.OnSignClick;
import me.sat7.dynamicshop.files.CustomConfig;
import me.sat7.dynamicshop.guis.QuickSell;
import me.sat7.dynamicshop.guis.StartPage;
import me.sat7.dynamicshop.guis.UIManager;
import me.sat7.dynamicshop.utilities.*;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;

public final class DynamicShop extends JavaPlugin implements Listener
{
    private static Economy econ = null; // 볼트에 물려있는 이코노미
    public static Economy getEconomy()
    {
        return econ;
    }

    public static PlayerPointsAPI ppAPI;

    public static DynamicShop plugin;
    public static ConsoleCommandSender console;

    public static String dsPrefix(CommandSender commandSender)
    {
        Player player = null;
        if(commandSender instanceof Player)
            player = (Player) commandSender;

        return dsPrefix(player);
    }

    public static String dsPrefix(Player player)
    {
        String temp = dsPrefix_;

        if(ConfigUtil.GetUseHexColorCode())
            temp = LangUtil.TranslateHexColor(temp);

        if(isPapiExist && player != null && ConfigUtil.GetUsePlaceholderAPI())
            return PlaceholderAPI.setPlaceholders(player, temp);

        return temp;
    }

    public static String dsPrefix_ = "§3DShop3 §7| §f";

    public static CustomConfig ccSign = new CustomConfig();

    private BukkitTask periodicRepetitiveTask;
    private BukkitTask saveLogsTask;
    private BukkitTask cullLogsTask;
    private BukkitTask backupTask;
    private BukkitTask shopSaveTask;
    private BukkitTask userDataRepetitiveTask;

    public static boolean updateAvailable = false;
    public static String lastVersion = "";
    public static String yourVersion = "";

    public static UIManager uiManager;

    public static final LocaleManager localeManager = new LocaleManager();
    public static boolean isPapiExist;

    public static boolean DEBUG_LOG_ENABLED = false;
    public static void PrintConsoleDbgLog(String msg)
    {
        if (DEBUG_LOG_ENABLED)
            console.sendMessage(DynamicShop.dsPrefix_ + msg);
    }

    public static boolean DEBUG_MODE = false;
    public static void DebugLog()
    {
        if(!DEBUG_MODE)
            return;

        console.sendMessage("========== DEBUG LOG ==========");

        console.sendMessage("userTempData: size: " + UserUtil.userTempData.size());
        int idx = 0;
        for(Map.Entry<UUID, String> entry : UserUtil.userTempData.entrySet())
        {
            console.sendMessage(entry.getKey() + ": " + entry.getValue());
            idx++;
            if (idx > 9)
                break;
        }

        console.sendMessage("---------------------");

        console.sendMessage("userInteractItem: size: " + UserUtil.userInteractItem.size());
        idx = 0;
        for(Map.Entry<UUID, String> entry : UserUtil.userInteractItem.entrySet())
        {
            console.sendMessage(entry.getKey() + ": " + entry.getValue());
            idx++;
            if (idx > 9)
                break;
        }

        console.sendMessage("---------------------");

        console.sendMessage("ShopUtil.shopConfigFiles: size: " + ShopUtil.shopConfigFiles.size());
        for(Map.Entry<String, CustomConfig> entry : ShopUtil.shopConfigFiles.entrySet())
            console.sendMessage(entry.getKey() + ": " + entry.getValue());

        console.sendMessage("---------------------");

        console.sendMessage("ShopUtil.ShopUtil.shopDirty: size: " + ShopUtil.shopDirty.size());
        for(Map.Entry<String, Boolean> entry : ShopUtil.shopDirty.entrySet())
            console.sendMessage(entry.getKey() + ": " + entry.getValue());

        console.sendMessage("---------------------");

        UIManager.DebugLog();

        console.sendMessage("---------------------");

        console.sendMessage("RotationTaskMap: size: " + RotationUtil.RotationTaskMap.size());
        for(Map.Entry<String, Integer> entry : RotationUtil.RotationTaskMap.entrySet())
            console.sendMessage(entry.getKey() + ": " + entry.getValue());

        console.sendMessage("---------------------");

        for (Map.Entry<String, HashMap<String, HashMap<UUID, Integer>>> entry : UserUtil.tradingVolume.entrySet())
        {
            console.sendMessage(entry.getKey());
            for (Map.Entry<String, HashMap<UUID, Integer>> entry1 : UserUtil.tradingVolume.get(entry.getKey()).entrySet())
            {
                console.sendMessage(" - " + entry1.getKey());
                for (Map.Entry<UUID, Integer> entry2 : UserUtil.tradingVolume.get(entry.getKey()).get(entry1.getKey()).entrySet())
                {
                    console.sendMessage(" --- " + entry2.getKey() + " : " + entry2.getValue());
                }
            }
        }

        console.sendMessage("========== DEBUG LOG END ==========");
    }

    @Override
    public void onEnable()
    {
        plugin = this;
        console = plugin.getServer().getConsoleSender();

        SetupVault();
    }

    private void Init()
    {
        CMDManager.Init();

        registerEvents();
        initCommands();

        makeFolders();
        InitConfig();

        PeriodicRepetitiveTask();
        startSaveLogsTask();
        startCullLogsTask();
        StartBackupTask();
        StartShopSaveTask();
        StartUserDataTask();
        hookIntoJobs();
        hookIntoPlayerPoints();
        InitPapi();

        RotationUtil.RestartAllRotationTask();

        // Warning for QuickSell logic change (Left-Click/Shift-Click swap)
        // Server admin needs to update the language file (QUICK_SELL.GUIDE_LORE) manually.
        console.sendMessage("=======================================================");
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " QUICKSELL UI LOGIC HAS BEEN MODIFIED!");
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Please update your language file (e.g., Lang_V3_en-US.yml):");
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " -> QUICK_SELL.GUIDE_LORE");
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + "    - The logic for Left-Click and Shift+Left-Click has been swapped.");
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + "    - Please set the LORE to reflect this: ");
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + "    - Left-click: Sell ALL items of that type.");
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + "    - Shift+Left-click: Sell only the selected STACK.");
        console.sendMessage("=======================================================");

        // 완료
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Enabled! :)");

        CheckUpdate();
        InitBstats();
    }

    // 볼트 이코노미 초기화
    private void SetupVault()
    {
        if (getServer().getPluginManager().getPlugin("Vault") == null)
        {
            console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Disabled due to no Vault dependency found!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        else
        {
            console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'Vault' Found");
        }

        SetupRSP();
    }

    private int setupRspRetryCount = 0;
    private void SetupRSP()
    {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null)
        {
            econ = rsp.getProvider();

            Init();
        }
        else
        {
            if(setupRspRetryCount >= 3)
            {
                console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Disabled due to no Vault dependency found!");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            setupRspRetryCount++;
            //console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Economy provider not found. Retry... " + setupRspRetryCount + "/3");

            Bukkit.getScheduler().runTaskLater(this, this::SetupRSP, 40L);
        }
    }

    // This method is removed as comparison is now handled by the UpdateChecker utility class.

    private void CheckUpdate()
    {
        // Use the UpdateChecker to get the latest version from the configured GitHub repository.
        new UpdateChecker(this, UpdateChecker.PROJECT_ID).getVersion(version ->
        {
            try
            {
                lastVersion = version;
                yourVersion = getDescription().getVersion();

                // Instantiate UpdateChecker to use its comparison logic.
                UpdateChecker checker = new UpdateChecker(this, UpdateChecker.PROJECT_ID);

                // IMPORTANT: For this to work, compareVersions in UpdateChecker.java must be public static!
                // We use the compareVersions method to accurately check if your version is older.
                int comparisonResult = UpdateChecker.compareVersions(yourVersion, lastVersion);

                if (comparisonResult < 0) // If your version is OLDER than the GitHub release
                {
                    DynamicShop.updateAvailable = true;
                    DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Plugin outdated! Latest: " + lastVersion);
                    DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Get the latest version here: " + UpdateChecker.getResourceUrl());
                } else if (comparisonResult > 0) // If your version is NEWER than the GitHub release
                {
                    DynamicShop.updateAvailable = false;
                    DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Running a potentially newer version (" + yourVersion + ") than the latest release (" + lastVersion + ").");
                } else // Versions are the same
                {
                    DynamicShop.updateAvailable = false;
                    DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Plugin is up to date! (Version: " + yourVersion + ")");
                }
            } catch (Exception e)
            {
                DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Failed to check update. Try again later.");
                DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Error: " + e.getMessage());
            }
        });
    }

    public static TextComponent CreateLink(final String text, boolean bold, ChatColor color, final String link) {
        final TextComponent component = new TextComponent(text);
        component.setBold(bold);
        component.setUnderlined(true);
        component.setColor(color);
        component.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link));
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder(link).create()));
        return component;
    }

    private void InitBstats()
    {
        try
        {
            int pluginId = 4258;
            Metrics metrics = new Metrics(this, pluginId);
        } catch (Exception e)
        {
            DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + "Failed to Init bstats : " + e);
        }
    }

    private void InitPapi()
    {
        isPapiExist = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        if(isPapiExist)
        {
            console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'PlaceholderAPI' Found");
        }
        else
        {
            console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'PlaceholderAPI' Not Found");
        }
    }

    public void startSaveLogsTask()
    {
        if (ConfigUtil.GetSaveLogs())
        {
            if (saveLogsTask != null)
            {
                saveLogsTask.cancel();
            }
            saveLogsTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, LogUtil::SaveLogToCSV, 0L, (20L * 10L));
        }
    }

    public void startCullLogsTask()
    {
        if (ConfigUtil.GetCullLogs())
        {
            if (cullLogsTask != null)
            {
                cullLogsTask.cancel();
            }
            cullLogsTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    this, LogUtil::cullLogs, 0L, (20L * 60L * (long) ConfigUtil.GetLogCullTimeMinutes())
            );
        }
    }

    public void StartUserDataTask()
    {
        if (userDataRepetitiveTask != null)
            userDataRepetitiveTask.cancel();

        userDataRepetitiveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                this, UserUtil::RepetitiveTask, 0L, 20L * 60L * 60L
        );
    }

    public void PeriodicRepetitiveTask()
    {
        if (periodicRepetitiveTask != null)
        {
            periodicRepetitiveTask.cancel();
        }

        // 1000틱 = 50초 = 마인크래프트 1시간
        // 20틱 = 현실시간 1초
        periodicRepetitiveTask = Bukkit.getScheduler().runTaskTimer(DynamicShop.plugin, this::RepeatAction, 20, 20);
    }

    private int repeatTaskCount = 0;
    private void RepeatAction()
    {
        repeatTaskCount++;

        //SimpleDateFormat sdf = new SimpleDateFormat("MM.dd.yyyy,HH.mm.ss");
        //String time = sdf.format(System.currentTimeMillis());
        //console.sendMessage(time + " / " + repeatTaskCount);

        if (repeatTaskCount == 25) // 25초 = 500틱 = 마인ครฟ트 30นาที
        {
            ShopUtil.randomChange(new Random());
            repeatTaskCount = 0;
        }
        UIManager.RefreshUI();
    }

    public void StartBackupTask()
    {
        if (!ConfigUtil.GetShopYmlBackup_Enable())
            return;

        if (backupTask != null)
        {
            backupTask.cancel();
        }
        long interval = (20L * 60L * (long) ConfigUtil.GetShopYmlBackup_IntervalMinutes());
        backupTask = Bukkit.getScheduler().runTaskTimer(DynamicShop.plugin, ShopUtil::ShopYMLBackup, interval, interval);
    }

    public void StartShopSaveTask()
    {
        if (shopSaveTask != null)
        {
            shopSaveTask.cancel();
        }

        long interval = (20L * 10L);
        shopSaveTask = Bukkit.getScheduler().runTaskTimer(DynamicShop.plugin, ShopUtil::SaveDirtyShop, interval, interval);
    }

    private void hookIntoJobs()
    {
        // Jobs
        if (getServer().getPluginManager().getPlugin("Jobs") == null)
        {
            console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'Jobs Reborn' Not Found");
            JobsHook.jobsRebornActive = false;
        } else
        {
            console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'Jobs Reborn' Found");
            JobsHook.jobsRebornActive = true;
        }
    }

    private void hookIntoPlayerPoints()
    {
        if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints"))
        {
            console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'PlayerPoints' Found");
            ppAPI = PlayerPoints.getInstance().getAPI();
            PlayerpointHook.isPPActive = true;

        }
        else
        {
            console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " 'PlayerPoints' Not Found");
            PlayerpointHook.isPPActive = false;
        }
    }

    private void initCommands()
    {
        // 명령어 등록 (개별 클เลสที่กำหนดไว้)
        getCommand("DynamicShop").setExecutor(new Root());
        getCommand("shop").setExecutor(new Optional());
        getCommand("sell").setExecutor(new Sell());

        // auto-completion
        getCommand("DynamicShop").setTabCompleter(this);
        getCommand("shop").setTabCompleter(this);
        getCommand("sell").setTabCompleter(this);
    }

    private void registerEvents()
    {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new JoinQuit(), this);
        getServer().getPluginManager().registerEvents(new OnClick(), this);
        getServer().getPluginManager().registerEvents(new OnSignClick(), this);
        getServer().getPluginManager().registerEvents(new OnChat(), this);

        uiManager = new UIManager();
        getServer().getPluginManager().registerEvents(uiManager, this);
    }

    private void makeFolders()
    {
        File shopFolder = new File(getDataFolder(), "Shop");
        shopFolder.mkdir(); // creates folder

        File rotationFolder = new File(getDataFolder(), "Rotation");
        rotationFolder.mkdir();

        File LogFolder = new File(getDataFolder(), "Log");
        LogFolder.mkdir();
    }

    private void InitConfig()
    {
        UserUtil.Init();
        ShopUtil.Reload();
        ConfigUtil.Load();

        LangUtil.setupLangFile(ConfigUtil.GetLanguage());  // Must be under ConfigUtil.Load()
        LayoutUtil.Setup();

        StartPage.setupStartPageFile();
        setupSignFile();
        WorthUtil.setupWorthFile();
        SoundUtil.setupSoundFile();

        QuickSell.quickSellGui = new CustomConfig();
        QuickSell.SetupQuickSellGUIFile();
    }

    private void setupSignFile()
    {
        ccSign.setup("Sign", null);
        ccSign.get().options().copyDefaults(true);
        ccSign.save();
    }

    // auto-completion
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String commandLabel, String[] args)
    {
        return TabCompleteUtil.onTabCompleteBody(this, sender, cmd, args);
    }

    @Override
    public void onDisable()
    {
        if (econ != null)
        {
            UserUtil.OnPluginDisable();
            ShopUtil.ForceSaveAllShop();
        }

        Bukkit.getScheduler().cancelTasks(this);
        console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " Disabled");
    }
}