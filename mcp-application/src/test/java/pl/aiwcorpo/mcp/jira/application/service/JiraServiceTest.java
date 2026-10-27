package pl.aiwcorpo.mcp.jira.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;

class JiraServiceTest {

    private static final JiraIssue ABC_1 = issue("ABC-1", "ABC");

    private final FakeJira jira = new FakeJira();
    private final JiraService service = new JiraService(jira);

    @Test
    void returnsTheIssueJiraShows() {
        assertThat(service.getIssue("ABC-1").summary()).isEqualTo("Summary of ABC-1");
    }

    @Test
    void anIssueJiraDoesNotShowIsNotFoundOrDenied() {
        assertThatThrownBy(() -> service.getIssue("ABC-2")).isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void searchInAnInvisibleProjectNeverReachesTheSearch() {
        assertThatThrownBy(() -> service.searchIssues(new IssueSearch("HR", null, null, null, null)))
                .isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(jira.searches).isEmpty();
    }

    @Test
    void searchKeepsOnlyIssuesOfTheRequestedProject() {
        jira.searchAnswer = new IssueSearchResult(2, List.of(ABC_1, issue("DEF-1", "DEF")));

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", "Open", null, null, null));

        assertThat(result.issues()).extracting(JiraIssue::key).containsExactly("ABC-1");
        assertThat(jira.searches).singleElement().satisfies(s -> assertThat(s.status()).isEqualTo("Open"));
    }

    @Test
    void transitionIsMatchedByIdOrByNameIgnoringCase() {
        assertThat(service.transitionIssue("ABC-1", "21").name()).isEqualTo("In Progress");
        assertThat(service.transitionIssue("ABC-1", "in progress").id()).isEqualTo("21");
        assertThat(jira.transitioned).containsExactly("ABC-1:21", "ABC-1:21");
    }

    @Test
    void anUnknownTransitionIsNotAttempted() {
        assertThatThrownBy(() -> service.transitionIssue("ABC-1", "Close"))
                .isInstanceOf(TransitionNotAvailableException.class);
        assertThatThrownBy(() -> service.transitionIssue("ABC-2", "21"))
                .isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(jira.transitioned).isEmpty();
    }

    @Test
    void aCommentOnAnInvisibleIssueIsNotFoundOrDenied() {
        assertThat(service.addComment("ABC-1", "hello").id()).isEqualTo("100");
        assertThatThrownBy(() -> service.addComment("ABC-2", "hello")).isInstanceOf(NotFoundOrDeniedException.class);
    }

    private static JiraIssue issue(String key, String project) {
        return new JiraIssue(key, project, "Task", "Open", null, "2026-10-20T12:00:00.000+0000",
                "Summary of " + key, null);
    }

    private static final class FakeJira implements JiraResourcePort {

        final Map<String, JiraIssue> issues = Map.of("ABC-1", ABC_1);
        final List<IssueSearch> searches = new ArrayList<>();
        final List<String> transitioned = new ArrayList<>();
        IssueSearchResult searchAnswer = new IssueSearchResult(0, List.of());

        @Override
        public Optional<JiraProject> findProject(String projectKey) {
            return "ABC".equals(projectKey) ? Optional.of(new JiraProject("ABC", "")) : Optional.empty();
        }

        @Override
        public Optional<JiraIssue> findIssue(String issueKey) {
            return Optional.ofNullable(issues.get(issueKey));
        }

        @Override
        public IssueSearchResult search(IssueSearch search, int maxResults) {
            searches.add(search);
            return searchAnswer;
        }

        @Override
        public Optional<JiraComment> addComment(String issueKey, String body) {
            return issues.containsKey(issueKey)
                    ? Optional.of(new JiraComment(issueKey, "100", "2026-10-27T10:00:00.000+0000"))
                    : Optional.empty();
        }

        @Override
        public Optional<List<IssueTransition>> transitions(String issueKey) {
            return issues.containsKey(issueKey)
                    ? Optional.of(List.of(new IssueTransition("21", "In Progress", "In Progress")))
                    : Optional.empty();
        }

        @Override
        public boolean transition(String issueKey, String transitionId) {
            transitioned.add(issueKey + ":" + transitionId);
            return true;
        }
    }
}
