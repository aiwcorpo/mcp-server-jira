package pl.aiwcorpo.mcp.jira.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;

class JqlBuilderTest {

    @Test
    void onlyTheProjectWhenNoFilterIsGiven() {
        assertThat(JqlBuilder.build(new IssueSearch("ABC", null, null, null, null)))
                .isEqualTo("project = \"ABC\" ORDER BY updated DESC");
    }

    @Test
    void everyFilterIsOneQuotedAndClause() {
        assertThat(JqlBuilder.build(new IssueSearch("ABC", "In Progress", "alice", "Bug", "login page")))
                .isEqualTo("project = \"ABC\" AND status = \"In Progress\" AND assignee = \"alice\""
                        + " AND issuetype = \"Bug\" AND text ~ \"login page\" ORDER BY updated DESC");
    }

    @Test
    void aValueCannotCloseItsStringAndAddAClause() {
        String jql = JqlBuilder.build(new IssueSearch("ABC", "x\" OR project = \"HR", null, null, null));

        assertThat(jql).isEqualTo("project = \"ABC\" AND status = \"x\\\" OR project = \\\"HR\" ORDER BY updated DESC");
        assertThat(JqlBuilder.quote("a\\\"")).isEqualTo("\"a\\\\\\\"\"");
    }

    @Test
    void controlCharactersAreRefused() {
        assertThatThrownBy(() -> JqlBuilder.quote("a\nb")).isInstanceOf(IllegalStateException.class);
    }
}
