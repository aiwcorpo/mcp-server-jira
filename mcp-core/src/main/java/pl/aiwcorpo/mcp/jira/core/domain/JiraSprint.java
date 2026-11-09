package pl.aiwcorpo.mcp.jira.core.domain;

/** A sprint of a board; the dates are as Jira formats them and {@code null} while not set. */
public record JiraSprint(String id, String name, String state, String startDate, String endDate, String completeDate,
        String goal) {
}
