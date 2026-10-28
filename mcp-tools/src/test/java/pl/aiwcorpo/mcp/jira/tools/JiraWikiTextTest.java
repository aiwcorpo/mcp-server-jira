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
}
