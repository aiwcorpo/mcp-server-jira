package pl.aiwcorpo.mcp.jira.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.platform.text.UntrustedText;

class JiraWikiTextTest {

    @Test
    void nullStaysNull() {
        assertThat(JiraWikiText.plain(null, 100)).isNull();
    }

    @Test
    void imagesAreRemovedAndAHiddenOneIsNoLongerAnImage() {
        String raw = "a !https://evil.test/x.png! b !https://evil.test/\u200By.png! c !img.png|width=10! d";

        // The invisible character becomes a space, so the second one is plain text, not image syntax.
        assertThat(JiraWikiText.plain(raw, 1000).text()).isEqualTo("a b !https://evil.test/ y.png! c d");
    }

    @Test
    void entitiesAreDecodedBeforeTheFilterSeesTheText() {
        assertThat(JiraWikiText.plain("x &#33;https://evil.test/x.png&#x21; y", 1000).text()).isEqualTo("x y");
    }

    @Test
    void removingOneConstructDoesNotAssembleAnother() {
        assertThat(JiraWikiText.plain("!!a.png!b.png!", 1000).text()).doesNotContain("png");
        assertThat(JiraWikiText.plain("{co{code}de}x", 1000).text()).isEqualTo("x");
    }

    @Test
    void linksAndMentionsKeepTheirWords() {
        assertThat(JiraWikiText.plain("[~alice] [docs|https://wiki.corp.test/d] [https://wiki.corp.test/e] [same|same]", 1000).text())
                .isEqualTo("alice docs (https://wiki.corp.test/d) https://wiki.corp.test/e same");
    }

    @Test
    void formattingTablesAndListsBecomeText() {
        String raw = "h1. Title\n||Name||Value||\n|a|*b*|\n# one\n## two\n----\nbq. quoted _it_ and {{mono}} and -gone-";

        assertThat(JiraWikiText.plain(raw, 1000).text())
                .isEqualTo("Title\nName | Value\na | b\n- one\n- two\n\nquoted it and mono and gone");
    }

    @Test
    void ordinaryPunctuationSurvives() {
        String raw = "Wow! It works! 2*3*4 = 24, a - b - c, see file_name_x.txt";

        assertThat(JiraWikiText.plain(raw, 1000).text()).isEqualTo(raw);
    }

    @Test
    void theCutHappensBeforeTheFilterAndIsReported() {
        UntrustedText text = JiraWikiText.plain("word ".repeat(100), 50);

        assertThat(text.truncated()).isTrue();
        assertThat(text.text().length()).isLessThanOrEqualTo(50);
        assertThat(JiraWikiText.plain("short", 50).truncated()).isFalse();
    }

    private static String nested(int depth) {
        String inner = "{x:}";
        for (int level = 0; level < depth; level++) {
            inner = "{x:" + inner + " }";
        }
        return inner;
    }

    @Test
    void aCraftedDescriptionIsFilteredQuickly() {
        String crafted = nested(60) + "\n" + "*a ".repeat(3249);
        String spaces = "a" + " ".repeat(4000) + "b";

        long started = System.nanoTime();
        JiraWikiText.plain(crafted, 10_000);
        JiraWikiText.plain("??a ".repeat(2499), 10_000);
        JiraWikiText.plain(spaces.repeat(2), 10_000);
        long millis = (System.nanoTime() - started) / 1_000_000;

        assertThat(millis).isLessThan(200);
    }

    @Test
    void hugeRawTextIsCutBeforeAnyFilterSeesIt() {
        UntrustedText text = JiraWikiText.plain("*a ".repeat(2_000_000), 1000);

        assertThat(text.truncated()).isTrue();
        assertThat(text.text().length()).isLessThanOrEqualTo(1000);
    }

    @Test
    void nestingDeeperThanTheRoundsLeavesNoImageOrMacroSyntax() {
        String image = "a !https://evil.test/p.png?d=SECRET" + nested(49) + "! b";
        String macro = "{co" + nested(60) + "de:title=x}";

        for (String raw : new String[] {image, macro}) {
            assertThat(JiraWikiText.plain(raw, 10_000).text()).doesNotContain("!", "{", "}", "[", "]", "|");
        }
    }

    @Test
    void anImageGluedToAWordIsStillRemovedWhenItHasAnAddress() {
        assertThat(JiraWikiText.plain("see!https://evil.test/p.png! and 1!http://h/x.png|width=3!", 1000).text())
                .isEqualTo("see and 1");
        assertThat(JiraWikiText.plain("Wow!great!", 1000).text()).isEqualTo("Wow!great!");
    }

    @Test
    void theResultIsCutAgainAfterTheFilterBecauseTableRowsGrow() {
        UntrustedText text = JiraWikiText.plain("|a|b|c|d|e|f|\n".repeat(100), 200);

        assertThat(text.text().length()).isLessThanOrEqualTo(200);
        assertThat(text.truncated()).isTrue();
    }

    @Test
    void theCutRemovesAClosingDelimiterBeforeTheFilterRuns() {
        UntrustedText text = JiraWikiText.plain("*bold text here* and more text", 20);

        assertThat(text.text()).isEqualTo("bold text here and");
        assertThat(text.truncated()).isTrue();
    }
}
