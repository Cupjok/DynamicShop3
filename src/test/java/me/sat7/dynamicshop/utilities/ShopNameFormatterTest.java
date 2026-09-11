package me.sat7.dynamicshop.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class ShopNameFormatterTest
{
    /** One visible run of text with its effective (inherited) style. */
    private record Run(String text, Style style)
    {
        TextColor color() { return style.color(); }
        boolean has(TextDecoration d) { return style.decoration(d) == TextDecoration.State.TRUE; }
    }

    private static List<Run> runs(Component c)
    {
        List<Run> out = new ArrayList<>();
        collect(c, Style.empty(), out);
        return out;
    }

    private static void collect(Component c, Style parent, List<Run> out)
    {
        Style effective = c.style().merge(parent, Style.Merge.Strategy.IF_ABSENT_ON_TARGET);
        if (c instanceof TextComponent t && !t.content().isEmpty())
            out.add(new Run(t.content(), effective));
        for (Component child : c.children())
            collect(child, effective, out);
    }

    private static Run runWith(Component c, String text)
    {
        for (Run r : runs(c))
            if (r.text().contains(text))
                return r;
        fail("no run containing '" + text + "' in " + runs(c));
        return null;
    }

    private static String plain(Component c)
    {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    // 1. plain name
    @Test
    public void plainNameIsUnchanged()
    {
        Component c = ShopNameFormatter.format("Normal Shop", true);
        assertEquals("Normal Shop", plain(c));
        for (Run r : runs(c))
        {
            assertNull(r.color());
            assertFalse(r.has(TextDecoration.BOLD));
        }
    }

    // 2. legacy colour (both & and §)
    @Test
    public void legacyColorCodes()
    {
        Component amp = ShopNameFormatter.format("&aGreen Shop", true);
        assertEquals("Green Shop", plain(amp));
        assertEquals(NamedTextColor.GREEN, runWith(amp, "Green").color());

        Component section = ShopNameFormatter.format("§3§lStart Page", true);
        assertEquals("Start Page", plain(section));
        Run r = runWith(section, "Start");
        assertEquals(NamedTextColor.DARK_AQUA, r.color());
        assertTrue(r.has(TextDecoration.BOLD));
    }

    @Test
    public void legacyColorResetsFormattingLikeVanilla()
    {
        Component c = ShopNameFormatter.format("&lBold&cRed", true);
        assertTrue(runWith(c, "Bold").has(TextDecoration.BOLD));
        Run red = runWith(c, "Red");
        assertEquals(NamedTextColor.RED, red.color());
        assertFalse(red.has(TextDecoration.BOLD));
    }

    // 3. single hex, all accepted spellings
    @Test
    public void singleHexColor()
    {
        TextColor expected = TextColor.fromHexString("#FF5555");
        for (String raw : new String[]{"#FF5555Hex Shop", "&#FF5555Hex Shop", "§#FF5555Hex Shop",
                "&x&F&F&5&5&5&5Hex Shop", "§x§F§F§5§5§5§5Hex Shop", "<#FF5555>Hex Shop", "<color:#FF5555>Hex Shop"})
        {
            Component c = ShopNameFormatter.format(raw, true);
            assertEquals(raw, "Hex Shop", plain(c));
            assertEquals(raw, expected, runWith(c, "Hex").color());
        }
    }

    @Test
    public void lowercaseHexIsAccepted()
    {
        Component c = ShopNameFormatter.format("#7289daDiscord", true);
        assertEquals(TextColor.fromHexString("#7289DA"), runWith(c, "Discord").color());
    }

    @Test
    public void bareHexCanBeDisabled()
    {
        Component c = ShopNameFormatter.format("#FF5555Shop", false);
        assertEquals("#FF5555Shop", plain(c));
        // the explicit spellings keep working
        assertEquals(TextColor.fromHexString("#FF5555"), runWith(ShopNameFormatter.format("&#FF5555Shop", false), "Shop").color());
    }

    @Test
    public void textThatIsNotSixHexDigitsStaysText()
    {
        assertEquals("Shop #1", plain(ShopNameFormatter.format("Shop #1", true)));
        // like LangUtil.HEX_PATTERN, the first six hex digits are the colour
        Component seven = ShopNameFormatter.format("#FF55559", true);
        assertEquals("9", plain(seven));
        assertEquals(TextColor.fromHexString("#FF5555"), runWith(seven, "9").color());
    }

    // 4. multiple hex colours
    @Test
    public void multipleHexColors()
    {
        Component c = ShopNameFormatter.format("#FF5555Red #00FFAAMint &#7289DABlurple", true);
        assertEquals("Red Mint Blurple", plain(c));
        assertEquals(TextColor.fromHexString("#FF5555"), runWith(c, "Red").color());
        assertEquals(TextColor.fromHexString("#00FFAA"), runWith(c, "Mint").color());
        assertEquals(TextColor.fromHexString("#7289DA"), runWith(c, "Blurple").color());
    }

    // 5. hex + bold
    @Test
    public void hexWithBold()
    {
        for (String raw : new String[]{"#FF5555&lBold", "&#FF5555&lBold", "<#FF5555><bold>Bold", "<bold><#FF5555>Bold"})
        {
            Run r = runWith(ShopNameFormatter.format(raw, true), "Bold");
            assertEquals(raw, TextColor.fromHexString("#FF5555"), r.color());
            assertTrue(raw, r.has(TextDecoration.BOLD));
        }
    }

    // 6. hex + italic
    @Test
    public void hexWithItalic()
    {
        for (String raw : new String[]{"#00FFAA&oItalic", "<#00FFAA><italic>Italic"})
        {
            Run r = runWith(ShopNameFormatter.format(raw, true), "Italic");
            assertEquals(raw, TextColor.fromHexString("#00FFAA"), r.color());
            assertTrue(raw, r.has(TextDecoration.ITALIC));
        }
    }

    // 7. several formatting elements and segments
    @Test
    public void multipleFormattingElements()
    {
        Component c = ShopNameFormatter.format("#FF5555&l&nBig &r#00FFAA&o&mSale<reset> <gradient:#FF5555:#7289DA>Fade</gradient>", true);
        assertEquals("Big Sale Fade", plain(c));

        Run big = runWith(c, "Big");
        assertEquals(TextColor.fromHexString("#FF5555"), big.color());
        assertTrue(big.has(TextDecoration.BOLD));
        assertTrue(big.has(TextDecoration.UNDERLINED));

        Run sale = runWith(c, "Sale");
        assertEquals(TextColor.fromHexString("#00FFAA"), sale.color());
        assertTrue(sale.has(TextDecoration.ITALIC));
        assertTrue(sale.has(TextDecoration.STRIKETHROUGH));
        assertFalse(sale.has(TextDecoration.BOLD));

        // gradient: every letter coloured, first letter is the start colour
        Run f = runWith(c, "F");
        assertEquals(TextColor.fromHexString("#FF5555"), f.color());
    }

    @Test
    public void allLegacyFormatsMap()
    {
        Run r = runWith(ShopNameFormatter.format("&k&l&m&n&oX", true), "X");
        assertTrue(r.has(TextDecoration.OBFUSCATED));
        assertTrue(r.has(TextDecoration.BOLD));
        assertTrue(r.has(TextDecoration.STRIKETHROUGH));
        assertTrue(r.has(TextDecoration.UNDERLINED));
        assertTrue(r.has(TextDecoration.ITALIC));
    }

    // 8. malformed input never throws and never produces structure
    @Test
    public void malformedFormattingIsHarmless()
    {
        String[] inputs = {
                "<bold", "Shop <", "a < b > c", "<#GGGGGG>Bad", "<gradient:#zz>Bad", "</bold>Close", "&", "§", "&z", "&#12",
                "#12345", "&x&1&2", "<<<>>>", "\\<bold>escaped", "<color:>", "<gradient>", "<rainbow:!!>R",
                "&#FF5555", "", "   ", "<reset>", "\u0000\u0001", "<bold><italic><underlined>unclosed"
        };
        for (String raw : inputs)
        {
            Component c = ShopNameFormatter.format(raw, true);
            assertNotNull(raw, c);
            String p = plain(c);
            assertFalse(raw + " -> " + p, p.contains("\n"));
        }
        assertEquals("a < b > c", plain(ShopNameFormatter.format("a < b > c", true)));
        assertEquals("Shop <", plain(ShopNameFormatter.format("Shop <", true)));
        assertEquals("", plain(ShopNameFormatter.format(null, true)));
    }

    @Test
    public void interactiveTagsAreNotResolved()
    {
        String[] inputs = {
                "<click:run_command:/op attacker>Free</click>",
                "<hover:show_text:'x'>Hover</hover>",
                "<insert:/op attacker>Insert</insert>",
                "<font:uniform>Font</font>",
                "<key:key.jump>",
                "<lang:block.minecraft.diamond_block>",
                "<selector:@a>",
                "<score:@p:obj>",
                "<nbt:block:'0 0 0':Items>",
                "line<newline>break",
                "<br>"
        };
        for (String raw : inputs)
        {
            Component c = ShopNameFormatter.format(raw, true);
            assertNoInteractiveStyle(raw, c);
            assertTrue(raw, c.children().stream().allMatch(ch -> ch instanceof TextComponent) || c instanceof TextComponent);
            assertFalse(raw, plain(c).contains("\n"));
        }
        // the click tag stays visible as literal text instead of becoming an action
        assertTrue(plain(ShopNameFormatter.format("<click:run_command:/op attacker>Free</click>", true)).contains("<click:"));
    }

    @Test
    public void sanitizeStripsInteractiveStyleFromAnyComponent()
    {
        Component evil = Component.text("x")
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/op attacker"))
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(Component.text("h")))
                .insertion("/op")
                .append(Component.text("y").clickEvent(net.kyori.adventure.text.event.ClickEvent.suggestCommand("/x")));
        assertNoInteractiveStyle("sanitized", ShopNameFormatter.sanitize(evil));
    }

    private static void assertNoInteractiveStyle(String label, Component c)
    {
        assertNull(label, c.clickEvent());
        assertNull(label, c.hoverEvent());
        assertNull(label, c.insertion());
        assertNull(label, c.font());
        for (Component child : c.children())
            assertNoInteractiveStyle(label, child);
    }

    @Test
    public void itemNamesAreNotItalicUnlessAsked()
    {
        Component plainName = ShopNameFormatter.formatItemName("§3Weapons", true);
        assertEquals(TextDecoration.State.FALSE, plainName.decoration(TextDecoration.ITALIC));

        Run italic = runWith(ShopNameFormatter.formatItemName("<#FF5555><italic>Fancy", true), "Fancy");
        assertTrue(italic.has(TextDecoration.ITALIC));
    }

    @Test
    public void overlongInputIsCut()
    {
        String raw = "a".repeat(ShopNameFormatter.MAX_INPUT_LENGTH + 500);
        assertEquals(ShopNameFormatter.MAX_INPUT_LENGTH, plain(ShopNameFormatter.format(raw, true)).length());
    }

    // 9. existing shop configuration / start page files keep rendering the same text
    @Test
    public void existingConfigurationsStillWork()
    {
        YamlConfiguration shop = new YamlConfiguration();
        shop.set("Options.title", "Sample Shop");
        assertEquals("Sample Shop", plain(ShopNameFormatter.format(shop.getString("Options.title", "SampleShop"), true)));

        // shop without a title falls back to the shop (file) name, as before
        YamlConfiguration untitled = new YamlConfiguration();
        assertEquals("SampleShop", plain(ShopNameFormatter.format(untitled.getString("Options.title", "SampleShop"), true)));

        // default Startpage.yml values
        Component title = ShopNameFormatter.format("§3§lStart Page", true);
        assertEquals("Start Page", plain(title));
        Component button = ShopNameFormatter.formatItemName("§3§lExample Button", true);
        assertEquals("Example Button", plain(button));
        Run r = runWith(button, "Example");
        assertEquals(NamedTextColor.DARK_AQUA, r.color());
        assertTrue(r.has(TextDecoration.BOLD));

        // a name picked from the shop list is stored as "§3" + shopName
        assertEquals("SampleShop", plain(ShopNameFormatter.formatItemName("§3SampleShop", true)));

        // the start page's default " " name stays a blank name
        assertEquals(" ", plain(ShopNameFormatter.formatItemName(" ", true)));
    }

    // ------------------------------------------------------------------ '&' inside normal text

    @Test
    public void ampersandInNormalTextStaysText()
    {
        for (String raw : new String[]{"R&D Shop", "A&B Shop", "Shop & More", "M&M", "Q&A Corner", "AT&T", "Tom & Jerry", "&", "a&", "Fish&Chips"})
        {
            Component c = ShopNameFormatter.format(raw, true);
            assertEquals(raw, raw, plain(c));
            for (Run r : runs(c))
                assertNull(raw + " must not be coloured: " + r, r.color());
        }
        // the shop GUI's dark_aqua base colour is untouched as well (no reset/colour inserted)
        assertEquals("R&D Shop", ShopNameFormatter.toMiniMessage("R&D Shop", true));
        assertEquals("A&B Shop", ShopNameFormatter.toMiniMessage("A&B Shop", true));
    }

    @Test
    public void legitimateLegacyCodesNextToTextStillWork()
    {
        Component c = ShopNameFormatter.format("&cRed&aGreen", true);
        assertEquals("RedGreen", plain(c));
        assertEquals(NamedTextColor.RED, runWith(c, "Red").color());
        assertEquals(NamedTextColor.GREEN, runWith(c, "Green").color());

        // upper-case codes are still codes at the start, after a space, or after another code
        assertEquals(NamedTextColor.GREEN, runWith(ShopNameFormatter.format("&AGreen", true), "Green").color());
        assertEquals(NamedTextColor.AQUA, runWith(ShopNameFormatter.format("Shop &BBlue", true), "Blue").color());
        Run bold = runWith(ShopNameFormatter.format("&c&LBold", true), "Bold");
        assertEquals(NamedTextColor.RED, bold.color());
        assertTrue(bold.has(TextDecoration.BOLD));

        // '§' is never ordinary text
        assertEquals(NamedTextColor.LIGHT_PURPLE, runWith(ShopNameFormatter.format("R§DShop", true), "Shop").color());

        // hex and BungeeCord hex right after a word still work
        assertEquals(TextColor.fromHexString("#FF5555"), runWith(ShopNameFormatter.format("Big&#FF5555Hex", true), "Hex").color());
        assertEquals(TextColor.fromHexString("#FF5555"), runWith(ShopNameFormatter.format("Big&x&F&F&5&5&5&5Hex", true), "Hex").color());

        // mixed: normal '&' text and real codes in one name
        Component mixed = ShopNameFormatter.format("&aR&D &lLab", true);
        assertEquals("R&D Lab", plain(mixed));
        assertEquals(NamedTextColor.GREEN, runWith(mixed, "R&D").color());
        assertTrue(runWith(mixed, "Lab").has(TextDecoration.BOLD));
    }

    // ------------------------------------------------------------------ Change Lore (start page button lore)

    private static Run loreRun(List<Component> lore, int line, String text)
    {
        return runWith(lore.get(line), text);
    }

    @Test
    public void changeLoreSupportsTheSameFormattingAsRename()
    {
        // what OnChat stores for "Change Lore" ("§f" + input), split by the start page LineBreak ("/")
        String stored = "§f" + "Plain line/&aGreen &bAqua/&#FF5555Hex &#00FFAAMint/&#FF5555&lBold&r &o&nItal &m&kX/R&D & More/<#7289DA>Mini";
        List<Component> lore = ShopNameFormatter.formatLore(java.util.Arrays.asList(stored.split("/")), true);
        assertEquals(6, lore.size());

        assertEquals("Plain line", plain(lore.get(0)));
        assertEquals(NamedTextColor.WHITE, loreRun(lore, 0, "Plain").color());

        assertEquals("Green Aqua", plain(lore.get(1)));
        assertEquals(NamedTextColor.GREEN, loreRun(lore, 1, "Green").color());
        assertEquals(NamedTextColor.AQUA, loreRun(lore, 1, "Aqua").color());

        assertEquals("Hex Mint", plain(lore.get(2)));
        assertEquals(TextColor.fromHexString("#FF5555"), loreRun(lore, 2, "Hex").color());
        assertEquals(TextColor.fromHexString("#00FFAA"), loreRun(lore, 2, "Mint").color());

        assertEquals("Bold Ital X", plain(lore.get(3)));
        Run b = loreRun(lore, 3, "Bold");
        assertEquals(TextColor.fromHexString("#FF5555"), b.color());
        assertTrue(b.has(TextDecoration.BOLD));
        Run it = loreRun(lore, 3, "Ital");
        assertTrue(it.has(TextDecoration.ITALIC));
        assertTrue(it.has(TextDecoration.UNDERLINED));
        Run x = loreRun(lore, 3, "X");
        assertTrue(x.has(TextDecoration.STRIKETHROUGH));
        assertTrue(x.has(TextDecoration.OBFUSCATED));

        assertEquals("R&D & More", plain(lore.get(4)));

        assertEquals(TextColor.fromHexString("#7289DA"), loreRun(lore, 5, "Mini").color());

        // lore lines are not italic unless asked (like Bukkit's legacy setLore)
        assertEquals(TextDecoration.State.FALSE, lore.get(0).decoration(TextDecoration.ITALIC));
    }

    @Test
    public void changeLoreMalformedInputIsHarmless()
    {
        List<Component> lore = ShopNameFormatter.formatLore(java.util.Arrays.asList(
                "§f<click:run_command:/op x>Click</click>", "§f<bold", "§f&", "§f&#12", "§f<hover:show_text:'x'>H</hover>", "", "§f<newline>n"), true);
        assertEquals(7, lore.size());
        for (Component c : lore)
        {
            assertNoInteractiveStyle("lore", c);
            assertFalse(plain(c).contains("\n"));
        }
        assertTrue(plain(lore.get(0)).contains("<click:"));
    }

    @Test
    public void existingPlainAndLegacyLoreUnchanged()
    {
        // default Startpage.yml example lore + lang hint lines (legacy § codes)
        List<Component> lore = ShopNameFormatter.formatLore(java.util.Arrays.asList(
                "§fThis is Example Button", "§aClick empty slot to create new button", "§e§nShift+Click: Move"), true);
        assertEquals("This is Example Button", plain(lore.get(0)));
        assertEquals(NamedTextColor.WHITE, loreRun(lore, 0, "Example").color());
        assertEquals(NamedTextColor.GREEN, loreRun(lore, 1, "Click").color());
        Run r = loreRun(lore, 2, "Shift");
        assertEquals(NamedTextColor.YELLOW, r.color());
        assertTrue(r.has(TextDecoration.UNDERLINED));
    }

    @Test
    public void toMiniMessageLeavesTagArgumentsAlone()
    {
        assertEquals("<gradient:#FF0000:#00FF00>x", ShopNameFormatter.toMiniMessage("<gradient:#FF0000:#00FF00>x", true));
        assertEquals("<reset><#FF5555>x", ShopNameFormatter.toMiniMessage("#FF5555x", true));
        assertEquals("\\<3", ShopNameFormatter.toMiniMessage("<3", true));
    }
}
