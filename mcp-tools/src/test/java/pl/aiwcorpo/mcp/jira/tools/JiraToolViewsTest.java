package pl.aiwcorpo.mcp.jira.tools;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JiraToolViewsTest {

    @Test
    void aUserNameFromTheDirectoryLosesInvisibleCharactersAndIsCut() {
        assertThat(JiraToolViews.username("al‮ice​")).doesNotContain("‮", "​");
        assertThat(JiraToolViews.username("a".repeat(300))).hasSize(JiraToolViews.MAX_USERNAME);
        assertThat(JiraToolViews.username(null)).isNull();
    }
}
