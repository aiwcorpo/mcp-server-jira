package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * A sprint of a board; the dates are as Jira formats them and {@code null} while not set. {@code originBoardId} is the
 * board the sprint was created on, which can differ from the board it is listed on; {@code null} when Jira names none.
 */
public record JiraSprint(String id, String name, String state, String startDate, String endDate, String completeDate,
        String goal, String originBoardId) {
}
