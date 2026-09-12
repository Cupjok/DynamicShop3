package me.sat7.dynamicshop.utilities;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.Assert.*;

public class CommandItemUtilTest
{
    @Test
    public void typeRoundTripKeepsCommandsWhenSwitchedBack()
    {
        YamlConfiguration data = new YamlConfiguration();
        data.set("3.mat", "PAPER");
        data.set("3.value", 10.0);
        assertFalse(CommandItemUtil.IsCommandItem(data, "3"));

        CommandItemUtil.SetCommandItem(data, "3", true);
        CommandItemUtil.SetCommands(data, "3", List.of("say hi", "give {player} diamond 1"));
        assertTrue(CommandItemUtil.IsCommandItem(data, "3"));
        assertFalse(CommandItemUtil.IsCommandItem(data, "4"));

        CommandItemUtil.SetCommandItem(data, "3", false);
        assertFalse(CommandItemUtil.IsCommandItem(data, "3"));
        assertEquals(2, CommandItemUtil.GetCommands(data, "3").size());

        CommandItemUtil.SetCommandItem(data, "3", true);
        assertEquals(List.of("say hi", "give {player} diamond 1"), CommandItemUtil.GetCommands(data, "3"));
    }

    @Test
    public void emptyValuesRemoveKeys()
    {
        YamlConfiguration data = new YamlConfiguration();
        CommandItemUtil.SetName(data, "0", "&aName");
        CommandItemUtil.SetLore(data, "0", List.of("a"));
        CommandItemUtil.SetCommands(data, "0", List.of("say x"));
        CommandItemUtil.SetName(data, "0", "");
        CommandItemUtil.SetLore(data, "0", List.of());
        CommandItemUtil.SetCommands(data, "0", List.of());
        assertFalse(data.contains("0.cmdName"));
        assertFalse(data.contains("0.cmdLore"));
        assertFalse(data.contains("0.commands"));
        assertEquals("", CommandItemUtil.GetName(data, "0"));
    }

    @Test
    public void commandInputIsNormalized()
    {
        assertEquals("give {player} diamond 1", CommandItemUtil.NormalizeCommandInput("  /give {player} diamond 1 "));
        assertEquals("say hi", CommandItemUtil.NormalizeCommandInput("say hi"));
        assertEquals("", CommandItemUtil.NormalizeCommandInput("/"));
        assertEquals("", CommandItemUtil.NormalizeCommandInput(null));
    }

    @Test
    public void placeholdersAreReplaced()
    {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertEquals("lp user Steve parent add vip 00000000-0000-0000-0000-000000000001 in Ranks",
                CommandItemUtil.BuildCommand("/lp user {player} parent add vip {uuid} in {shop}", "Steve", id, "Ranks"));
    }

    @Test
    public void loreInputSplitsOnLiteralBackslashN()
    {
        assertEquals(List.of("&aLine 1", "&#FF5555Line 2", ""), CommandItemUtil.SplitLoreInput("&aLine 1\\n&#FF5555Line 2\\n"));
        assertEquals(List.of("<gradient:#FF0000:#0000FF>one line</gradient>"),
                CommandItemUtil.SplitLoreInput("<gradient:#FF0000:#0000FF>one line</gradient>"));
        assertTrue(CommandItemUtil.SplitLoreInput("").isEmpty());
        assertEquals(CommandItemUtil.MAX_LORE_LINES, CommandItemUtil.SplitLoreInput("x\\n".repeat(50)).size());
    }
}
