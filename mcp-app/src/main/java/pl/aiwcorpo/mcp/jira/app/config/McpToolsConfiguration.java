package pl.aiwcorpo.mcp.jira.app.config;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallback;
import org.springframework.ai.tool.support.ToolDefinitions;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ReflectionUtils;
import pl.aiwcorpo.mcp.jira.tools.JiraTool;

/**
 * The tool set of the server, as an explicit list: the MCP server exposes only tools registered through a
 * {@link ToolCallbackProvider} (an {@code @Tool} bean alone answers "Unknown tool"), and a reviewed list
 * keeps the public surface from growing by accident.
 *
 * <p>Each tool gets its hand-written input schema from {@link JiraTool#inputSchemas()}; a tool without one
 * stops the start, so a new tool cannot slip out with the open schema Spring AI would derive.
 */
@Configuration
public class McpToolsConfiguration {

    static final List<String> TOOLS = List.of("search_issues", "get_issue", "add_comment", "transition_issue");

    @Bean
    ToolCallbackProvider mcpTools(JiraTool jiraTool) {
        return ToolCallbackProvider.from(callbacks(jiraTool, JiraTool.inputSchemas()));
    }

    static List<ToolCallback> callbacks(Object toolObject, Map<String, String> schemas) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Method method : ReflectionUtils.getUniqueDeclaredMethods(AopUtils.getTargetClass(toolObject))) {
            Tool tool = method.getAnnotation(Tool.class);
            if (tool == null) {
                continue;
            }
            String schema = schemas.get(tool.name());
            if (schema == null || !TOOLS.contains(tool.name())) {
                throw new IllegalStateException("Tool '" + tool.name() + "' needs an input schema and an entry "
                        + "in McpToolsConfiguration.TOOLS");
            }
            callbacks.add(MethodToolCallback.builder()
                    .toolDefinition(ToolDefinitions.builder(method).inputSchema(schema).build())
                    .toolMetadata(ToolMetadata.from(method))
                    .toolMethod(method)
                    .toolObject(toolObject)
                    .build());
        }
        if (callbacks.size() != TOOLS.size()) {
            throw new IllegalStateException("Expected tools " + TOOLS + ", found " + callbacks.size());
        }
        return callbacks;
    }
}
