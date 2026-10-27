package pl.aiwcorpo.mcp.jira.integration;

import java.util.ArrayList;
import java.util.List;
import pl.aiwcorpo.mcp.jira.core.domain.IssueSearch;

/**
 * The only place where JQL is written. The model never supplies JQL: each search field becomes one clause
 * with a fixed field name and the value as a quoted, escaped JQL string, joined with AND, so no value can
 * add an OR, a function or another project. The tools layer has already checked the values against their
 * patterns; escaping here does not rely on that.
 *
 * <p>The constraints of {@code QueryScope} (starter beta, M3) are appended here, as further AND clauses
 * around the whole query.
 */
final class JqlBuilder {

    private JqlBuilder() {
    }

    static String build(IssueSearch search) {
        List<String> clauses = new ArrayList<>();
        clauses.add("project = " + quote(search.projectKey()));
        if (search.status() != null) {
            clauses.add("status = " + quote(search.status()));
        }
        if (search.assignee() != null) {
            clauses.add("assignee = " + quote(search.assignee()));
        }
        if (search.type() != null) {
            clauses.add("issuetype = " + quote(search.type()));
        }
        if (search.text() != null) {
            clauses.add("text ~ " + quote(search.text()));
        }
        return String.join(" AND ", clauses) + " ORDER BY updated DESC";
    }

    /** A JQL string literal: backslash and double quote escaped, control characters refused. */
    static String quote(String value) {
        StringBuilder quoted = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalStateException("control character in a JQL value");
            }
            if (c == '"' || c == '\\') {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.append('"').toString();
    }
}
