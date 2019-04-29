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
import org.languagetool.languagemodel.bert.grpc.BertTokenClassifierGrpc;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.languagetool.languagemodel.bert.grpc.BertLmProto.*;

public class BertTokenClassifier {
  private final BertTokenClassifierGrpc.BertTokenClassifierBlockingStub stub;

  public BertTokenClassifier(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext());
  }

  public BertTokenClassifier(ManagedChannelBuilder<?> channelBuilder) {
    ManagedChannel channel = channelBuilder.build();
    stub = BertTokenClassifierGrpc.newBlockingStub(channel);
    //stub.withDeadline()
  }


  // TODO: train with context
  public Model train(List<String> sentences, List<String> tokens, List<Boolean> labels, List<Boolean> split) {
    Dataset.Builder data = Dataset.newBuilder();

    if (!(sentences.size() == tokens.size() && tokens.size() == labels.size() && labels.size() == split.size())) {
      throw new IllegalArgumentException("Inputs must be of the same length.");
    }

    data.addAllInput(buildInput(sentences, tokens));

    for (Boolean label : labels) {
      data.addTarget(ClassificationTarget.newBuilder()
        .setLabel(label ? 1 : 0)
        .build());
    }
    data.addAllTrain(split);

    return stub.train(data.build());
  }

  private static List<ClassificationInput> buildInput(List<String> sentences, List<String> tokens) {
    List<ClassificationInput> data = new ArrayList<>(sentences.size());
    for (int i = 0; i < sentences.size(); i++) {
      String sentence = sentences.get(i);
      String word = tokens.get(i);
      int sentenceIndex = 0;
      int wordIndex = sentence.indexOf(word);
      int wordSize = word.length();
      data.add(ClassificationInput.newBuilder()
        .addSentences(sentence)
        .setSentenceIndex(sentenceIndex)
        .setWordIndex(wordIndex)
        .setWordSize(wordSize)
        .build());
    }
    return data;
  }

  public List<List<Double>> classify(Model model, List<String> sentences, List<String> token) {
    List<ClassificationInput> input = buildInput(sentences, token);
    ClassificationRequest request = ClassificationRequest.newBuilder().addAllInput(input).setModel(model).build();
    ClassificationResponse response = stub.classify(request);
    return response.getResultList().stream()
      .map(ClassificationResult::getProbabilitiesList)
      .collect(Collectors.toList());
  }
}
