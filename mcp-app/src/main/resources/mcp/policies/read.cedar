// Reading follows the user's own rights in Jira (the server calls Jira with the user's credential). The group check
// is here; restricted projects are closed by the forbid the platform generates from the classification of the type.

@id("jira-read")
permit (
  principal in UserGroup::"mcp-jira-read",
  action == Jira::Action::"read",
  resource
)
when { resource is Jira::Project || resource is Jira::Issue };
