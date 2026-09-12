package me.sat7.dynamicshop.files;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class DefaultsSyncTest
{
    private static YamlConfiguration file(String yaml) throws Exception
    {
        YamlConfiguration c = new YamlConfiguration();
        c.loadFromString(yaml);
        return c;
    }

    @Test
    public void untouchedOldDefaultIsUpdatedCustomValueIsKept() throws Exception
    {
        YamlConfiguration snapshot = new YamlConfiguration();

        // run 1: old plugin version writes its defaults
        YamlConfiguration f = file("");
        f.addDefault("A.KEY", "old a");
        f.addDefault("B.KEY", "old b");
        DefaultsSync.Sync(f, snapshot, null);
        f.options().copyDefaults(true);
        f = file(f.saveToString());

        f.set("B.KEY", "my custom b"); // admin edits B

        // run 2: new plugin version with changed defaults
        YamlConfiguration next = file(f.saveToString());
        next.addDefault("A.KEY", "new a");
        next.addDefault("B.KEY", "new b");
        next.addDefault("C.KEY", "added c");
        DefaultsSync.Result r = DefaultsSync.Sync(next, snapshot, null);
        next.options().copyDefaults(true);
        next = file(next.saveToString());

        assertEquals("new a", next.getString("A.KEY"));
        assertEquals("my custom b", next.getString("B.KEY"));
        assertEquals("added c", next.getString("C.KEY"));
        assertEquals(List.of("A.KEY"), r.updated());
        assertEquals(List.of("B.KEY"), r.keptCustom());
        assertEquals("new b", snapshot.getString("B.KEY"));

        // run 3: nothing changed -> no messages, no snapshot write
        YamlConfiguration again = file(next.saveToString());
        again.addDefault("A.KEY", "new a");
        again.addDefault("B.KEY", "new b");
        again.addDefault("C.KEY", "added c");
        DefaultsSync.Result r3 = DefaultsSync.Sync(again, snapshot, null);
        assertTrue(r3.updated().isEmpty());
        assertTrue(r3.keptCustom().isEmpty());
        assertFalse(r3.snapshotChanged());
    }

    @Test
    public void fileFromBeforeSnapshotsUsesKnownOldDefaults() throws Exception
    {
        YamlConfiguration snapshot = new YamlConfiguration();
        YamlConfiguration f = file("QUICK_SELL:\n  GUIDE_LORE: old text\nOTHER: 'my own'\nCUSTOM:\n  X: 'custom lore'\n");
        f.addDefault("QUICK_SELL.GUIDE_LORE", "new text");
        f.addDefault("OTHER", "default other");
        f.addDefault("CUSTOM.X", "new x");

        DefaultsSync.Result r = DefaultsSync.Sync(f, snapshot, Map.of(
                "QUICK_SELL.GUIDE_LORE", List.of("older text", "old text"),
                "CUSTOM.X", List.of("old x")));

        assertEquals("new text", f.getString("QUICK_SELL.GUIDE_LORE"));
        assertEquals("my own", f.getString("OTHER")); // no known history: never touched, no message
        assertEquals("custom lore", f.getString("CUSTOM.X"));
        assertEquals(List.of("QUICK_SELL.GUIDE_LORE"), r.updated());
        assertEquals(List.of("CUSTOM.X"), r.keptCustom());
    }
}
