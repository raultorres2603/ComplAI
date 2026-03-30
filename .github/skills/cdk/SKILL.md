---
name: cdk
description: Skill to know how to create/update/remove CDK components
---

# CDK Context
This skill contains knowledge about how to create, update, and remove CDK components in the ComplAI codebase. It includes best practices for defining infrastructure as code, managing dependencies, and ensuring that CDK changes are properly tested and deployed. Use this skill when you need to modify the infrastructure layer of ComplAI, such as adding new AWS resources, updating existing ones, or removing unused components.
## Best Practices
- Always follow the existing architectural patterns and conventions in the CDK codebase.
- Ensure that any new CDK components are properly scoped to the relevant city and service.
- When updating or removing CDK components, carefully consider the impact on existing resources and services, and ensure that any necessary migration steps are included in the plan.