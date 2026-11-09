package pl.aiwcorpo.mcp.jira.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import pl.aiwcorpo.mcp.cedar.entities.ResolvedResource;
import pl.aiwcorpo.mcp.platform.test.PolicyDecisions;
import pl.aiwcorpo.mcp.platform.test.PolicyDecisions.Decision;

/**
 * The decisions of the policies in {@code mcp/policies/} on the resources of this server, without Spring and without
 * the gateway: who may read, change and delete what, given the scope mapping an administrator would set. The rules for
 * restricted projects come from the platform, generated from the classification declared for {@code Jira::Project}.
 */
class JiraPolicyDecisionsTest {

    private static final String READ = "mcp-jira-read";
    private static final String WRITE = "mcp-jira-write";
    private static final String DESTRUCTIVE = "mcp-jira-destructive";
    private static final String ABC = "mcp-jira-proj-ABC";
    private static final String HR = "mcp-jira-proj-HR";

    private final PolicyDecisions policies = PolicyDecisions.fromResources().scopeMapping(Map.of(
            ABC, List.of("Jira::Project/ABC"),
            "mcp-jira-proj-DEF", List.of("Jira::Project/DEF"),
            HR, List.of("Jira::Project/HR")));

    private static ResolvedResource project(String key, String category) {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("key", key);
        attributes.put("category", category);
        return ResolvedResource.of("Jira::Project", key, attributes).withScopes(List.of("Jira::Project/" + key));
    }

    private static ResolvedResource issue(String key, String projectKey, String category) {
        ResolvedResource owner = project(projectKey, category);
        return ResolvedResource.of("Jira::Issue", key, Map.of("key", key)).withParent(owner).withScopes(owner.scopes());
    }

    @Test
    void aReaderReadsAnIssueWithoutAProjectScope() {
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", issue("ABC-1", "ABC", "Software"));
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", issue("DEF-1", "DEF", "Software"));
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", project("DEF", ""));
    }

    @Test
    void aCallerWithoutTheReadGroupReadsNothing() {
        policies.assertDenied(PolicyDecisions.caller(), "read", issue("ABC-1", "ABC", "Software"));
        policies.assertDenied(PolicyDecisions.caller(WRITE, ABC), "read", issue("ABC-1", "ABC", "Software"));
    }

    @Test
    void anUpdateInTheScopeOfTheProjectIsAllowed() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, WRITE, ABC), "update", issue("ABC-2", "ABC", "Software"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("jira-update-issue-in-scope");
    }

    @Test
    void anUpdateOutsideTheScopeIsDenied() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, WRITE, ABC), "update", issue("DEF-1", "DEF", "Software"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.determiningPolicies()).isEmpty();
    }

    @Test
    void theScopeWithoutTheWriteGroupChangesNothing() {
        policies.assertDenied(PolicyDecisions.caller(READ, ABC), "update", issue("ABC-1", "ABC", "Software"));
    }

    @Test
    void anIssueMovedToAnotherProjectIsJudgedByTheProjectItIsInNow() {
        // The key says ABC, Jira says the issue is in DEF: the resolver describes the project from Jira's answer.
        policies.assertDenied(PolicyDecisions.caller(READ, WRITE, ABC), "update", issue("ABC-3", "DEF", "Software"));
        policies.assertAllowed(PolicyDecisions.caller(READ, WRITE, ABC), "update", issue("DEF-5", "ABC", "Software"));
    }

    @Test
    void aProjectUpdateInTheScopeIsAllowedAndOutsideItDenied() {
        Decision inScope = policies.decide(PolicyDecisions.caller(READ, WRITE, ABC), "update", project("ABC", "Software"));

        assertThat(inScope.allowed()).isTrue();
        assertThat(inScope.determiningPolicies()).containsExactly("jira-update-project-in-scope");
        policies.assertDenied(PolicyDecisions.caller(READ, WRITE, ABC), "update", project("DEF", "Software"));
    }

    @Test
    void anIssueIsDeletedInTheScopeWithTheDestructiveGroup() {
        Decision decision = policies.decide(PolicyDecisions.caller(READ, DESTRUCTIVE, ABC), "delete", issue("ABC-1", "ABC", "Software"));

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.determiningPolicies()).containsExactly("jira-delete-issue-in-scope");
    }

    @Test
    void aDeleteNeedsTheDestructiveGroupAndTheScope() {
        policies.assertDenied(PolicyDecisions.caller(READ, WRITE, ABC), "delete", issue("ABC-1", "ABC", "Software"));
        policies.assertDenied(PolicyDecisions.caller(READ, DESTRUCTIVE, ABC), "delete", issue("DEF-1", "DEF", "Software"));
        policies.assertDenied(PolicyDecisions.caller(READ, DESTRUCTIVE), "delete", issue("ABC-1", "ABC", "Software"));
    }

    @Test
    void removingALinkNeedsTheUpdateScopeOnBothIssues() {
        ResolvedResource inScope = issue("ABC-1", "ABC", "Software");
        ResolvedResource outOfScope = issue("DEF-1", "DEF", "Software");
        ResolvedResource restricted = issue("HR-1", "HR", "restricted");

        policies.assertAllowed(PolicyDecisions.caller(READ, WRITE, ABC), "update", inScope);
        policies.assertDenied(PolicyDecisions.caller(READ, WRITE, ABC), "update", outOfScope);
        policies.assertDenied(PolicyDecisions.caller(READ, WRITE, ABC, HR), "update", restricted);
    }

    @Test
    void watchingAndReadingTheNewMetadataFollowTheExistingGroups() {
        policies.assertAllowed(PolicyDecisions.caller(READ, WRITE, ABC), "update", issue("ABC-1", "ABC", "Software"));
        policies.assertDenied(PolicyDecisions.caller(READ, ABC), "update", issue("ABC-1", "ABC", "Software"));
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", project("ABC", ""));
        policies.assertDenied(PolicyDecisions.caller(), "read", project("ABC", ""));
    }

    @Test
    void aWholeProjectIsNeverDeleted() {
        policies.assertDenied(PolicyDecisions.caller(READ, WRITE, DESTRUCTIVE, ABC), "delete", project("ABC", "Software"));
    }

    @Test
    void anIssueOfARestrictedProjectIsDeniedByThePlatformRuleWhatEverTheGroups() {
        ResolvedResource restricted = issue("HR-1", "HR", "restricted");

        for (String action : List.of("read", "update", "delete")) {
            Decision decision = policies.decide(PolicyDecisions.caller(READ, WRITE, DESTRUCTIVE, HR), action, restricted);

            assertThat(decision.allowed()).as(action).isFalse();
            assertThat(decision.determiningPolicies()).as(action).isNotEmpty().allMatch(id -> id.startsWith("platform-"));
        }
    }

    @Test
    void theCategoryIsNormalizedBeforeThePlatformRuleLooksAtIt() {
        policies.assertDenied(PolicyDecisions.caller(READ), "read", issue("HR-1", "HR", "Restricted"));
        policies.assertDenied(PolicyDecisions.caller(READ), "read", issue("HR-1", "HR", " RESTRICTED "));
        policies.assertDenied(PolicyDecisions.caller(READ), "read", project("HR", "Restricted"));
    }

    @Test
    void aProjectWithAnotherCategoryOrNoneIsNotRestricted() {
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", issue("ABC-1", "ABC", ""));
        policies.assertAllowed(PolicyDecisions.caller(READ), "read", issue("ABC-1", "ABC", "Internal"));
    }

    @Test
    void aProjectWithoutTheRequiredCategoryIsRefusedBeforeAnyPolicyRuns() {
        ResolvedResource noCategory = ResolvedResource.of("Jira::Project", "HR", Map.of("key", "HR"))
                .withScopes(List.of("Jira::Project/HR"));

        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller(READ), "read", noCategory))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aKeyOutsideTheDeclaredPatternIsRefused() {
        assertThatThrownBy(() -> policies.decide(PolicyDecisions.caller(READ), "read", issue("abc-1", "ABC", "")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anOverrideOfTheAdministratorClosesDeletesEverywhere() {
        PolicyDecisions frozen = policies.override("override-no-delete",
                "forbid (principal, action == Jira::Action::\"delete\", resource);");

        frozen.assertDenied(PolicyDecisions.caller(READ, DESTRUCTIVE, ABC), "delete", issue("ABC-1", "ABC", "Software"));
        frozen.assertAllowed(PolicyDecisions.caller(READ, WRITE, ABC), "update", issue("ABC-1", "ABC", "Software"));
    }
}
