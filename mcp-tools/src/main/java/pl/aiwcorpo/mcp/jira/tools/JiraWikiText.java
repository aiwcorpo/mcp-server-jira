package pl.aiwcorpo.mcp.jira.tools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import pl.aiwcorpo.mcp.platform.text.TextHygiene;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

/**
 * Turns text that Jira stores as wiki markup (descriptions) into plain text for {@code text/plain}: formatting
 * syntax, macro tags and images are removed, links and mentions are left as the words they show.
 *
 * <p>Order for text from a system: entity decoding, {@link TextHygiene#normalize}, {@link UntrustedText#cut},
 * the markup filter below, {@link UntrustedText#plainCut}. The filter must see what the client will receive, so
 * it runs after normalization (a zero-width character inside {@code !https://host/x.png!} no longer hides the
 * image from it) and after the cut (a cut can remove a closing delimiter), and it repeats until nothing changes,
 * because removing one construct can join the pieces of another ({@code "!!a.png!b.png!"}). Backslash escapes
 * are not interpreted: {@code \*} stays as written, so no markup can appear that the filter has not seen.
 */
final class JiraWikiText {

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}));");
    private static final Pattern NAMED_ENTITY = Pattern.compile("&(nbsp|amp|lt|gt|quot|apos);");

    /** {@code !file.png!}, {@code !http://host/x.png|width=10!}, also inside a link label. */
    private static final Pattern IMAGE = Pattern.compile("(?<![\\p{L}\\p{N}])!(?=[^\\s!])[^\\s!|]+(?:\\|[^!\\n]*)?!");
    /** Macro tags: {@code {code:java}}, {@code {noformat}}, {@code {panel:title=x}}, {@code {color:red}}. */
    private static final Pattern MACRO_TAG = Pattern.compile("\\{[A-Za-z][A-Za-z0-9-]*(?::[^{}\\n]*)?\\}");
    private static final Pattern MONOSPACE = Pattern.compile("\\{\\{|\\}\\}");
    private static final Pattern LINK = Pattern.compile("\\[([^\\[\\]|\\n]*)(?:\\|([^\\[\\]|\\n]*))?(?:\\|[^\\[\\]\\n]*)?\\]");
    private static final Pattern HEADING = Pattern.compile("(?m)^[ \\t]*h[1-6]\\.[ \\t]+");
    private static final Pattern QUOTE_LINE = Pattern.compile("(?m)^[ \\t]*bq\\.[ \\t]+");
    private static final Pattern LIST_ITEM = Pattern.compile("(?m)^[ \\t]*(?:[*#-]+)[ \\t]+");
    private static final Pattern RULE = Pattern.compile("(?m)^[ \\t]*-{4,}[ \\t]*$");
    private static final Pattern TABLE_CELLS = Pattern.compile("[ \\t]*\\|{1,2}[ \\t]*");
    private static final Pattern TABLE_ROW = Pattern.compile("(?m)^[ \\t]*\\|.*$");
    private static final Pattern LINE_BREAK = Pattern.compile("\\\\\\\\");
    /** {@code *bold*}, {@code _italic_}, {@code +underline+}, {@code -strike-}, {@code ^sup^}, {@code ~sub~}, {@code ??cite??}. */
    private static final Pattern INLINE = Pattern.compile(
            "(?<![\\p{L}\\p{N}\\\\])(\\?\\?|[*_+^~-])(?=[^\\s])([^\\n]+?)(?<=[^\\s])\\1(?![\\p{L}\\p{N}])");
    private static final Pattern SPACE_RUN = Pattern.compile("[ \\t]{2,}");
    private static final Pattern TRAILING_SPACE = Pattern.compile("(?m)[ \\t]+$");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final int MAX_ROUNDS = 50;

    private JiraWikiText() {
    }

    static UntrustedText plain(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }
        String normalized = TextHygiene.normalize(decodeEntities(raw));
        String cut = UntrustedText.cut(normalized, maxLength);
        return UntrustedText.plainCut(strip(cut), cut.length() < normalized.length());
    }

    static String decodeEntities(String raw) {
        String numeric = NUMERIC_ENTITY.matcher(raw).replaceAll(match -> {
            int code = match.group(1) != null ? Integer.parseInt(match.group(1), 16) : Integer.parseInt(match.group(2));
            return Character.isValidCodePoint(code) ? Matcher.quoteReplacement(new String(Character.toChars(code))) : "";
        });
        return NAMED_ENTITY.matcher(numeric).replaceAll(match -> switch (match.group(1)) {
            case "nbsp" -> " ";
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            default -> "'";
        });
    }

    static String strip(String text) {
        String current = text;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            String next = round(current);
            if (next.equals(current)) {
                break;
            }
            current = next;
        }
        String compact = SPACE_RUN.matcher(TRAILING_SPACE.matcher(current).replaceAll("")).replaceAll(" ");
        return BLANK_LINES.matcher(compact).replaceAll("\n\n").strip();
    }

    private static String round(String text) {
        String result = IMAGE.matcher(text).replaceAll("");
        result = MONOSPACE.matcher(result).replaceAll("");
        result = MACRO_TAG.matcher(result).replaceAll("");
        result = LINK.matcher(result).replaceAll(JiraWikiText::linkText);
        result = TABLE_ROW.matcher(result).replaceAll(row -> Matcher.quoteReplacement(tableRow(row.group())));
        result = HEADING.matcher(result).replaceAll("");
        result = QUOTE_LINE.matcher(result).replaceAll("");
        result = RULE.matcher(result).replaceAll("");
        result = LIST_ITEM.matcher(result).replaceAll("- ");
        result = LINE_BREAK.matcher(result).replaceAll("\n");
        return INLINE.matcher(result).replaceAll("$2");
    }

    /** {@code [text|url]} -> {@code text (url)}, {@code [url]} -> {@code url}, {@code [~user]} -> {@code user}. */
    private static String linkText(java.util.regex.MatchResult link) {
        String label = link.group(1).strip();
        String target = link.group(2) == null ? "" : link.group(2).strip();
        String text;
        if (label.startsWith("~")) {
            text = label.substring(1);
        } else if (label.isEmpty()) {
            text = target;
        } else if (target.isEmpty() || target.equals(label)) {
            text = label;
        } else {
            text = label + " (" + target + ")";
        }
        return Matcher.quoteReplacement(text);
    }

    private static String tableRow(String row) {
        return TABLE_CELLS.matcher(row.strip()).replaceAll(" | ").strip().replaceAll("^\\| |\\s\\|$", "");
    }
}
