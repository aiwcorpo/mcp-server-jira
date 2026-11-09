package pl.aiwcorpo.mcp.jira.core.domain;

import java.util.List;

/**
 * The fields of one issue type in one project, as the create screen offers them.
 *
 * @param truncated true when Jira holds more fields than were read
 */
public record CreateFields(List<Field> fields, boolean truncated) {

    /**
     * @param fieldId       {@code summary}, {@code priority}, {@code customfield_10010} ...
     * @param type          Jira's schema type ({@code string}, {@code array}, {@code option} ...), {@code null} when absent
     * @param itemsType     the schema type of the elements of an {@code array} field
     * @param allowedValues the values Jira lists for the field, empty for free-form fields
     */
    public record Field(String fieldId, String name, boolean required, String type, String itemsType,
            List<AllowedValue> allowedValues) {

        public Field {
            allowedValues = List.copyOf(allowedValues);
        }
    }

    public record AllowedValue(String id, String name) {
    }

    public CreateFields {
        fields = List.copyOf(fields);
    }
}
