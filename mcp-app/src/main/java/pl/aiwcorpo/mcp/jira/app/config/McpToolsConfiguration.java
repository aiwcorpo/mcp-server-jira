package pl.aiwcorpo.mcp.jira.app.config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.ai.tool.support.ToolUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;
import pl.aiwcorpo.mcp.jira.tools.JiraTool;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tool set of the server, as an explicit list: the MCP server exposes only tools registered through a
 * {@link ToolCallbackProvider} (an {@code @Tool} bean alone answers "Unknown tool"), and a reviewed list
 * keeps the public surface from growing by accident. {@link SchemaLimitedToolCallbacks} publishes the
 * {@code @ArgumentLimits} of the arguments in the input schema and closes the top-level object; the nested
 * {@code jql_filter} object it leaves as Spring AI generated it, so it is closed and bounded here, and the
 * starter then enforces it on every call.
 */
@Configuration
public class McpToolsConfiguration {

    static final Set<String> TOOLS = Set.of("search_issues", "get_issue", "add_comment", "transition_issue");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Bean
    ToolCallbackProvider mcpTools(JiraTool jiraTool) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (ToolCallback callback : SchemaLimitedToolCallbacks.of(jiraTool).getToolCallbacks()) {
            callbacks.add("search_issues".equals(callback.getToolDefinition().name())
                    ? withBoundedFilter(callback, jiraTool) : callback);
        }
        if (callbacks.size() != TOOLS.size()
                || !callbacks.stream().map(c -> c.getToolDefinition().name()).allMatch(TOOLS::contains)) {
            throw new IllegalStateException("Expected tools " + TOOLS + ", found " + callbacks.stream()
                    .map(c -> c.getToolDefinition().name()).toList());
        }
        return ToolCallbackProvider.from(callbacks);
    }

    private static ToolCallback withBoundedFilter(ToolCallback callback, JiraTool jiraTool) {
        Method method = ReflectionUtils.findMethod(JiraTool.class, "searchIssues", String.class,
                JiraTool.JqlFilter.class, String.class);
        if (method == null) {
            throw new IllegalStateException("JiraTool.searchIssues not found");
        }
        return MethodToolCallback.builder()
                .toolDefinition(ToolDefinitions.builder(method)
                        .inputSchema(boundedFilter(callback.getToolDefinition().inputSchema()))
                        .build())
                .toolMetadata(ToolMetadata.from(method))
                .toolMethod(method)
                .toolObject(jiraTool)
                .toolCallResultConverter(ToolUtils.getToolCallResultConverter(method))
                .build();
    }

    static String boundedFilter(String inputSchema) {
        ObjectNode schema = (ObjectNode) JSON.readTree(inputSchema);
        JsonNode filter = schema.path("properties").path("jql_filter");
        if (!(filter instanceof ObjectNode filterNode) || !(filter.path("properties") instanceof ObjectNode fields)) {
            throw new IllegalStateException("search_issues: jql_filter missing from the generated schema");
        }
        filterNode.put("additionalProperties", false);
        // Spring AI lists every record component as required; all three filters are optional.
        filterNode.remove("required");
        limit(fields, "status", JiraTool.MAX_NAME);
        limit(fields, "type", JiraTool.MAX_NAME);
        limit(fields, "assignee", JiraTool.MAX_USERNAME);
        return JSON.writeValueAsString(schema);
    }

    private static void limit(ObjectNode fields, String name, int maxLength) {
        if (!(fields.path(name) instanceof ObjectNode field) || !"string".equals(field.path("type").asString(null))) {
            throw new IllegalStateException("search_issues: jql_filter." + name + " is not a string property");
        }
        field.put("minLength", 1);
        field.put("maxLength", maxLength);
    }
}
