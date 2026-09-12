package me.sat7.dynamicshop.files;

import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.constants.Constants;

import org.apache.commons.io.FilenameUtils;
import org.bukkit.Bukkit;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CustomConfig
{
    private File file;
    protected FileConfiguration customFile; // 버킷의 데이터 타입

    // 파일을 읽지 못한 경우 (YAML 문법 오류 등). 이 상태에서는 원본 파일을 절대 덮어쓰지 않음
    private boolean loadFailed;
    private boolean saveBlockedWarned;

    //Finds or generates the custom config file
    public void setup(String name, String folder)
    {
        String path = name + ".yml";
        if (folder != null)
        {
            path = folder + "/" + path;

            File directory = new File(DynamicShop.plugin.getDataFolder() + "/" + folder);
            if (!directory.exists())
            {
                directory.mkdir();
            }
        }

        setupFile(new File(DynamicShop.plugin.getDataFolder(), path));
    }

    void setupFile(File target)
    {
        file = target;

        if (!file.exists())
        {
            try
            {
                // 최초 실행 시 플러그인 데이터 폴더 자체가 아직 없을 수 있음
                File parent = file.getParentFile();
                if (parent != null && !parent.exists())
                {
                    parent.mkdirs();
                }

                file.createNewFile();
            } catch (IOException e)
            {
                Log(Level.SEVERE, "Fatal error! Config Setup Fail. File name: " + file.getName(), null);
            }
        }

        customFile = Load();
    }

    public boolean open(String name, String folder)
    {
        file = new File(DynamicShop.plugin.getDataFolder(), folder + "/" + name + ".yml");

        if (!file.exists())
        {
            DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + name + " not found");
            return false;
        }

        customFile = Load();
        return true;
    }

    /**
     * Reads the file. A file that exists but cannot be parsed is copied to "<name>.yml.broken-<time>" and marked so
     * that save() never replaces it with the (empty) in-memory data: a typo in a shop file must not wipe the shop.
     */
    private FileConfiguration Load()
    {
        loadFailed = false;
        saveBlockedWarned = false;

        YamlConfiguration yml = new YamlConfiguration();
        try
        {
            yml.load(file);
        }
        catch (FileNotFoundException ignored)
        {
            // 새 파일
        }
        catch (IOException | InvalidConfigurationException e)
        {
            loadFailed = true;

            String backupInfo;
            try
            {
                // 같은 내용의 백업이 이미 있으면 재시작마다 사본을 또 만들지 않음
                Path existing = FindIdenticalBackup(file.toPath());
                if (existing != null)
                {
                    backupInfo = "A copy already exists: " + existing.getFileName() + ".";
                }
                else
                {
                    Path backup = file.toPath().resolveSibling(file.getName() + ".broken-" + System.currentTimeMillis());
                    Files.copy(file.toPath(), backup, StandardCopyOption.COPY_ATTRIBUTES);
                    backupInfo = "A copy was saved as " + backup.getFileName() + ".";
                }
            }
            catch (IOException copyError)
            {
                backupInfo = "A backup copy could not be made (" + copyError.getMessage() + ").";
            }

            Log(Level.SEVERE, file.getPath() + " could not be read and is NOT loaded. The file will not be overwritten "
                    + "until it is fixed (then use /ds reload or restart). " + backupInfo, e);
        }
        return yml;
    }

    private static Path FindIdenticalBackup(Path target) throws IOException
    {
        Path dir = target.toAbsolutePath().getParent();
        if (dir == null)
            return null;

        String prefix = target.getFileName() + ".broken-";
        try (var files = Files.list(dir))
        {
            for (Path p : (Iterable<Path>) files::iterator)
            {
                if (p.getFileName().toString().startsWith(prefix) && Files.mismatch(p, target) == -1)
                    return p;
            }
        }
        return null;
    }

    public boolean IsLoadFailed()
    {
        return loadFailed;
    }

    public FileConfiguration get()
    {
        return customFile;
    }

    public void save()
    {
        if (loadFailed)
        {
            if (!saveBlockedWarned)
            {
                saveBlockedWarned = true;
                Log(Level.WARNING, "Not saving " + file.getPath() + ": the file could not be read at load time, "
                        + "so saving would replace it. Fix the file, then use /ds reload or restart.", null);
            }
            return;
        }

        try
        {
            AtomicWrite(file.toPath(), customFile.saveToString());
        } catch (IOException | RuntimeException e)
        {
            Log(Level.SEVERE, "Couldn't save file :" + e, null);
        }
    }

    /** Writes to a temporary file first and then replaces the target, so a crash mid-write cannot truncate it. */
    static void AtomicWrite(Path target, String content) throws IOException
    {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null)
            Files.createDirectories(parent);

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
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void Log(Level level, String message, Throwable error)
    {
        // Bukkit lookup instead of DynamicShop.plugin, so this also works outside a server (unit tests)
        org.bukkit.plugin.Plugin plugin = Bukkit.getServer() == null ? null : Bukkit.getPluginManager().getPlugin("DynamicShop");
        Logger logger = plugin != null ? plugin.getLogger() : Logger.getLogger("DynamicShop");
        if (error == null)
            logger.log(level, message);
        else
            logger.log(level, message + " (" + error.getMessage() + ")");
    }

    public void rename(String newName)
    {
        if (!file.exists())
            return;

        try
        {
            File newFile = new File(file.toPath().resolveSibling(newName) + ".yml");
            Files.copy(file.toPath(), newFile.toPath());
            file.delete();
            file = newFile;
        }
        catch (Exception e)
        {
            System.out.println("rename fail :" + e);
        }
    }

    public void copy(String newName)
    {
        if (!file.exists())
            return;

        try
        {
            File newFile = new File(file.toPath().resolveSibling(newName) + ".yml");
            Files.copy(file.toPath(), newFile.toPath());
        }
        catch (Exception e)
        {
            System.out.println("copy fail :" + e);
        }
    }

    public void delete()
    {
        if (file.exists())
        {
            if (!file.delete())
                System.out.println("file delete fail: " + file.getName());
        } else
        {
            System.out.println("file delete fail: not exist");
        }
    }

    public void reload()
    {
        customFile = Load();
    }

    public static FileConfiguration GetFileFromPath(String name, String folder)
    {
        File tempFile = new File(Bukkit.getServer().getPluginManager().getPlugin("DynamicShop").getDataFolder(), folder + "/" + name + ".yml");

        if (!tempFile.exists())
        {
            DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + name + " not found");
            return null;
        }

        return YamlConfiguration.loadConfiguration(tempFile);
    }

    public String GetFileName()
    {
        return file.getName();
    }

    public String GetFileNameWithoutExtension()
    {
        return FilenameUtils.getBaseName(file.getName());
    }
}
