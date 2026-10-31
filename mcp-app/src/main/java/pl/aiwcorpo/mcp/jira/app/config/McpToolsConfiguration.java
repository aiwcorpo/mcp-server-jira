package pl.aiwcorpo.mcp.jira.app.config;

import java.util.Set;
import java.util.TreeSet;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import pl.aiwcorpo.mcp.jira.tools.JiraTool;
import pl.aiwcorpo.mcp.platform.input.SchemaLimitedToolCallbacks;

/**
 * The tool set of the server, as an explicit list: the MCP server exposes only tools registered through a
 * {@link ToolCallbackProvider} (an {@code @Tool} bean alone answers "Unknown tool"), and a reviewed list
 * keeps the public surface from growing by accident. {@link SchemaLimitedToolCallbacks} publishes the
 * {@code @ArgumentLimits} of the arguments, including those on the components of {@code JqlFilter}, closes
 * every object and the starter enforces the schema on every call.
 */
@Configuration
public class McpToolsConfiguration {

    static final Set<String> TOOLS = Set.of("search_issues", "get_issue", "add_comment", "transition_issue", "delete_issue");

    @Bean
    public ToolCallbackProvider mcpTools(JiraTool jiraTool) {
        ToolCallbackProvider provider = SchemaLimitedToolCallbacks.of(jiraTool);
        Set<String> names = new TreeSet<>();
        for (var callback : provider.getToolCallbacks()) {
            names.add(callback.getToolDefinition().name());
        }
        if (!names.equals(TOOLS)) {
            throw new IllegalStateException("Expected tools " + TOOLS + ", found " + names);
        }
        return provider;
    }
}
