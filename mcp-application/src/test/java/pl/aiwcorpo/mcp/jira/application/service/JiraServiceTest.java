package pl.aiwcorpo.mcp.jira.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    void searchKeepsOnlyIssuesOfTheRequestedProject() {
        jira.searchAnswer = new IssueSearchResult(List.of(ABC_1, issue("DEF-1", "DEF")));

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", null, "ORDER BY updated DESC"));

        assertThat(result.issues()).extracting(JiraIssue::key).containsExactly("ABC-1");
        assertThat(jira.searches).singleElement().satisfies(s -> assertThat(s.jql()).isEqualTo("ORDER BY updated DESC"));
    }

    @Test
    void searchKeepsOnlyIssuesOfTheRequestedTypeIgnoringCase() {
        jira.searchAnswer = new IssueSearchResult(List.of(ABC_1, issueOfType("ABC-2", "Bug")));

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", "BUG", ""));

        assertThat(result.issues()).extracting(JiraIssue::key).containsExactly("ABC-2");
    }

    @Test
    void searchAsksForMoreRowsThanTheResultHoldsBecauseTheTypeIsAppliedAfterwards() {
        List<JiraIssue> rows = java.util.stream.IntStream.rangeClosed(1, 40)
                .mapToObj(n -> issue("ABC-" + n, "ABC")).toList();
        jira.searchAnswer = new IssueSearchResult(rows);

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", null, ""));

        assertThat(result.issues()).as("the tool cuts the list after the policy has judged the hits").hasSize(40);
        assertThat(result.hasMore()).as("the number of matches is not a signal: some may be hidden by the policy").isFalse();
        assertThat(jira.searchSizes).containsExactly(JiraService.SEARCH_FETCH_SIZE);
    }

    @Test
    void aTypeFilterOnAFullPageOfRowsSaysTheResultMayBeIncomplete() {
        List<JiraIssue> rows = java.util.stream.IntStream.rangeClosed(1, JiraService.SEARCH_FETCH_SIZE)
                .mapToObj(n -> issue("ABC-" + n, "ABC")).toList();
        jira.searchAnswer = new IssueSearchResult(rows);

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", "Bug", ""));

        assertThat(result.issues()).isEmpty();
        assertThat(result.hasMore()).as("older Bugs may exist below the 100 rows Jira was asked for").isTrue();
    }

    @Test
    void aCompleteAnswerIsNotMarkedAsPossiblyIncomplete() {
        jira.searchAnswer = new IssueSearchResult(List.of(ABC_1, issueOfType("ABC-2", "Bug")));

        assertThat(service.searchIssues(new IssueSearch("ABC", "Bug", "")).hasMore()).isFalse();
        assertThat(service.searchIssues(new IssueSearch("ABC", null, "")).hasMore()).isFalse();
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
    void aWriteIsReportedUnderTheKeyItWasGiven() {
        assertThat(service.addComment("DEF-5", "hello").issueKey()).isEqualTo("DEF-5");
        assertThat(service.transitionIssue("DEF-5", "21").issueKey()).isEqualTo("DEF-5");
        assertThat(jira.commented).containsExactly("DEF-5");
        assertThat(jira.transitioned).containsExactly("DEF-5:21");
    }

    @Test
    void deletingAnIssueJiraDoesNotShowIsNotFoundOrDenied() {
        service.deleteIssue("ABC-1");

        assertThatThrownBy(() -> service.deleteIssue("ABC-2")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(jira.deleted).containsExactly("ABC-1", "ABC-2");
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

    private static JiraIssue issueOfType(String key, String type) {
        return new JiraIssue(key, "ABC", type, "Open", null, "2026-10-20T12:00:00.000+0000", "Summary of " + key, null, null);
    }

    private static JiraIssue issue(String key, String project) {
        return new JiraIssue(key, project, "Task", "Open", null, "2026-10-20T12:00:00.000+0000",
                "Summary of " + key, null, null);
    }

    private static final class FakeJira implements JiraResourcePort {

        final Map<String, JiraIssue> issues = Map.of("ABC-1", ABC_1);
        /** Keys Jira shows to the caller. */
        final Set<String> visible = Set.of("ABC-1", "DEF-5");
        final List<String> deleted = new ArrayList<>();
        final List<IssueSearch> searches = new ArrayList<>();
        final List<Integer> searchSizes = new ArrayList<>();
        final List<String> transitioned = new ArrayList<>();
        final List<String> commented = new ArrayList<>();
        String commentBody;
        List<IssueTransition> available = List.of(new IssueTransition("21", "In Progress", "In Progress"));
        IssueSearchResult searchAnswer = new IssueSearchResult(List.of());

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
            searchSizes.add(maxResults);
            return searchAnswer;
        }

        @Override
        public Optional<JiraComment> addComment(String issueKey, String body) {
            if (!visible.contains(issueKey)) {
                return Optional.empty();
            }
            commented.add(issueKey);
            commentBody = body;
            return Optional.of(new JiraComment(issueKey, "100", "2026-10-27T10:00:00.000+0000"));
        }

        @Override
        public Optional<List<IssueTransition>> transitions(String issueKey) {
            return visible.contains(issueKey) ? Optional.of(available) : Optional.empty();
        }

        @Override
        public boolean transition(String issueKey, String transitionId) {
            transitioned.add(issueKey + ":" + transitionId);
            return true;
        }

        @Override
        public boolean deleteIssue(String issueKey) {
            deleted.add(issueKey);
            return visible.contains(issueKey);
        }
    }
}
