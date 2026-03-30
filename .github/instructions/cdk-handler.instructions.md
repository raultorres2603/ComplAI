---
description: How to handle CDK creation/update or delition
applyTo: **/cdk/**
---

# CDK Handler Instructions
## Rules for handling CDK creation/update or deletion
1. **CDK Creation/Update**: When the `task.md` includes steps to create or update CDK infrastructure, follow these guidelines:
   - **Create/Update CDK Files**: Implement the necessary changes in the CDK files as specified in the `task.md`. Ensure that the CDK code adheres to best practices for infrastructure as code, including modularity and reusability.
   - **Synthesize CDK**: After making changes to the CDK files, run `cdk synth` to generate the CloudFormation templates. This step is crucial to validate that the CDK code is correct and can be deployed successfully.
   - **Validate Changes**: Review the synthesized CloudFormation templates to ensure that they reflect the intended infrastructure changes and do not introduce unintended consequences.
2. **CDK Deletion**: If the `task.md` includes steps to delete CDK infrastructure, follow these guidelines:
   - **Identify Resources to Delete**: Carefully identify which CDK resources are to be deleted based on the `task.md` instructions. Ensure that you understand the dependencies and implications of deleting these resources.
   - **Update CDK Files**: Remove or comment out the CDK code corresponding to the resources that need to be deleted. Ensure that the remaining CDK code remains functional and does not reference the deleted resources.
   - **Synthesize CDK**: Run `cdk synth` after making changes to ensure that the CDK code is still valid and that the CloudFormation templates reflect the intended deletions without errors.
   - **Validate Deletions**: Review the synthesized CloudFormation templates to confirm that the resources marked for deletion are now absent and that there are no unintended changes to other resources.
3. **Testing**: If the `task.md` specifies any tests related to CDK changes, ensure that these tests are implemented and executed as part of the build process. This may include unit tests for CDK constructs or integration tests that validate the deployment of the CDK infrastructure.
4. **Documentation**: If the `task.md` includes steps to update documentation related to CDK infrastructure, ensure that the documentation is updated to reflect the changes made to the CDK code. This may include updating README files, architecture diagrams, or any other relevant documentation to ensure that it accurately describes the current state of the CDK infrastructure.