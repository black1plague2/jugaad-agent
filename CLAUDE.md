# Jugaad Agent

Read `CONTEXT.md` first (how to work here, toolchain, phones), then `HANDOFF.md` (current
state), then `FEDERATED.md`. Plans under `plans/` are binding contracts; their "Result"
sections hold only verified facts.

Hard rules:

- Never train, share or calibrate on readings taken with the phones on a table or on
  synthetic device-test samples. Test equipment gets the "Bench / test equipment" flag;
  `*.DO-NOT-TRAIN.jsonl` files never enter `ml/fl/pretrain.py`.
- Every reported fact is backed by a log line, a test or a screenshot; never estimate a number.
- Verify the outcome on the phone, not the acknowledgement from adb or gradle.
- Surgical changes only; no em or en dashes in UI strings.
- Export `JAVA_HOME` to a JDK 17 before `./gradlew`; every app log line is tagged `JUGAAD`.
- Delegate scoped implementation to Sonnet and search to Haiku subagents with a written
  contract (task, files, deliverable, max 40-line return, no nested agents); keep phone-state
  changes and final verification in the main session.
