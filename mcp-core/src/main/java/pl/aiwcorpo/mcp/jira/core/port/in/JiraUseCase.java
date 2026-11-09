package pl.aiwcorpo.mcp.jira.core.port.in;

import java.util.List;
import pl.aiwcorpo.mcp.jira.core.domain.AttachmentText;
import pl.aiwcorpo.mcp.jira.core.domain.BoardPage;
import pl.aiwcorpo.mcp.jira.core.domain.ChangelogPage;
import pl.aiwcorpo.mcp.jira.core.domain.CommentPage;
import pl.aiwcorpo.mcp.jira.core.domain.CreateFields;
import pl.aiwcorpo.mcp.jira.core.domain.CurrentUser;
import pl.aiwcorpo.mcp.jira.core.domain.IssueChanges;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearchResult;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypePage;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTypeStatuses;
import pl.aiwcorpo.mcp.jira.core.domain.JiraAttachment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraComment;
import pl.aiwcorpo.mcp.jira.core.domain.JiraUser;
import pl.aiwcorpo.mcp.jira.core.domain.JiraWorklog;
import pl.aiwcorpo.mcp.jira.core.domain.LinkType;
import pl.aiwcorpo.mcp.jira.core.domain.NewIssue;
import pl.aiwcorpo.mcp.jira.core.domain.ProjectList;
import pl.aiwcorpo.mcp.jira.core.domain.SprintPage;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionOutcome;
import pl.aiwcorpo.mcp.jira.core.domain.WorklogPage;

/**
 * Inbound port: what the server can do, in business terms. Implemented in mcp-application, called by
 * mcp-tools with arguments already validated and the issue already judged by the policy. Issues and projects the
 * caller's Jira account does not see raise {@link pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException}.
 * Methods that take an {@code issueKey} expect the key Jira reports for the issue (the resolver of the policy read
 * it), which differs from the requested key when the issue was moved.
 */
public interface JiraUseCase {

    IssueSearchResult searchIssues(IssueSearch search);

    ProjectList listProjects();

    List<JiraUser> assignableUsers(String projectKey, String query);

    CommentPage listComments(String issueKey, int startAt);

    /** @param body plain text; the use case makes sure Jira does not interpret it as wiki markup */
    JiraComment addComment(String issueKey, String body);

    /** Replaces the text of a comment; same body rules as {@link #addComment}. */
    JiraComment editComment(String issueKey, String commentId, String body);

    List<IssueTransition> listTransitions(String issueKey);

    /**
     * @param transition transition id (digits, wins over a name) or name, case-insensitive
     * @throws pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException when the issue offers no such
     *         transition now
     * @throws pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException when a name fits several transitions
     */
    TransitionOutcome transitionIssue(String issueKey, String transition);

    /** Deletes the issue for good. Jira refuses an issue with subtasks (400), which this method does not work around. */
    void deleteIssue(String issueKey);

    /** @return the key of the new issue; {@code description} is stored as literal text */
    String createIssue(NewIssue issue);

    /** Sets the given fields; {@code description} is stored as literal text. */
    void updateIssue(String issueKey, IssueChanges changes);

    /** @param username {@code null} unassigns */
    void assign(String issueKey, String username);

    void linkIssues(String linkType, String inwardIssueKey, String outwardIssueKey);

    /** @param comment plain text or {@code null}; stored as literal text */
    JiraWorklog addWorklog(String issueKey, String timeSpent, String comment);

    WorklogPage listWorklogs(String issueKey, int startAt);

    List<JiraAttachment> listAttachments(String issueKey);

    ChangelogPage changelog(String issueKey, int startAt);

    List<IssueTypeStatuses> projectStatuses(String projectKey);

    IssueTypePage issueTypes(String projectKey, int startAt);

    /** @param issueTypeId id from {@link #issueTypes}; the fields are those of the create screen, capped */
    CreateFields createFields(String projectKey, String issueTypeId);

    List<LinkType> linkTypes();

    CurrentUser currentUser();

    /** Text attachments up to a size limit only; for other files the result says why there is no text. */
    AttachmentText readAttachment(String issueKey, String attachmentId);

    /** Boards relevant to the project, as Jira lists them; the caller of this method judges the projects of each board. */
    BoardPage listBoards(String projectKey, String type, int startAt);

    /**
     * The projects a board is built on. A board built on more projects than the cap is refused like an invisible one:
     * the caller cannot judge them all.
     */
    List<String> boardProjectKeys(String boardId);

    SprintPage listSprints(String boardId, String state, int startAt);

    /** The board a sprint belongs to, which stands for the projects of the sprint. */
    String sprintBoardId(String sprintId);

    IssueSearchResult sprintIssues(String sprintId, int startAt);

    /** @param username {@code null} watches as the calling user; @return the username that was added */
    String addWatcher(String issueKey, String username);

    /** Stops the calling user watching the issue; @return that user's username */
    String removeWatcher(String issueKey);

    /**
     * Removes one link, only when it joins exactly the two issues the caller named (both judged by the policy).
     * A link of other issues is reported like a missing one.
     */
    void removeIssueLink(String linkId, String inwardIssueKey, String outwardIssueKey);
}
