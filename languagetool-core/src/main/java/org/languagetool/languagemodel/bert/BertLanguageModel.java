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

package org.languagetool.languagemodel.bert;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.languagemodel.bert.grpc.BertLmGrpc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.languagetool.languagemodel.bert.grpc.BertLmProto.*;

public class BertLanguageModel {
  private final ManagedChannel channel;
  private final BertLmGrpc.BertLmBlockingStub stub;

  public BertLanguageModel(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext());
  }

  public BertLanguageModel(ManagedChannelBuilder<?> channelBuilder) {
    channel = channelBuilder.build();
    stub = BertLmGrpc.newBlockingStub(channel);
  }


  public Map<String, Double> getTermProbabilities(AnalyzedSentence sentence, AnalyzedTokenReadings position, List<String> terms) {
    String text = sentence.getText();
    String masked = text.substring(0, position.getStartPos()) + text.substring(position.getEndPos());
    LookupRequest lookupRequest = LookupRequest.newBuilder()
      .addSentences(masked)
      .setMask(Mask.newBuilder().setSentenceIndex(0).setWordIndex(position.getStartPos()).build())
      .addAllCandidates(terms)
      .build();
    BertLmResponse response = stub.lookup(lookupRequest);
    List<Prediction> predictions = response.getResultList();
    Map<String, Double> result = new HashMap<>();
    for (Prediction p : predictions) {
      result.put(p.getWord(), p.getProbability());
    }
    return result;
  }
}
