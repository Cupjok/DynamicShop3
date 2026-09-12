package me.sat7.dynamicshop.files;

import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.constants.Constants;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps generated YAML files (language files, Layout.yml) in step with changed built-in defaults, without deleting
 * the file and without touching anything the admin customised.
 *
 * For every default key, the default that was last written is remembered in plugins/DynamicShop/.defaults/<file>.yml.
 * On start:
 * - value still equals the remembered old default (never customised) and the default changed -> replaced by the new one
 * - value differs from both (customised) -> kept; logged once, when the default changes
 * - missing -> added by copyDefaults as before
 *
 * Files from before this snapshot existed have no remembered defaults. For keys whose default changed back then,
 * pass the old default texts as {@code knownOldDefaults}: a value equal to one of them is updated the same way.
 */
public final class DefaultsSync
{
    private DefaultsSync()
    {

    }

    private static final String FOLDER = ".defaults";

    public record Result(List<String> updated, List<String> keptCustom, boolean snapshotChanged) {}

    /** Call after all addDefault(...) calls and before copyDefaults(true) + save(). */
    public static void Apply(CustomConfig target, String fileId, Map<String, List<Object>> knownOldDefaults)
    {
        if (target.IsLoadFailed())
            return; // unreadable file: leave it and its snapshot alone until it is fixed

        CustomConfig snapshot = new CustomConfig();
        snapshot.setup(fileId, FOLDER);

        Result r = Sync(target.get(), snapshot.get(), knownOldDefaults);

        for (String key : r.updated())
            DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " " + fileId + ".yml: '" + key + "' updated to the new default text.");
        for (String key : r.keptCustom())
            DynamicShop.console.sendMessage(Constants.DYNAMIC_SHOP_PREFIX + " " + fileId + ".yml: '" + key
                    + "' has a new default, but your customised text was kept. Delete that line to get the new default.");

        if (r.snapshotChanged())
            snapshot.save();
    }

    /** Pure logic, no files: updates {@code file} and {@code snapshot} in place. */
    public static Result Sync(FileConfiguration file, ConfigurationSection snapshot, Map<String, List<Object>> knownOldDefaults)
    {
        List<String> updated = new ArrayList<>();
        List<String> keptCustom = new ArrayList<>();
        boolean snapshotChanged = false;

        Configuration defaults = file.getDefaults();
        if (defaults == null)
            return new Result(updated, keptCustom, false);

        for (String key : defaults.getKeys(true))
        {
            if (defaults.isConfigurationSection(key))
                continue;

            Object newDefault = defaults.get(key);
            Object remembered = snapshot.isConfigurationSection(key) ? null : snapshot.get(key);

            if (file.isSet(key) && !file.isConfigurationSection(key))
            {
                Object current = file.get(key);
                if (!Objects.equals(current, newDefault))
                {
                    boolean untouchedOldDefault;
                    boolean defaultChanged;
                    if (remembered != null)
                    {
                        untouchedOldDefault = Objects.equals(current, remembered);
                        defaultChanged = !Objects.equals(remembered, newDefault);
                    }
                    else
                    {
                        List<Object> olds = knownOldDefaults == null ? null : knownOldDefaults.get(key);
                        untouchedOldDefault = olds != null && olds.contains(current);
                        defaultChanged = olds != null;
                    }

                    if (untouchedOldDefault)
                    {
                        file.set(key, newDefault);
                        updated.add(key);
                    }
                    else if (defaultChanged)
                    {
                        keptCustom.add(key);
                    }
                }
            }

            if (!Objects.equals(remembered, newDefault))
            {
                snapshot.set(key, newDefault);
                snapshotChanged = true;
            }
        }

        return new Result(updated, keptCustom, snapshotChanged);
    }
}
