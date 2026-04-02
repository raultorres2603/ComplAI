import * as cdk from 'aws-cdk-lib';
import { Construct } from 'constructs';
import * as wafv2 from 'aws-cdk-lib/aws-wafv2';
import { HttpApi } from 'aws-cdk-lib/aws-apigatewayv2';
import { DeploymentEnvironment } from './deployment-environment';

export interface WafStackProps extends cdk.StackProps {
  readonly environment: DeploymentEnvironment;
  readonly httpApi: HttpApi;
}

export class WafStack extends cdk.Stack {
  constructor(scope: Construct, id: string, props: WafStackProps) {
    super(scope, id, props);

    const { environment, httpApi } = props;

    const webAcl = new wafv2.CfnWebACL(this, `ComplAIWebACL-${environment}`, {
      name: `ComplAIWebACL-${environment}`,
      scope: 'REGIONAL',
      defaultAction: { block: {} },
      visibilityConfig: {
        cloudWatchMetricsEnabled: true,
        metricName: `ComplAIWaf-${environment}`,
        sampledRequestsEnabled: true,
      },
      rules: [
        {
          name: `AllowSpain-${environment}`,
          priority: 1,
          action: { allow: {} },
          statement: {
            geoMatchStatement: {
              countryCodes: ['ES'],
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            metricName: `AllowSpain-${environment}`,
            sampledRequestsEnabled: true,
          },
        },
        {
          name: `RateLimit100per5min-${environment}`,
          priority: 2,
          action: { block: {} },
          statement: {
            rateBasedStatement: {
              limit: 100,
              evaluationWindowSec: 300,
              aggregateKeyType: 'IP',
            },
          },
          visibilityConfig: {
            cloudWatchMetricsEnabled: true,
            metricName: `RateLimit-${environment}`,
            sampledRequestsEnabled: true,
          },
        },
      ],
    });

    webAcl.node.addDependency(httpApi);

    const stageArn = cdk.Fn.join('', [
      'arn:aws:apigateway:',
      this.region,
      '::/apis/',
      httpApi.apiId,
      '/stages/',
      httpApi.defaultStage!.stageName,
    ]);

    new wafv2.CfnWebACLAssociation(this, `ComplAIWebACLAssociation-${environment}`, {
      resourceArn: stageArn,
      webAclArn: webAcl.attrArn,
    });
  }
}
