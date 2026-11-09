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
import pl.aiwcorpo.mcp.jira.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.jira.core.domain.BoardPage;
import pl.aiwcorpo.mcp.jira.core.domain.ChangelogPage;
import pl.aiwcorpo.mcp.jira.core.domain.CommentPage;
import pl.aiwcorpo.mcp.jira.core.domain.CreateFields;
import pl.aiwcorpo.mcp.jira.core.domain.CurrentUser;
import pl.aiwcorpo.mcp.jira.core.domain.IssueChanges;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueLinkEnds;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypePage;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypeStatuses;
import pl.aiwcorpo.mcp.jira.core.domain.JiraAttachment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;
import pl.aiwcorpo.mcp.jira.core.domain.JiraUser;
import pl.aiwcorpo.mcp.jira.core.domain.JiraWorklog;
import pl.aiwcorpo.mcp.jira.core.domain.LinkType;
import pl.aiwcorpo.mcp.jira.core.domain.NewIssue;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectList;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectSummary;
import pl.aiwcorpo.mcp.jira.core.domain.ServerInfo;
import pl.aiwcorpo.mcp.jira.core.domain.SprintPage;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;
import pl.aiwcorpo.mcp.jira.core.domain.WorklogPage;
import pl.aiwcorpo.mcp.jira.core.port.out.JiraResourcePort;

class JiraServiceTest {

    private static final JiraIssue ABC_1 = issue("ABC-1", "ABC");

    private final FakeJira jira = new FakeJira();
    private final JiraService service = new JiraService(jira);

    @Test
    void searchKeepsOnlyIssuesOfTheRequestedProject() {
        jira.searchAnswer = new IssueSearchResult(List.of(ABC_1, issue("DEF-1", "DEF")), null);

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", null, "ORDER BY updated DESC", 0));

        assertThat(result.issues()).extracting(JiraIssue::key).containsExactly("ABC-1");
        assertThat(jira.searches).singleElement().satisfies(s -> assertThat(s.jql()).isEqualTo("ORDER BY updated DESC"));
    }

    @Test
    void searchKeepsOnlyIssuesOfTheRequestedTypeIgnoringCase() {
        jira.searchAnswer = new IssueSearchResult(List.of(ABC_1, issueOfType("ABC-2", "Bug")), null);

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", "BUG", "", 0));

        assertThat(result.issues()).extracting(JiraIssue::key).containsExactly("ABC-2");
    }

    @Test
    void searchAsksForOnePageAtTheGivenOffsetAndPassesTheNextOffsetOn() {
        jira.searchAnswer = new IssueSearchResult(List.of(ABC_1), 75);

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", null, "", 50));

        assertThat(jira.searchSizes).containsExactly(JiraService.PAGE_SIZE);
        assertThat(jira.searches.get(0).startAt()).isEqualTo(50);
        assertThat(result.nextStartAt()).isEqualTo(75);
    }

    @Test
    void aPageCutByTheTypeFilterKeepsTheNextOffsetOfJiraNotOfTheRowsLeft() {
        jira.searchAnswer = new IssueSearchResult(List.of(ABC_1, ABC_1), 25);

        IssueSearchResult result = service.searchIssues(new IssueSearch("ABC", "Bug", "", 0));

        assertThat(result.issues()).isEmpty();
        assertThat(result.nextStartAt()).as("older Bugs may be on the next page").isEqualTo(25);
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

    @Test
    void createdIssueDescriptionAndUpdatedDescriptionGoToJiraAsLiteralText() {
        String key = service.createIssue(new NewIssue("ABC", "Task", "Title", "see !https://evil.test/p.png!", List.of("a"), null, null));
        service.updateIssue("ABC-1", new IssueChanges(null, "[~admin]", null, null));

        assertThat(key).isEqualTo("ABC-901");
        assertThat(jira.created.get(0).description()).isEqualTo("{noformat}\nsee !https://evil.test/p.png!\n{noformat}");
        assertThat(jira.created.get(0).summary()).as("the summary is a single-line field, not markup").isEqualTo("Title");
        assertThat(jira.changes.get(0).description()).isEqualTo("{noformat}\n[~admin]\n{noformat}");
    }

    @Test
    void anAbsentDescriptionStaysAbsent() {
        service.createIssue(new NewIssue("ABC", "Task", "Title", null, null, null, null));
        service.updateIssue("ABC-1", new IssueChanges("New", null, List.of(), null));

        assertThat(jira.created.get(0).description()).isNull();
        assertThat(jira.changes.get(0).description()).isNull();
        assertThat(jira.changes.get(0).labels()).isEmpty();
    }

    @Test
    void writesToIssuesJiraDoesNotShowAreNotFoundOrDenied() {
        assertThatThrownBy(() -> service.createIssue(new NewIssue("HR", "Task", "x", null, null, null, null)))
                .isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.updateIssue("ABC-2", new IssueChanges("x", null, null, null)))
                .isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.assign("ABC-2", "alice")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.linkIssues("Blocks", "ABC-1", "ABC-2")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.addWorklog("ABC-2", "1h", null)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.editComment("ABC-2", "1", "x")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.listComments("ABC-2", 0)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.listWorklogs("ABC-2", 0)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.listAttachments("ABC-2")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.listTransitions("ABC-2")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.assignableUsers("HR", "al")).isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void anEditedCommentAndAWorklogCommentAreLiteralToo() {
        service.editComment("ABC-1", "7", "!x!");
        service.addWorklog("ABC-1", "1h", "[~admin]");

        assertThat(jira.commentBody).isEqualTo("{noformat}\n!x!\n{noformat}");
        assertThat(jira.worklogComment).isEqualTo("{noformat}\n[~admin]\n{noformat}");
        service.addWorklog("ABC-1", "2h", null);
        assertThat(jira.worklogComment).isNull();
    }

    @Test
    void theProjectListIsReadUpToTheLimitOfTheServer() {
        assertThat(service.listProjects().truncated()).isFalse();
        assertThat(jira.projectLimits).containsExactly(JiraService.MAX_PROJECTS);
    }

    @Test
    void listsAskForOnePageAtTheOffset() {
        service.listComments("ABC-1", 25);
        service.listWorklogs("ABC-1", 50);

        assertThat(jira.pageRequests).containsExactly("comments:25:25", "worklogs:50:25");
    }

    @Test
    void theNewReadsAskForOnePageAtTheOffsetAndForTheCappedNumberOfFields() {
        service.changelog("ABC-1", 50);
        service.issueTypes("ABC", 25);
        service.createFields("ABC", "10002");

        assertThat(jira.pageRequests).containsExactly("changelog:50:25", "issuetypes:25:25", "createfields:10002:100");
    }

    @Test
    void theNewReadsOfAnInvisibleIssueOrProjectAreNotFoundOrDenied() {
        assertThatThrownBy(() -> service.changelog("ABC-2", 0)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.readAttachment("ABC-2", "1")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.projectStatuses("HR")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.issueTypes("HR", 0)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.createFields("ABC", "99")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.listBoards("HR", null, 0)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.listSprints("8", null, 0)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.sprintBoardId("71")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.sprintIssues("71", 0)).isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void anAttachmentIsReadWithTheTextLimitOfTheServer() {
        service.readAttachment("ABC-1", "5");

        assertThat(jira.pageRequests).containsExactly("attachment:5:" + JiraService.ATTACHMENT_TEXT_MAX);
    }

    @Test
    void aBoardOnMoreProjectsThanCanBeJudgedIsRefusedLikeAMissingOne() {
        assertThat(service.boardProjectKeys("7")).containsExactly("ABC");

        jira.boardProjects = java.util.stream.IntStream.rangeClosed(1, JiraService.MAX_BOARD_PROJECTS + 1)
                .mapToObj(i -> "P" + i).toList();

        assertThatThrownBy(() -> service.boardProjectKeys("7")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(jira.projectLimits).as("one key more than the cap is read, to see the overflow")
                .containsExactly(JiraService.MAX_BOARD_PROJECTS + 1, JiraService.MAX_BOARD_PROJECTS + 1);
    }

    @Test
    void aWatcherWithoutUsernameIsTheCallingUser() {
        assertThat(service.addWatcher("ABC-1", null)).isEqualTo("jkowalski");
        assertThat(service.addWatcher("ABC-1", "alice")).isEqualTo("alice");
        assertThat(service.removeWatcher("ABC-1")).isEqualTo("jkowalski");

        assertThat(jira.watcherChanges).containsExactly("add:ABC-1:jkowalski", "add:ABC-1:alice", "remove:ABC-1:jkowalski");
        assertThatThrownBy(() -> service.addWatcher("ABC-2", null)).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.removeWatcher("ABC-2")).isInstanceOf(NotFoundOrDeniedException.class);
    }

    @Test
    void aLinkIsRemovedOnlyWhenItJoinsExactlyTheNamedIssues() {
        service.removeIssueLink("90", "ABC-1", "ABC-2");
        service.removeIssueLink("90", "ABC-2", "ABC-1");

        assertThat(jira.deletedLinks).containsExactly("90", "90");
        assertThatThrownBy(() -> service.removeIssueLink("90", "ABC-1", "DEF-5")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.removeIssueLink("90", "ABC-1", "ABC-1")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThatThrownBy(() -> service.removeIssueLink("91", "ABC-1", "ABC-2")).isInstanceOf(NotFoundOrDeniedException.class);
        assertThat(jira.deletedLinks).as("nothing else was deleted").hasSize(2);
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
        String worklogComment;
        final List<NewIssue> created = new ArrayList<>();
        final List<IssueChanges> changes = new ArrayList<>();
        final List<String> pageRequests = new ArrayList<>();
        final List<Integer> projectLimits = new ArrayList<>();
        List<IssueTransition> available = List.of(new IssueTransition("21", "In Progress", "In Progress"));
        IssueSearchResult searchAnswer = new IssueSearchResult(List.of(), null);

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

        @Override
        public ServerInfo serverInfo() {
            return new ServerInfo("11.3.0", "Server");
        }

        @Override
        public ProjectList listProjects(int limit) {
            projectLimits.add(limit);
            return new ProjectList(List.of(new ProjectSummary("ABC", "Alpha", "")), false);
        }

        @Override
        public Optional<List<JiraUser>> assignableUsers(String projectKey, String query, int maxResults) {
            return "ABC".equals(projectKey) ? Optional.of(List.of(new JiraUser("alice", "Alice", true))) : Optional.empty();
        }

        @Override
        public Optional<CommentPage> listComments(String issueKey, int startAt, int maxResults) {
            pageRequests.add("comments:" + startAt + ":" + maxResults);
            return visible.contains(issueKey) ? Optional.of(new CommentPage(List.of(), null)) : Optional.empty();
        }

        @Override
        public Optional<JiraComment> editComment(String issueKey, String commentId, String body) {
            if (!visible.contains(issueKey)) {
                return Optional.empty();
            }
            commentBody = body;
            return Optional.of(new JiraComment(issueKey, commentId, "2026-10-27T10:00:00.000+0000"));
        }

        @Override
        public Optional<String> createIssue(NewIssue issue) {
            if (!"ABC".equals(issue.projectKey())) {
                return Optional.empty();
            }
            created.add(issue);
            return Optional.of("ABC-901");
        }

        @Override
        public boolean updateIssue(String issueKey, IssueChanges issueChanges) {
            changes.add(issueChanges);
            return visible.contains(issueKey);
        }

        @Override
        public boolean assign(String issueKey, String username) {
            return visible.contains(issueKey);
        }

        @Override
        public boolean linkIssues(String linkType, String inwardIssueKey, String outwardIssueKey) {
            return visible.contains(inwardIssueKey) && visible.contains(outwardIssueKey);
        }

        @Override
        public Optional<JiraWorklog> addWorklog(String issueKey, String timeSpent, String comment) {
            if (!visible.contains(issueKey)) {
                return Optional.empty();
            }
            worklogComment = comment;
            return Optional.of(new JiraWorklog("1", "alice", "2026-10-27T10:00:00.000+0000", timeSpent, comment));
        }

        @Override
        public Optional<WorklogPage> listWorklogs(String issueKey, int startAt, int maxResults) {
            pageRequests.add("worklogs:" + startAt + ":" + maxResults);
            return visible.contains(issueKey) ? Optional.of(new WorklogPage(List.of(), null)) : Optional.empty();
        }

        @Override
        public Optional<List<JiraAttachment>> listAttachments(String issueKey) {
            return visible.contains(issueKey) ? Optional.of(List.of()) : Optional.empty();
        }

        final List<String> watcherChanges = new ArrayList<>();
        final List<String> deletedLinks = new ArrayList<>();
        List<String> boardProjects = List.of("ABC");
        final IssueLinkEnds link = new IssueLinkEnds("90", "ABC-2", "ABC-1");

        @Override
        public Optional<ChangelogPage> changelog(String issueKey, int startAt, int maxResults) {
            pageRequests.add("changelog:" + startAt + ":" + maxResults);
            return visible.contains(issueKey) ? Optional.of(new ChangelogPage(List.of(), null, false)) : Optional.empty();
        }

        @Override
        public Optional<List<IssueTypeStatuses>> projectStatuses(String projectKey) {
            return "ABC".equals(projectKey) ? Optional.of(List.of()) : Optional.empty();
        }

        @Override
        public Optional<IssueTypePage> issueTypes(String projectKey, int startAt, int maxResults) {
            pageRequests.add("issuetypes:" + startAt + ":" + maxResults);
            return "ABC".equals(projectKey) ? Optional.of(new IssueTypePage(List.of(), null)) : Optional.empty();
        }

        @Override
        public Optional<CreateFields> createFields(String projectKey, String issueTypeId, int maxFields) {
            pageRequests.add("createfields:" + issueTypeId + ":" + maxFields);
            return "ABC".equals(projectKey) && "10002".equals(issueTypeId)
                    ? Optional.of(new CreateFields(List.of(), false)) : Optional.empty();
        }

        @Override
        public List<LinkType> linkTypes() {
            return List.of(new LinkType("1", "Blocks", "is blocked by", "blocks"));
        }

        @Override
        public CurrentUser currentUser() {
            return new CurrentUser("jkowalski", "JIRAUSER10000", "Jan Kowalski", true);
        }

        @Override
        public Optional<AttachmentText> readAttachment(String issueKey, String attachmentId, int maxChars) {
            pageRequests.add("attachment:" + attachmentId + ":" + maxChars);
            return visible.contains(issueKey) ? Optional.of(new AttachmentText(
                    new JiraAttachment(attachmentId, "a.txt", 1, "text/plain", null, null), "x", false, null)) : Optional.empty();
        }

        @Override
        public Optional<BoardPage> boards(String projectKey, String type, int startAt, int maxResults) {
            return "ABC".equals(projectKey) ? Optional.of(new BoardPage(List.of(), null)) : Optional.empty();
        }

        @Override
        public Optional<List<String>> boardProjectKeys(String boardId, int limit) {
            projectLimits.add(limit);
            return "7".equals(boardId) ? Optional.of(boardProjects) : Optional.empty();
        }

        @Override
        public Optional<SprintPage> sprints(String boardId, String state, int startAt, int maxResults) {
            return "7".equals(boardId) ? Optional.of(new SprintPage(List.of(), null)) : Optional.empty();
        }

        @Override
        public Optional<String> sprintBoardId(String sprintId) {
            return "70".equals(sprintId) ? Optional.of("7") : Optional.empty();
        }

        @Override
        public Optional<IssueSearchResult> sprintIssues(String sprintId, int startAt, int maxResults) {
            return "70".equals(sprintId) ? Optional.of(new IssueSearchResult(List.of(), null)) : Optional.empty();
        }

        @Override
        public boolean addWatcher(String issueKey, String username) {
            watcherChanges.add("add:" + issueKey + ":" + username);
            return visible.contains(issueKey);
        }

        @Override
        public boolean removeWatcher(String issueKey, String username) {
            watcherChanges.add("remove:" + issueKey + ":" + username);
            return visible.contains(issueKey);
        }

        @Override
        public Optional<IssueLinkEnds> issueLink(String linkId) {
            return "90".equals(linkId) ? Optional.of(link) : Optional.empty();
        }

        @Override
        public boolean deleteIssueLink(String linkId) {
            deletedLinks.add(linkId);
            return true;
        }
    }
}
