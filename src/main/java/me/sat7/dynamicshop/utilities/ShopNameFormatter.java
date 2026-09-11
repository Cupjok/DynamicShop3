package me.sat7.dynamicshop.utilities;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEventSource;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns admin-configured shop names/titles into Adventure components with full colour support.
 *
 * Accepted syntax (can be mixed):
 * - legacy codes with '&' or '§': colours 0-9 a-f, formats k l m n o, reset r
 * - hex: &#RRGGBB, §#RRGGBB, &x&R&R&G&G&B&B (BungeeCord style), and bare #RRGGBB (when UI.UseHexColorCode is on)
 * - MiniMessage colour/format tags only: <red>, <#FF5555>, <color:#FF5555>, <bold>, <italic>, <underlined>,
 *   <strikethrough>, <obfuscated>, <gradient:...>, <rainbow>, <transition:...>, <reset>
 *
 * Only visual tags are resolved. Click, hover, insertion, font, keybind, translatable, selector, score, nbt and
 * newline tags are not registered, so they stay literal text; the result is additionally stripped of any click/
 * hover/insertion/font style as a second line of defence. Malformed input never throws: it falls back to plain text.
 *
 * Legacy-style colour codes (&a, &#RRGGBB, #RRGGBB) reset formatting like vanilla legacy text does, so put format
 * codes after the colour: "&#FF5555&lName", not "&l&#FF5555Name".
 */
public final class ShopNameFormatter
{
    private ShopNameFormatter()
    {

    }

    /** Longer input is cut before parsing; inventory titles and item names are far shorter than this anyway. */
    public static final int MAX_INPUT_LENGTH = 1024;

    private static final MiniMessage MINI_MESSAGE = MiniMessage.builder()
            .tags(TagResolver.resolver(
                    StandardTags.color(),
                    StandardTags.decorations(),
                    StandardTags.gradient(),
                    StandardTags.rainbow(),
                    StandardTags.transition(),
                    StandardTags.reset()))
            .strict(false)
            .build();

    // Something that looks like a MiniMessage tag. Copied verbatim so bare hex inside tag arguments
    // (<gradient:#FF0000:#00FF00>) is not rewritten. Whether the tag is allowed is MiniMessage's job.
    private static final Pattern TAG = Pattern.compile("<[/!]?[A-Za-z#][^<>]*>");

    private static final String LEGACY_CODES = "0123456789abcdefklmnor";
    private static final String[] LEGACY_COLOR_NAMES = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray",
            "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    };
    private static final String[] LEGACY_FORMAT_TAGS = {
            "<obfuscated>", "<bold>", "<strikethrough>", "<underlined>", "<italic>", "<reset>"
    };

    private static final Pattern STRIP_LEGACY = Pattern.compile("(?i)[&§](#[0-9a-f]{6}|[0-9a-fk-orx])");

    /**
     * For inventory titles.
     *
     * @param bareHex whether a bare "#RRGGBB" is treated as a colour (DynamicShop's UI.UseHexColorCode option)
     */
    public static Component format(String raw, boolean bareHex)
    {
        if (raw == null || raw.isEmpty())
            return Component.empty();

        String input = raw.length() > MAX_INPUT_LENGTH ? raw.substring(0, MAX_INPUT_LENGTH) : raw;
        try
        {
            return sanitize(MINI_MESSAGE.deserialize(toMiniMessage(input, bareHex)));
        }
        catch (RuntimeException e)
        {
            return Component.text(stripLegacy(input));
        }
    }

    /** For item display names: like {@link #format} but not italic unless the text asks for italic. */
    public static Component formatItemName(String raw, boolean bareHex)
    {
        return format(raw, bareHex).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    /** Rewrites legacy and bare-hex colour codes into MiniMessage tags; everything else is left untouched. */
    static String toMiniMessage(String raw, boolean bareHex)
    {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        Matcher tag = TAG.matcher(raw);
        int n = raw.length();
        int i = 0;
        // true when the previous character was visible text that is a letter/digit (not part of a code or tag)
        boolean afterWordChar = false;
        while (i < n)
        {
            char c = raw.charAt(i);

            if (c == '<')
            {
                tag.region(i, n);
                if (tag.lookingAt())
                {
                    out.append(tag.group());
                    i = tag.end();
                }
                else
                {
                    out.append("\\<"); // a lone '<' is text, never the start of a tag
                    i++;
                }
                afterWordChar = false;
                continue;
            }

            if ((c == '&' || c == '§') && i + 1 < n)
            {
                char d = raw.charAt(i + 1);

                // "R&D Shop", "A&B Shop", "M&M": an '&' glued between a word character and an UPPER-case letter is
                // ordinary text, not a colour code. Lower-case codes ("Red&aGreen"), codes at the start or after
                // other codes ("&c&LBold"), digits, hex (&#RRGGBB, &x...) and '§' are unaffected.
                if (c == '&' && afterWordChar && d >= 'A' && d <= 'Z')
                {
                    out.append(c);
                    i++;
                    afterWordChar = false;
                    continue;
                }

                // &#RRGGBB
                if (d == '#' && isHex(raw, i + 2, 6))
                {
                    appendLegacyColor(out, "#" + raw.substring(i + 2, i + 8));
                    i += 8;
                    afterWordChar = false;
                    continue;
                }

                // &x&R&R&G&G&B&B
                if (d == 'x' || d == 'X')
                {
                    String hex = bungeeHex(raw, i);
                    if (hex != null)
                    {
                        appendLegacyColor(out, "#" + hex);
                        i += 14;
                        afterWordChar = false;
                        continue;
                    }
                }

                int code = LEGACY_CODES.indexOf(Character.toLowerCase(d));
                if (code >= 0)
                {
                    if (code < LEGACY_COLOR_NAMES.length)
                        appendLegacyColor(out, LEGACY_COLOR_NAMES[code]);
                    else
                        out.append(LEGACY_FORMAT_TAGS[code - LEGACY_COLOR_NAMES.length]);
                    i += 2;
                    afterWordChar = false;
                    continue;
                }
            }

            // bare #RRGGBB: the first six hex digits, same rule as LangUtil.HEX_PATTERN ("#7289daDiscord" works)
            if (c == '#' && bareHex && isHex(raw, i + 1, 6))
            {
                appendLegacyColor(out, "#" + raw.substring(i + 1, i + 7));
                i += 7;
                afterWordChar = false;
                continue;
            }

            out.append(c);
            i++;
            afterWordChar = Character.isLetterOrDigit(c);
        }
        return out.toString();
    }

    /** Lore lines (start page buttons): each line like {@link #formatItemName}. Null lines become empty lines. */
    public static List<Component> formatLore(List<String> lines, boolean bareHex)
    {
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines)
            out.add(formatItemName(line, bareHex));
        return out;
    }

    /** Removes anything that is not purely visual (click/hover/insertion/font), recursively. */
    public static Component sanitize(Component component)
    {
        Style style = component.style();
        if (style.clickEvent() != null || style.hoverEvent() != null || style.insertion() != null || style.font() != null)
        {
            component = component.style(style.toBuilder()
                    .clickEvent((ClickEvent) null)
                    .hoverEvent((HoverEventSource<?>) null)
                    .insertion(null)
                    .font(null)
                    .build());
        }

        List<Component> children = component.children();
        if (!children.isEmpty())
        {
            List<Component> clean = new ArrayList<>(children.size());
            for (Component child : children)
                clean.add(sanitize(child));
            component = component.children(clean);
        }
        return component;
    }

    static String stripLegacy(String raw)
    {
        return STRIP_LEGACY.matcher(raw).replaceAll("");
    }

    // Legacy colours reset formatting (vanilla behaviour), hence the leading <reset>.
    private static void appendLegacyColor(StringBuilder out, String color)
    {
        out.append("<reset><").append(color).append('>');
    }

    private static String bungeeHex(String raw, int start)
    {
        if (start + 14 > raw.length())
            return null;

        StringBuilder hex = new StringBuilder(6);
        for (int k = 0; k < 6; k++)
        {
            int p = start + 2 + k * 2;
            char prefix = raw.charAt(p);
            char digit = raw.charAt(p + 1);
            if ((prefix != '&' && prefix != '§') || !isHexChar(digit))
                return null;
            hex.append(digit);
        }
        return hex.toString();
    }

    private static boolean isHex(String s, int from, int count)
    {
        if (from < 0 || from + count > s.length())
            return false;
        for (int k = from; k < from + count; k++)
        {
            if (!isHexChar(s.charAt(k)))
                return false;
        }
        return true;
    }

    private static boolean isHexChar(char c)
    {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
