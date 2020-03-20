/*
 *  LanguageTool, a natural language style checker
 *  * Copyright (C) 2018 Fabian Richter
 *  *
 *  * This library is free software; you can redistribute it and/or
 *  * modify it under the terms of the GNU Lesser General Public
 *  * License as published by the Free Software Foundation; either
 *  * version 2.1 of the License, or (at your option) any later version.
 *  *
 *  * This library is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  * Lesser General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU Lesser General Public
 *  * License along with this library; if not, write to the Free Software
 *  * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 *  * USA
 *
 */

package org.languagetool.rules.confusion;

import com.google.common.collect.Streams;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NegotiationType;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import org.jetbrains.annotations.Nullable;
import org.languagetool.AnalyzedSentence;
import org.languagetool.rules.RuleMatch;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class ConfPairModel {
  private final ConfPairGrpc.ConfPairBlockingStub model;
  private final ManagedChannel channel;

  public ConfPairModel(String host, int port, boolean useSSL,
                             @Nullable String clientPrivateKey, @Nullable  String clientCertificate,
                             @Nullable String rootCertificate) throws SSLException {
    // TODO configure deadline/retries/... here?
    model = ConfPairGrpc.newBlockingStub(getChannel(
      host, port, useSSL, clientPrivateKey, clientCertificate, rootCertificate));
    channel = getChannel(host, port, useSSL, clientPrivateKey, clientCertificate, rootCertificate);
  }

  private ManagedChannel getChannel(String host, int port, boolean useSSL,
                                    @Nullable String clientPrivateKey, @Nullable  String clientCertificate,
                                    @Nullable String rootCertificate) throws SSLException {
    NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(host, port);
    if (useSSL) {
      SslContextBuilder sslContextBuilder = GrpcSslContexts.forClient();
      if (rootCertificate != null) {
        sslContextBuilder.trustManager(new File(rootCertificate));
      }
      if (clientCertificate != null && clientPrivateKey != null) {
        sslContextBuilder.keyManager(new File(clientCertificate), new File(clientPrivateKey));
      }
      channelBuilder = channelBuilder.negotiationType(NegotiationType.TLS).sslContext(sslContextBuilder.build());
    } else {
      channelBuilder = channelBuilder.usePlaintext();
    }
    return channelBuilder.build();
  }

  public void shutdown() {
    if (channel != null) {
      channel.shutdownNow();
    }
  }

  public List<RuleMatch> eval(List<AnalyzedSentence> text, List<List<RuleMatch>> candidates) {
    List<ConfPairProto.Request> requests = Streams
      .zip(text.stream(), candidates.stream(), (sentence, matches) ->
      ConfPairProto.Request.newBuilder()
        .setText(sentence.getText())
        .addAllCandidates(matches.stream().map(match ->
          ConfPairProto.Candidate.newBuilder()
            .setOffset(match.getFromPos())
            .setLength(match.getToPos() - match.getFromPos())
            .build())
          .collect(Collectors.toList()))
        .build()
    ).collect(Collectors.toList());
    ConfPairProto.BatchRequest batch = ConfPairProto.BatchRequest.newBuilder()
      .addAllRequests(requests).build();
    List<ConfPairProto.Response> responses = model.evalBatch(batch).getResponsesList();
    return Streams.zip(candidates.stream(), responses.stream(), (matches, response) ->
      Streams.zip(matches.stream(), response.getMatchesList().stream(), (ruleMatch, modelMatch) -> {
        ruleMatch.setSuggestedReplacements(modelMatch.getReplacementsList());
        return ruleMatch;
    }).collect(Collectors.toList()))
      .flatMap(Collection::stream)
      .collect(Collectors.toList());
  }
}
