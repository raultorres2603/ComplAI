import * as cdk from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { QueueStack } from '../queue-stack';
import { LambdaStack } from '../lambda-stack';

// Save and restore process.env to prevent test pollution
const originalEnv = { ...process.env };

beforeEach(() => {
  process.env = { ...originalEnv };
  // Bypass filesystem lookup — use Code.fromBucket instead
  process.env.DEPLOYMENT_JAR_KEY = 'test-key.zip';
});

afterEach(() => {
  process.env = originalEnv;
});

function createStacks(env: 'development' | 'production'): { queueStack: QueueStack; lambdaStack: LambdaStack; template: Template } {
  const app = new cdk.App();

  const queueStack = new QueueStack(app, `ComplAIQueueStack-${env}`, {
    stackName: `ComplAIQueueStack-${env}`,
    environment: env,
    env: { account: '123456789012', region: 'eu-west-1' },
  });

  const lambdaStack = new LambdaStack(app, `ComplAILambdaStack-${env}`, {
    stackName: `ComplAILambdaStack-${env}`,
    environment: env,
    redactQueue: queueStack.redactQueue,
    feedbackQueue: queueStack.feedbackQueue,
    askQueue: queueStack.askQueue,
    env: { account: '123456789012', region: 'eu-west-1' },
    crossRegionReferences: true,
  });

  const template = Template.fromStack(lambdaStack);
  return { queueStack, lambdaStack, template };
}

describe('LambdaStack', () => {
  describe('development environment', () => {
    let template: Template;

    beforeAll(() => {
      ({ template } = createStacks('development'));
    });

    test('creates 5 Lambda functions', () => {
      template.resourceCountIs('AWS::Lambda::Function', 5);
    });

    test('API Lambda has memory size 1024', () => {
      template.hasResourceProperties('AWS::Lambda::Function', {
        FunctionName: Match.stringLikeRegexp('ComplAILambda-development'),
        MemorySize: 1024,
      });
    });

    test('API Lambda has ARM64 architecture', () => {
      template.hasResourceProperties('AWS::Lambda::Function', {
        FunctionName: Match.stringLikeRegexp('ComplAILambda-development'),
        Architectures: ['arm64'],
      });
    });

    test('API Lambda has PROVIDED_AL2023 runtime', () => {
      template.hasResourceProperties('AWS::Lambda::Function', {
        FunctionName: Match.stringLikeRegexp('ComplAILambda-development'),
        Runtime: 'provided.al2023',
      });
    });

    test('worker Lambda has memory size 512', () => {
      template.hasResourceProperties('AWS::Lambda::Function', {
        FunctionName: Match.stringLikeRegexp('ComplAIRedactorLambda-development'),
        MemorySize: 512,
      });
    });

    test('feedback worker Lambda has memory size 256', () => {
      template.hasResourceProperties('AWS::Lambda::Function', {
        FunctionName: Match.stringLikeRegexp('ComplAIFeedbackWorkerLambda-development'),
        MemorySize: 256,
      });
    });

    test('ask worker Lambda has memory size 256', () => {
      template.hasResourceProperties('AWS::Lambda::Function', {
        FunctionName: Match.stringLikeRegexp('ComplAIAskWorkerLambda-development'),
        MemorySize: 256,
      });
    });

    test('creates HttpApi (API Gateway v2)', () => {
      template.resourceCountIs('AWS::ApiGatewayV2::Api', 1);
    });

    test('creates 3 SQS event source mappings', () => {
      template.resourceCountIs('AWS::Lambda::EventSourceMapping', 3);
    });

    test('creates EventBridge rule with weekly cron', () => {
      template.hasResourceProperties('AWS::Events::Rule', {
        ScheduleExpression: 'cron(0 3 ? * MON *)',
      });
    });

    test('creates 5 IAM roles', () => {
      template.resourceCountIs('AWS::IAM::Role', 5);
    });

    test('creates log groups for all Lambdas', () => {
      template.resourceCountIs('AWS::Logs::LogGroup', 5);
    });
  });

  describe('production environment', () => {
    let template: Template;

    beforeAll(() => {
      ({ template } = createStacks('production'));
    });

    test('creates 5 Lambda functions', () => {
      template.resourceCountIs('AWS::Lambda::Function', 5);
    });

    test('creates HttpApi', () => {
      template.resourceCountIs('AWS::ApiGatewayV2::Api', 1);
    });
  });
});
