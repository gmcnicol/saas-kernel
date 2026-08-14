# Issue tracker: GitHub

Issues and specs for this repo live as GitHub issues. Use the `gh` CLI for all operations.

## Conventions

- **Create an issue**: `gh issue create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `gh issue view <number> --comments`, filtering comments with `jq` and fetching labels.
- **List issues**: `gh issue list --state open --json number,title,body,labels,comments --jq '[.[] | {number, title, body, labels: [.labels[].name], comments: [.comments[].body]}]'` with appropriate `--label` and `--state` filters.
- **Comment on an issue**: `gh issue comment <number> --body "..."`
- **Apply or remove labels**: `gh issue edit <number> --add-label "..."` or `--remove-label "..."`
- **Close**: `gh issue close <number> --comment "..."`

Infer the repo from `git remote -v`. `gh` does this automatically inside a clone.

## Pull requests as a triage surface

**PRs as a request surface: no.** _(Set to `yes` if this repo treats external PRs as feature requests. `/triage` reads this flag.)_

When set to `yes`, PRs use the same labels and states as issues:

- **Read a PR**: `gh pr view <number> --comments` and `gh pr diff <number>`
- **List external PRs for triage**: `gh pr list --state open --json number,title,body,labels,author,authorAssociation,comments`, then keep only `CONTRIBUTOR`, `FIRST_TIME_CONTRIBUTOR`, or `NONE`
- **Comment, label, or close**: `gh pr comment`, `gh pr edit --add-label` or `--remove-label`, and `gh pr close`

GitHub shares one number space across issues and PRs. Resolve a bare `#42` with `gh pr view 42`, then fall back to `gh issue view 42`.

## When a skill says "publish to the issue tracker"

Create a GitHub issue.

## When a skill says "fetch the relevant ticket"

Run `gh issue view <number> --comments`.

## Wayfinding operations

Used by `/wayfinder`. The map is one issue with child issues as tickets.

- **Map**: an issue labelled `wayfinder:map`, holding Notes, Decisions-so-far, and Fog. Create with `gh issue create --label wayfinder:map`.
- **Child ticket**: an issue linked to the map as a GitHub sub-issue using `gh api`. Where sub-issues are unavailable, add the child to a task list in the map body and put `Part of #<map>` at the top of the child body. Use a `wayfinder:<type>` label: `research`, `prototype`, `grilling`, or `task`. Assign claimed tickets to the driving developer.
- **Blocking**: use GitHub native issue dependencies. Add an edge with `gh api --method POST repos/<owner>/<repo>/issues/<child>/dependencies/blocked_by -F issue_id=<blocker-db-id>`, where `<blocker-db-id>` is the blocker numeric database ID from `gh api repos/<owner>/<repo>/issues/<n> --jq .id`. Where dependencies are unavailable, use `Blocked by: #<n>, #<n>` at the top of the child body.
- **Frontier query**: list open map children, then omit tickets with an open blocker or assignee. First in map order wins.
- **Claim**: `gh issue edit <n> --add-assignee @me`. This is the session's first write.
- **Resolve**: comment with the answer, close the ticket, then append a gist and link to the map's Decisions-so-far.
