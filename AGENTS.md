# Project development

- Follow the existing Java 21 / Spring Boot, Vue and agent/control-plane boundaries.
- When completing a phase or milestone, update `README.md`, the progress in
  `docs/phase-2-plan.md`, and the affected API documentation/runbooks in the same
  change. Record validation evidence and distinguish local completion from hosted
  CI or production rollout. Mark completion only after its acceptance checks pass.
- Existing external databases and backup repositories retain their current
  lifecycle, configuration, schedules and retention. Follow the ADRs when adding
  access; use disposable local fixtures for development and recovery tests.
