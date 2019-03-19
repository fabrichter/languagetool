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

import java.io.Serializable;
import java.util.List;

public class LogisticRegressionClassifier {

  public static class Model implements Serializable {
    private final int numFeatures;
    private final List<Double> coef;
    private final List<Double> mean;
    private final List<Double> var;
    private final double intercept;

    public Model(int numFeatures, List<Double> coef, double intercept, List<Double> mean, List<Double> var) {
      this.numFeatures = numFeatures;
      this.coef = coef;
      this.mean = mean;
      this.var = var;
      this.intercept = intercept;
    }

    /**
     * Run prediction using the trained model
     * @param sample data point to make prediction for
     * @return probability of positive class
     */
    public double predict(List<Double> sample) {
      if (sample.size() != numFeatures) {
        throw new RuntimeException(String.format("Got sample with dimension %d, expected %d", sample.size(), numFeatures));
      }
      // np.dot((x - mean) / var, coef) + intercept
      double result = 0;
      for (int i = 0; i < numFeatures; i++) {
        double normed = (sample.get(i) - mean.get(i)) / var.get(i);
        result += coef.get(i) * normed;
      }
      result += intercept;
      // logistic function
      result = 1 / (1 + Math.exp(-result));
      return result;
    }

  }

  public static class Sample {
    private final boolean label;
    private final List<Double> features;

    public Sample(boolean label, List<Double> features) {
      this.label = label;
      this.features = features;
    }

    public boolean isLabel() {
      return label;
    }

    public List<Double> getFeatures() {
      return features;
    }
  }

  private final ManagedChannel channel;
  private final LogisticRegressionGrpc.LogisticRegressionBlockingStub stub;

  public LogisticRegressionClassifier(String host, int port) {
    this(ManagedChannelBuilder.forAddress(host, port).usePlaintext());
  }

  public LogisticRegressionClassifier(ManagedChannelBuilder<?> channelBuilder) {
    channel = channelBuilder.build();
    stub = LogisticRegressionGrpc.newBlockingStub(channel);
  }

  public Model train(List<Sample> data, int numFeatures) {
    MachineLearningUtilsProto.Dataset.Builder request = MachineLearningUtilsProto.Dataset.newBuilder();
    for (Sample sample : data) {
      MachineLearningUtilsProto.Datapoint newSample = MachineLearningUtilsProto.Datapoint.newBuilder()
        .addAllX(sample.features).setY(sample.label).build();
      request.addSamples(newSample);
    }
    request.setNumFeatures(numFeatures);
    MachineLearningUtilsProto.RegressionModel response = stub.train(request.build());
    Model model = new Model(numFeatures, response.getCoefList(), response.getIntercept(),
      response.getMeanList(), response.getVarList());
    return model;
  }

}
