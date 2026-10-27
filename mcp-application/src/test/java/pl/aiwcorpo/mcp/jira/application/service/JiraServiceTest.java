package pl.aiwcorpo.mcp.jira.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;
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
        assertThat(result.total()).as("hits that were dropped are not counted").isEqualTo(1);
        assertThat(jira.searches).singleElement().satisfies(s -> assertThat(s.status()).isEqualTo("Open"));
    }

    @Test
    void theTotalOfJiraStandsWhenNothingWasDropped() {
        jira.searchAnswer = new IssueSearchResult(140, List.of(ABC_1));

        assertThat(service.searchIssues(new IssueSearch("ABC", null, null, null, null)).total()).isEqualTo(140);
    }

    @Test
    void transitionIsMatchedByIdOrByNameIgnoringCase() {
        assertThat(service.transitionIssue("ABC-1", "21").transition().name()).isEqualTo("In Progress");
        assertThat(service.transitionIssue("ABC-1", "in progress").transition().id()).isEqualTo("21");
        assertThat(jira.transitioned).containsExactly("ABC-1:21", "ABC-1:21");
    }

    @Test
    void aNameMatchesWholeOrNotAtAll() {
        jira.available = List.of(new IssueTransition("23", "Do", "Doing"), new IssueTransition("31", "Done", "Done"));

        assertThat(service.transitionIssue("ABC-1", "Do").transition().id()).isEqualTo("23");
        assertThat(service.transitionIssue("ABC-1", "DONE").transition().id()).isEqualTo("31");
        assertThatThrownBy(() -> service.transitionIssue("ABC-1", "Don"))
                .isInstanceOf(TransitionNotAvailableException.class);
        assertThatThrownBy(() -> service.transitionIssue("ABC-1", "on"))
                .isInstanceOf(TransitionNotAvailableException.class);
        assertThat(jira.transitioned).containsExactly("ABC-1:23", "ABC-1:31");
    }

    @Test
    void aNameSharedByTwoTransitionsIsNotGuessed() {
        jira.available = List.of(new IssueTransition("21", "Done", "Done"), new IssueTransition("22", "done", "Rejected"));

        assertThatThrownBy(() -> service.transitionIssue("ABC-1", "Done"))
                .isInstanceOfSatisfying(AmbiguousTransitionException.class,
                        ex -> assertThat(ex.transitionIds()).containsExactly("21", "22"));
        assertThat(jira.transitioned).isEmpty();
        assertThat(service.transitionIssue("ABC-1", "22").transition().toStatus()).isEqualTo("Rejected");
        assertThat(jira.transitioned).containsExactly("ABC-1:22");
    }

    @Test
    void anIdWinsOverAnotherTransitionsName() {
        jira.available = List.of(new IssueTransition("21", "Start", "Doing"), new IssueTransition("22", "21", "Odd"));

        assertThat(service.transitionIssue("ABC-1", "21").transition().name()).isEqualTo("Start");
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
        assertThat(jira.commented).containsExactly("ABC-1");
    }

    @Test
    void writesGoToTheKeyJiraReportsAndAreReportedUnderIt() {
        assertThat(service.addComment("ABC-3", "hello").issueKey()).isEqualTo("DEF-5");
        TransitionOutcome moved = service.transitionIssue("ABC-3", "21");

        assertThat(moved.issueKey()).isEqualTo("DEF-5");
        assertThat(jira.commented).containsExactly("DEF-5");
        assertThat(jira.transitioned).containsExactly("DEF-5:21");
    }

    @Test
    void theCommentGoesToJiraAsLiteralText() {
        service.addComment("ABC-1", "see !https://evil.test/p.png?d=1! and [~admin] and [x|https://evil.test]");

        assertThat(jira.commentBody).isEqualTo(
                "{noformat}\nsee !https://evil.test/p.png?d=1! and [~admin] and [x|https://evil.test]\n{noformat}");
    }

    @Test
    void aNoformatTagInTheTextCannotEndTheBlock() {
        service.addComment("ABC-1", "a{noformat}!https://evil.test/p.png!{NOFORMAT:title=x}b{ noformat}c");

        assertThat(jira.commentBody).startsWith("{noformat}\n").endsWith("\n{noformat}");
        String inside = jira.commentBody.substring("{noformat}\n".length(),
                jira.commentBody.length() - "\n{noformat}".length());
        assertThat(inside).doesNotContainIgnoringCase("{noformat").doesNotContainPattern("(?i)\\{\\s*noformat");
        assertThat(inside).contains("!https://evil.test/p.png!");
    }

    private static JiraIssue issue(String key, String project) {
        return new JiraIssue(key, project, "Task", "Open", null, "2026-10-20T12:00:00.000+0000",
                "Summary of " + key, null);
    }

    private static final class FakeJira implements JiraResourcePort {

        final Map<String, JiraIssue> issues = Map.of("ABC-1", ABC_1);
        /** Keys Jira still resolves, to the key it reports now. */
        final Map<String, String> canonical = Map.of("ABC-1", "ABC-1", "ABC-3", "DEF-5", "DEF-5", "DEF-5");
        final List<IssueSearch> searches = new ArrayList<>();
        final List<String> transitioned = new ArrayList<>();
        final List<String> commented = new ArrayList<>();
        String commentBody;
        List<IssueTransition> available = List.of(new IssueTransition("21", "In Progress", "In Progress"));
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
        public Optional<String> canonicalIssueKey(String issueKey) {
            return Optional.ofNullable(canonical.get(issueKey));
        }

        @Override
        public IssueSearchResult search(IssueSearch search, int maxResults) {
            searches.add(search);
            return searchAnswer;
        }

        @Override
        public Optional<JiraComment> addComment(String issueKey, String body) {
            if (!canonical.containsValue(issueKey)) {
                return Optional.empty();
            }
            commented.add(issueKey);
            commentBody = body;
            return Optional.of(new JiraComment(issueKey, "100", "2026-10-27T10:00:00.000+0000"));
        }

        @Override
        public Optional<List<IssueTransition>> transitions(String issueKey) {
            return canonical.containsValue(issueKey) ? Optional.of(available) : Optional.empty();
        }

        @Override
        public boolean transition(String issueKey, String transitionId) {
            transitioned.add(issueKey + ":" + transitionId);
            return true;
        }
    }
}
