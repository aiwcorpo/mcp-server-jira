package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/** One page of sprints; {@code nextStartAt} as in {@link CommentPage}. */
public record SprintPage(List<JiraSprint> sprints, Integer nextStartAt) {

    public SprintPage {
        sprints = List.copyOf(sprints);
    }
}
