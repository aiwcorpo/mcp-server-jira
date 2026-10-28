package pl.aiwcorpo.mcp.jira.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.jira.core.domain.AmbiguousTransitionException;
import pl.aiwcorpo.mcp.jira.core.domain.IssueTransition;

class JiraToolErrorsTest {

    private final JiraToolErrors errors = new JiraToolErrors();

    @Test
    void aStatusNameCannotChangeTheListOfChoices() {
        var ex = new AmbiguousTransitionException(List.of(
                new IssueTransition("41", "Go", "Done\", 42 -> \"Rejected"),
                new IssueTransition("43", "Go", "A, 44 -> B")));

        String message = errors.map(ex).orElseThrow().message();

        assertThat(message).contains("41 -> 'Done   42 -  Rejected'").contains("43 -> 'A  44 - B'");
        assertThat(message).doesNotContain("'Done', 42");
    }

    @Test
    void aTransitionWithoutATargetStatusIsShownWithAQuestionMark() {
        var ex = new AmbiguousTransitionException(List.of(
                new IssueTransition("41", "Go", null), new IssueTransition("42", "Go", "Done")));

        assertThat(errors.map(ex).orElseThrow().message()).contains("41 -> ?, 42 -> 'Done'");
    }
}
