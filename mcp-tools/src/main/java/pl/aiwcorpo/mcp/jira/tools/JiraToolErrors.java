package pl.aiwcorpo.mcp.jira.tools;

import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;

/**
 * Domain exceptions of this server as C10 errors with fixed texts; everything else falls through to the
 * starter's defaults. No text here carries a value from the arguments; the only text from Jira's answer is a status name, cut and cleaned.
 */
@Component
public class JiraToolErrors implements ToolErrorMapping {

    private static final Pattern NUMERIC_ID = Pattern.compile("[0-9]{1,10}");

    @Override
    public Optional<SafeError> map(Throwable ex) {
        if (ex instanceof NotFoundOrDeniedException) {
            return Optional.of(new SafeError(ToolErrorCode.NOT_FOUND_OR_DENIED.code(),
                    "The issue or project does not exist or you have no access to it."));
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
                    .map(t -> t.id() + " -> " + JiraToolViews.name(t.toStatus()))
                    .toList());
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(),
                    "Several transitions have that name; call again with the transition id (id -> target status), "
                            + "one of: " + choices + "."));
        }
        if (ex instanceof RejectedByJiraException rejected) {
            return Optional.of(new SafeError(ToolErrorCode.INVALID_ARGUMENT.code(), switch (rejected.operation()) {
                case SEARCH -> "Jira does not know one of the values of status, assignee or type.";
                case TRANSITION -> "Jira refused the transition; it may need fields this tool does not set.";
                case COMMENT -> "Jira refused the comment body.";
            }));
        }
        return Optional.empty();
    }
}
