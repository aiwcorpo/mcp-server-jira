package pl.aiwcorpo.mcp.jira.tools;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException;
import pl.aiwcorpo.mcp.jira.core.domain.BackendDegradedException;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;

/**
 * Domain exceptions of this server as C10 errors with fixed texts (the text of not_found_or_denied is the constant of
 * the starter, so a refusal after the policy cannot be told from one before it); everything else falls through to the
 * starter's defaults. No text here carries a value from the arguments; the only text from Jira's answer is a status name, cut and cleaned.
 */
@Component
public class JiraToolErrors implements ToolErrorMapping {

    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,10}");

    @Override
    public Optional<SafeError> map(Throwable ex) {
        if (ex instanceof NotFoundOrDeniedException) {
            return Optional.of(new SafeError(ToolErrorCode.NOT_FOUND_OR_DENIED.code(),
                    ToolErrorCode.NOT_FOUND_OR_DENIED_MESSAGE));
        }
        if (ex instanceof BackendDegradedException) {
            return Optional.of(new SafeError(ToolErrorCode.UPSTREAM_UNAVAILABLE.code(),
                    "This Jira version is not supported for changes; reading still works. Nothing was changed."));
        }
        if (ex instanceof TransitionNotAvailableException) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "The issue has no such transition in its current status."));
        }
        if (ex instanceof AmbiguousTransitionException ambiguous) {
            // Ids are repeated only as plain numbers. The target status is the administrator's name, cut and
            // cleaned like every name in a result; without it the model would pick an id blind.
            String choices = String.join(", ", ambiguous.candidates().stream()
                    .filter(t -> NUMERIC_ID.matcher(t.id()).matches())
                    .map(t -> t.id() + " -> " + statusLabel(t.toStatus()))
                    .toList());
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "Several transitions have that name; call again with the transition id (id -> target status), "
                            + "one of: " + choices + "."));
        }
        if (ex instanceof RejectedByJiraException rejected) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(), switch (rejected.operation()) {
                case SEARCH -> "Jira does not know one of the values of status or assignee.";
                case TRANSITION -> "Jira refused the transition; it may need fields this tool does not set.";
                case COMMENT -> "Jira refused the comment body.";
                case DELETE -> "Jira refused the deletion; the issue may have subtasks.";
                case CREATE -> "Jira refused the issue; the project may require fields this tool does not set, "
                        + "or the type, priority, assignee or a label is unknown to it.";
                case UPDATE -> "Jira refused the change; a field may not be on the screen of the issue or the value is unknown to Jira.";
                case ASSIGN -> "Jira refused the assignment; the user may not be assignable in this project.";
                case LINK -> "Jira refused the link; the link type may not exist or linking may be switched off.";
                case WORKLOG -> "Jira refused the work log entry; check the time format and that work logging is on.";
            }));
        }
        return Optional.empty();
    }

    /** Quoted, without the characters that separate the entries of the list; an unknown target is "?". */
    private static String statusLabel(String toStatus) {
        String name = JiraToolViews.name(toStatus);
        if (name == null || name.isBlank()) {
            return "?";
        }
        return "'" + name.replace("'", " ").replace("\"", " ").replace(",", " ").replace("->", "-") + "'";
    }
}
