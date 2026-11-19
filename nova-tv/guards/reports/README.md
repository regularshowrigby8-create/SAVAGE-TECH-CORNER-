# Guard reports

CI (`.github/workflows/nova-guards.yml`, job "Commit tool reports") writes the
imported tools' own report files here on every push:

- `detekt.md`: detekt's Markdown report (anti-dead-button)
- `lychee.md`: lychee's Markdown summary (anti-hallucination)
- `swallowed.txt`: swallowed's terminal report (anti-false-green)

ruff, actionlint and the unit-test results appear as annotations and checks on
the workflow run; PIT's HTML report is the `nova-pitest-report` artifact.
