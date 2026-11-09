package pl.aiwcorpo.mcp.jira.core.domain;

/**
 * The text of a text attachment.
 *
 * @param omittedReason {@code null} when {@code text} is the content; otherwise a fixed snake case code
 *                      ({@code binary_attachment}, {@code attachment_too_large}) saying why there is no text
 */
public record AttachmentText(JiraAttachment attachment, String text, boolean truncated, String omittedReason) {

    public AttachmentText {
        text = text == null ? "" : text;
    }
}
