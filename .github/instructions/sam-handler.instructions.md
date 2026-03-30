---
description: How to handle local SAM resources
applyTo: **/sam/**
---
# SAM Handler Instructions
When working with local SAM resources, follow these guidelines:
1. **Use Local SAM Resources**: When running the application locally, ensure that you are using the local SAM resources defined in the `sam` directory. This allows you to test and develop against a local environment that closely mimics production.
2. **Avoid Modifying SAM Resources**: Do not make changes to the SAM resources unless absolutely necessary. If you need to make changes, ensure that they are well-documented and reviewed by the team to avoid unintended consequences.
3. **Testing**: When running tests locally, make sure to use the local SAM resources. This will help ensure that your tests are accurate and reflect the behavior of the application in a local environment.
4. **Check CDK**: Compatibilize any change on CDK with the SAM resources. If you need to make changes to the CDK, ensure that they are compatible with the SAM resources and do not break the local development environment.
5. **Documentation**: If you make any changes to the SAM resources, update the documentation accordingly. This will help other developers understand the changes and how to work with the SAM resources effectively.