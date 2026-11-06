package pl.aiwcorpo.mcp.jira.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.jira.core.domain.JiraIssue;
import pl.aiwcorpo.mcp.jira.core.domain.JiraProject;

class JiraResolversTest {

    private final FakeJira jira = new FakeJira();
    private final JiraIssueResolver issues = new JiraIssueResolver(jira);
    private final JiraProjectResolver projects = new JiraProjectResolver(jira);

    @Test
    void anIssueCarriesTheCategoryOfItsProject() {
        JiraIssue hr = issues.fetch("HR-1").orElseThrow();

        assertThat(hr.projectCategory()).isEqualTo("Restricted");
        assertThat(issues.fetch("ABC-1").orElseThrow().projectCategory()).isEmpty();
    }

    @Test
    void theDescriptionUsesTheKeyAndTheProjectJiraReports() {
        ResolvedResource moved = issues.describe(issues.fetch("ABC-3").orElseThrow());

        assertThat(moved.id()).isEqualTo("HR-2");
        assertThat(moved.parent().orElseThrow().id()).isEqualTo("HR");
        assertThat(moved.parent().orElseThrow().attributes()).containsEntry("category", "Restricted");
    }

    @Test
    void anIssueOrAProjectJiraDoesNotShowIsEmpty() {
        assertThat(issues.fetch("ABC-404")).isEmpty();
        assertThat(issues.fetch("NOP-1")).as("the project is not visible").isEmpty();
        assertThat(projects.fetch("NOP")).isEmpty();
    }

    @Test
    void anIssueWithoutAProjectIsUnusableNotUnclassified() {
        assertThatThrownBy(() -> issues.fetch("ABC-9")).isInstanceOf(BackendResponseException.class);
    }

    @Test
    void aListLooksEachProjectUpOnceAndLeavesOutWhatItCannotUse() {
        Map<String, JiraIssue> found = issues.fetchAll(List.of("ABC-1", "ABC-2", "ABC-9", "ABC-404", "HR-1"));

        assertThat(found).containsOnlyKeys("ABC-1", "ABC-2", "HR-1");
        assertThat(jira.projectLookups).containsExactly("ABC", "HR");
    }

    @Test
    void aProjectIsDescribedWithItsCategory() {
        ResolvedResource project = projects.describe(projects.fetch("HR").orElseThrow());

        assertThat(project.id()).isEqualTo("HR");
        assertThat(project.attributes()).containsEntry("category", "Restricted");
    }

    @Test
    void anIssueAndItsProjectCarryTheScopeOfTheProjectJiraReports() {
        ResolvedResource moved = issues.describe(issues.fetch("ABC-3").orElseThrow());

        assertThat(moved.scopes()).containsExactly("Jira::Project/HR");
        assertThat(moved.parent().orElseThrow().scopes()).containsExactly("Jira::Project/HR");
        assertThat(projects.describe(projects.fetch("ABC").orElseThrow()).scopes()).containsExactly("Jira::Project/ABC");
    }

    @Test
    void theProbeOfAnIssueNamesTheProjectOfTheKeyAndAsksJiraNothing() {
        ResolvedResource probe = issues.probe("ABC_X-12").orElseThrow();

        assertThat(probe.id()).isEqualTo("ABC_X-12");
        assertThat(probe.scopes()).containsExactly("Jira::Project/ABC_X");
        assertThat(probe.parent().orElseThrow().id()).isEqualTo("ABC_X");
        assertThat(probe.parent().orElseThrow().attributes()).containsEntry("category", "");
        assertThat(projects.probe("HR").orElseThrow().scopes()).containsExactly("Jira::Project/HR");
        assertThat(jira.projectLookups).isEmpty();
    }

    private static final class FakeJira extends UnsupportedJira {

        final List<String> projectLookups = new ArrayList<>();

        @Override
        public Optional<JiraProject> findProject(String projectKey) {
            projectLookups.add(projectKey);
            return switch (projectKey) {
                case "ABC" -> Optional.of(new JiraProject("ABC", ""));
                case "HR" -> Optional.of(new JiraProject("HR", "Restricted"));
                default -> Optional.empty();
            };
        }

        @Override
        public Optional<JiraIssue> findIssue(String issueKey) {
            return switch (issueKey) {
                case "ABC-1", "ABC-2" -> Optional.of(issue(issueKey, "ABC"));
                case "HR-1" -> Optional.of(issue("HR-1", "HR"));
                case "ABC-3" -> Optional.of(issue("HR-2", "HR"));
                case "NOP-1" -> Optional.of(issue("NOP-1", "NOP"));
                case "ABC-9" -> Optional.of(issue("ABC-9", null));
                default -> Optional.empty();
            };
        }

        private static JiraIssue issue(String key, String project) {
            return new JiraIssue(key, project, "Task", "To Do", null, null, "s", null, null);
        }
    }
}
