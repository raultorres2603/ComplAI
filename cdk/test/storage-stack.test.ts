import * as cdk from 'aws-cdk-lib';
import { Template } from 'aws-cdk-lib/assertions';
import { StorageStack } from '../storage-stack';

function createStack(env: 'development' | 'production'): Template {
  const app = new cdk.App();
  const stack = new StorageStack(app, `ComplAIStorageStack-${env}`, {
    stackName: `ComplAIStorageStack-${env}`,
    environment: env,
    env: { account: '123456789012', region: 'eu-west-1' },
  });
  return Template.fromStack(stack);
}

describe('StorageStack', () => {
  describe('development environment', () => {
    let template: Template;

    beforeAll(() => {
      template = createStack('development');
    });

    test('creates exactly 7 S3 buckets', () => {
      template.resourceCountIs('AWS::S3::Bucket', 7);
    });

    test('all buckets have BlockPublicAccess BLOCK_ALL', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        PublicAccessBlockConfiguration: {
          BlockPublicAcls: true,
          BlockPublicPolicy: true,
          IgnorePublicAcls: true,
          RestrictPublicBuckets: true,
        },
      });
    });

    test('all buckets use S3_MANAGED encryption', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketEncryption: {
          ServerSideEncryptionConfiguration: [
            {
              ServerSideEncryptionByDefault: {
                SSEAlgorithm: 'AES256',
              },
            },
          ],
        },
      });
    });

    test('procedures bucket has correct name', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-procedures-development',
      });
    });

    test('events bucket has correct name', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-events-development',
      });
    });

    test('news bucket has correct name', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-news-development',
      });
    });

    test('cityinfo bucket has correct name', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-cityinfo-development',
      });
    });

    test('complaints bucket has correct name', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-complaints-development',
      });
    });

    test('feedback bucket has correct name', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-feedback-development',
      });
    });

    test('deployments bucket has correct name', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-deployments-development',
      });
    });

    test('development complaints bucket removal policy is Delete', () => {
      const complaintsBucket = template.findResources('AWS::S3::Bucket', {
        Properties: { BucketName: 'complai-complaints-development' },
      });
      const bucketId = Object.keys(complaintsBucket)[0];
      expect(complaintsBucket[bucketId].DeletionPolicy).toBe('Delete');
    });

    test('development feedback bucket removal policy is Delete', () => {
      const feedbackBucket = template.findResources('AWS::S3::Bucket', {
        Properties: { BucketName: 'complai-feedback-development' },
      });
      const bucketId = Object.keys(feedbackBucket)[0];
      expect(feedbackBucket[bucketId].DeletionPolicy).toBe('Delete');
    });

    test('feedback bucket lifecycle expiration is 30 days in development', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-feedback-development',
        LifecycleConfiguration: {
          Rules: [
            {
              ExpirationInDays: 30,
              Status: 'Enabled',
            },
          ],
        },
      });
    });

    test('creates 7 CfnOutputs', () => {
      const outputs = template.findOutputs('*');
      const bucketOutputs = Object.keys(outputs).filter(k => k.includes('Bucket'));
      expect(bucketOutputs.length).toBe(7);
    });
  });

  describe('production environment', () => {
    let template: Template;

    beforeAll(() => {
      template = createStack('production');
    });

    test('all 7 buckets have removal policy Retain', () => {
      const buckets = template.findResources('AWS::S3::Bucket');
      const bucketIds = Object.keys(buckets);
      expect(bucketIds.length).toBe(7);
      for (const id of bucketIds) {
        expect(buckets[id].DeletionPolicy).toBe('Retain');
      }
    });

    test('feedback bucket lifecycle expiration is 90 days in production', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-feedback-production',
        LifecycleConfiguration: {
          Rules: [
            {
              ExpirationInDays: 90,
              Status: 'Enabled',
            },
          ],
        },
      });
    });

    test('complaints bucket lifecycle expiration is 30 days in production', () => {
      template.hasResourceProperties('AWS::S3::Bucket', {
        BucketName: 'complai-complaints-production',
        LifecycleConfiguration: {
          Rules: [
            {
              ExpirationInDays: 30,
              Status: 'Enabled',
            },
          ],
        },
      });
    });
  });
});
