package pl.aiwcorpo.mcp.jira.tools;

import java.util.Optional;
import org.springframework.stereotype.Component;
import pl.aiwcorpo.mcp.jira.core.domain.NotFoundOrDeniedException;
import pl.aiwcorpo.mcp.jira.core.domain.RejectedByJiraException;
import pl.aiwcorpo.mcp.jira.core.domain.TransitionNotAvailableException;
import pl.aiwcorpo.mcp.platform.error.McpErrorMapper.SafeError;
import pl.aiwcorpo.mcp.platform.error.ToolErrorCode;
import pl.aiwcorpo.mcp.platform.error.ToolErrorMapping;

/**
 * Domain exceptions of this server as C10 errors with fixed texts; everything else falls through to the
 * starter's defaults. No text here carries a value from the arguments or from Jira's answer.
 */
@Component
public class JiraToolErrors implements ToolErrorMapping {

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
