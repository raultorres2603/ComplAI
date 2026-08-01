import * as cdk from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { QueueStack } from '../queue-stack';

function createStack(env: 'development' | 'production'): Template {
  const app = new cdk.App();
  const stack = new QueueStack(app, `ComplAIQueueStack-${env}`, {
    stackName: `ComplAIQueueStack-${env}`,
    environment: env,
    env: { account: '123456789012', region: 'eu-west-1' },
  });
  return Template.fromStack(stack);
}

describe('QueueStack', () => {
  describe('development environment', () => {
    let template: Template;

    beforeAll(() => {
      template = createStack('development');
    });

    test('creates exactly 6 SQS queues (3 main + 3 DLQ)', () => {
      template.resourceCountIs('AWS::SQS::Queue', 6);
    });

    test('redact queue has visibility timeout of 90 seconds', () => {
      template.hasResourceProperties('AWS::SQS::Queue', {
        QueueName: 'complai-redact-development',
        VisibilityTimeout: 90,
      });
    });

    test('feedback queue has visibility timeout of 90 seconds', () => {
      template.hasResourceProperties('AWS::SQS::Queue', {
        QueueName: 'complai-feedback-development',
        VisibilityTimeout: 90,
      });
    });

    test('ask queue has visibility timeout of 120 seconds', () => {
      template.hasResourceProperties('AWS::SQS::Queue', {
        QueueName: 'complai-ask-development',
        VisibilityTimeout: 120,
      });
    });

    test('main queues have retention period of 4 hours (14400 seconds)', () => {
      const queues = template.findResources('AWS::SQS::Queue', {
        Properties: { QueueName: Match.stringLikeRegexp('complai-(redact|feedback|ask)-development') },
      });
      for (const id of Object.keys(queues)) {
        expect(queues[id].Properties.MessageRetentionPeriod).toBe(14400);
      }
    });

    test('DLQs have retention period of 7 days (604800 seconds)', () => {
      const dlqs = template.findResources('AWS::SQS::Queue', {
        Properties: { QueueName: Match.stringLikeRegexp('complai-.*-dlq-development') },
      });
      for (const id of Object.keys(dlqs)) {
        expect(dlqs[id].Properties.MessageRetentionPeriod).toBe(604800);
      }
    });

    test('redact queue has redrive policy with maxReceiveCount 3', () => {
      template.hasResourceProperties('AWS::SQS::Queue', {
        QueueName: 'complai-redact-development',
        RedrivePolicy: {
          maxReceiveCount: 3,
        },
      });
    });

    test('feedback queue has redrive policy with maxReceiveCount 3', () => {
      template.hasResourceProperties('AWS::SQS::Queue', {
        QueueName: 'complai-feedback-development',
        RedrivePolicy: {
          maxReceiveCount: 3,
        },
      });
    });

    test('ask queue has redrive policy with maxReceiveCount 3', () => {
      template.hasResourceProperties('AWS::SQS::Queue', {
        QueueName: 'complai-ask-development',
        RedrivePolicy: {
          maxReceiveCount: 3,
        },
      });
    });

    test('development queues have removal policy Delete', () => {
      const queues = template.findResources('AWS::SQS::Queue');
      for (const id of Object.keys(queues)) {
        expect(queues[id].DeletionPolicy).toBe('Delete');
      }
    });

    test('creates queue URL outputs', () => {
      const outputs = template.findOutputs('*');
      const queueOutputs = Object.keys(outputs).filter(k => k.includes('Queue'));
      expect(queueOutputs.length).toBeGreaterThanOrEqual(3);
    });
  });

  describe('production environment', () => {
    let template: Template;

    beforeAll(() => {
      template = createStack('production');
    });

    test('all queues have removal policy Retain', () => {
      const queues = template.findResources('AWS::SQS::Queue');
      for (const id of Object.keys(queues)) {
        expect(queues[id].DeletionPolicy).toBe('Retain');
      }
    });
  });
});
