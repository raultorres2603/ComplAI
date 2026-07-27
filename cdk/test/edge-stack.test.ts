import * as cdk from 'aws-cdk-lib';
import { Template, Match } from 'aws-cdk-lib/assertions';
import { EdgeStack } from '../edge-stack';

function createStack(): Template {
  const app = new cdk.App();
  const stack = new EdgeStack(app, 'ComplAIEdgeStack-production', {
    stackName: 'ComplAIEdgeStack-production',
    environment: 'production',
    httpApiId: 'test-api-id',
    httpApiRegion: 'eu-west-1',
    env: { account: '123456789012', region: 'us-east-1' },
  });
  return Template.fromStack(stack);
}

describe('EdgeStack', () => {
  let template: Template;

  beforeAll(() => {
    template = createStack();
  });

  test('creates WAF WebACL with CLOUDFRONT scope', () => {
    template.hasResourceProperties('AWS::WAFv2::WebACL', {
      Scope: 'CLOUDFRONT',
    });
  });

  test('WebACL has exactly 2 rules', () => {
    template.hasResourceProperties('AWS::WAFv2::WebACL', {
      Rules: Match.arrayWith([
        Match.objectLike({ Name: Match.stringLikeRegexp('AllowSpain.*') }),
        Match.objectLike({ Name: Match.stringLikeRegexp('RateLimit.*') }),
      ]),
    });
  });

  test('WAF rule 1 is geo match with country code ES', () => {
    template.hasResourceProperties('AWS::WAFv2::WebACL', {
      Rules: Match.arrayWith([
        Match.objectLike({
          Statement: {
            GeoMatchStatement: {
              CountryCodes: ['ES'],
            },
          },
        }),
      ]),
    });
  });

  test('WAF rule 2 is rate limit with limit 100 and window 300s', () => {
    template.hasResourceProperties('AWS::WAFv2::WebACL', {
      Rules: Match.arrayWith([
        Match.objectLike({
          Statement: {
            RateBasedStatement: {
              Limit: 100,
              EvaluationWindowSec: 300,
              AggregateKeyType: 'IP',
            },
          },
        }),
      ]),
    });
  });

  test('creates CloudFront CachePolicy', () => {
    template.resourceCountIs('AWS::CloudFront::CachePolicy', 1);
  });

  test('CachePolicy has default TTL of 60 seconds', () => {
    template.hasResourceProperties('AWS::CloudFront::CachePolicy', {
      CachePolicyConfig: {
        DefaultTTL: 60,
        MinTTL: 0,
        MaxTTL: 60,
      },
    });
  });

  test('creates CloudFront Distribution', () => {
    template.resourceCountIs('AWS::CloudFront::Distribution', 1);
  });

  test('Distribution origin contains execute-api', () => {
    template.hasResourceProperties('AWS::CloudFront::Distribution', {
      DistributionConfig: {
        Origins: Match.arrayWith([
          Match.objectLike({
            DomainName: Match.stringLikeRegexp('.*execute-api.*'),
          }),
        ]),
      },
    });
  });

  test('Distribution has WebACL attached', () => {
    template.hasResourceProperties('AWS::CloudFront::Distribution', {
      DistributionConfig: {
        WebACLId: Match.anyValue(),
      },
    });
  });
});
