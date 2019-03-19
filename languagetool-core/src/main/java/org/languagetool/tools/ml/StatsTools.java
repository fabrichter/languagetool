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

package org.languagetool.tools.ml;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.List;
import java.util.stream.Collectors;

public final class StatsTools {
  private static StatsTools instance = null;

  private final ManagedChannel channel;
  private final UtilsGrpc.UtilsBlockingStub stub;

  private StatsTools(String host, int port) {
    channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
    stub = UtilsGrpc.newBlockingStub(channel);
  }

  public static class PRCurveEntry {
    public final double threshold;
    public final double precision;
    public final double recall;

    public PRCurveEntry(double threshold, double precision, double recall) {
      this.threshold = threshold;
      this.precision = precision;
      this.recall = recall;
    }
  }

  public List<PRCurveEntry> computePRCurve(List<Double> predictions, List<Boolean> target) {
    MachineLearningUtilsProto.Predictions request = MachineLearningUtilsProto.Predictions.newBuilder()
      .addAllPrediction(predictions)
      .addAllTarget(target)
      .build();
    MachineLearningUtilsProto.PRCurve response =  stub.computePRCurve(request);
    return response.getThresholdsList().stream()
      .map(t -> new PRCurveEntry(t.getThreshold(), t.getPrecision(), t.getRecall()))
      .collect(Collectors.toList());
  }

  public static StatsTools getInstance() {
    if (instance == null) {
      instance = new StatsTools("127.0.0.1", 50051);
    }
    return instance;
  }

}
