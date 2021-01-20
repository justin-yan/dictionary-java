package com.justinyan.dictionary;

import com.slack.api.bolt.aws_lambda.SlackApiLambdaHandler;
import com.slack.api.bolt.aws_lambda.request.ApiGatewayRequest;

public class DictionaryHandler extends SlackApiLambdaHandler {

  public DictionaryHandler() {
    super(DictionaryApp.getApp());
  }

  @Override
  protected boolean isWarmupRequest(ApiGatewayRequest awsReq) {
    return awsReq != null && awsReq.getBody() != null && awsReq.getBody().equals("warmup=true");
  }
}
