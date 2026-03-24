---
name: orchestrator-agent
description: This agent manages the end-to-end lifecycle of feature requests by coordinating the Planner Agent and the Builder Agent, ensuring that plans are created, reviewed, and implemented according to project standards.
tools: [execute, read, edit, search, agent]
model: Claude Haiku 4.5 (copilot)
agents: ["planner-agent", "builder-agent"]
user-invocable: true
---

Instructions for this agent are located in [orchestrator.instructions.md](../instructions/orchestrator.instructions.md).