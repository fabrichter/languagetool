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

package org.languagetool.rules.bert;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.Language;
import org.languagetool.languagemodel.LanguageModel;
import org.languagetool.languagemodel.bert.BertLanguageModel;
import org.languagetool.rules.ConfusionSet;
import org.languagetool.rules.ConfusionString;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.ngrams.ConfusionProbabilityRule;
import org.languagetool.tools.StringTools;

import java.util.*;
import java.util.stream.Collectors;

public class BertConfusionProbabilityRule extends ConfusionProbabilityRule {
  private final BertLanguageModel bert;

  public BertConfusionProbabilityRule(ResourceBundle messages, Language language, LanguageModel lm, String host, int port) {
    super(messages, lm, language);
    bert = new BertLanguageModel(host, port);
  }

  @Override
  public RuleMatch[] match(AnalyzedSentence sentence) {
    String text = sentence.getText();
    List<RuleMatch> matches = new ArrayList<>();
    for (AnalyzedTokenReadings token : sentence.getTokensWithoutWhitespace()) {
      String word = token.getToken();
      List<ConfusionSet> confusionSets = wordToSets.get(token.getToken());
      boolean uppercase = false;
      if (confusionSets == null && word.length() > 0 && Character.isUpperCase(word.charAt(0))) {
        confusionSets = wordToSets.get(StringTools.lowercaseFirstChar(word));
        uppercase = true;
      }
      if (confusionSets != null) {
        for (ConfusionSet confusionSet : confusionSets) {
          boolean isEasilyConfused = confusionSet != null;
          if (isEasilyConfused) {
            Set<ConfusionString> set = uppercase ? confusionSet.getUppercaseFirstCharSet() : confusionSet.getSet();

            ConfusionString betterAlternative = getBetterAlternativeOrNull(sentence, token, set, confusionSet.getFactor());
            if (betterAlternative != null && !isException(text)) {
              ConfusionString stringFromText = getConfusionString(set, word);
              String message = getMessage(stringFromText, betterAlternative);
              RuleMatch match = new RuleMatch(this, sentence, token.getStartPos(), token.getEndPos(), message);
              match.setSuggestedReplacement(betterAlternative.getString());
              matches.add(match);
            }
          }
        }
      }
    }
    return matches.toArray(new RuleMatch[0]);
  }

  @Override
  public List<Double> evaluateConfusionPair(AnalyzedSentence sentence, String textToken, String alternative) {
    List<Double> features = null;
    // enable next line to combine ngram and BERT features
    // features = super.evaluateConfusionPair(sentence, textToken, alternative);
    if (features == null) {
      return null;
    } else {
      features = new ArrayList<>(features);
    }
    Optional<AnalyzedTokenReadings> token = Arrays.stream(sentence.getTokensWithoutWhitespace())
      .filter(t -> t.getToken().equals(textToken)).findFirst();
    if (!token.isPresent()) {
      return null;
      //throw new NoSuchElementException(String.format("Neither of pair %s / %s found in sentence '%s'.",
      //  textToken, alternative, sentence.getText()));
    }
    List<String> candidates = Arrays.asList(textToken, alternative);
    Map<String, Double> probabilities = bert.getTermProbabilities(sentence, token.get(), candidates);
    double p1 = probabilities.get(textToken), p2 = probabilities.get(alternative);
    //double p1Log = p1 == 0.0 ? 0.0 : Math.log10(p1), p2Log = p2 == 0.0 ? 0.0 : Math.log10(p2);
    features.addAll(Arrays.asList(p1, p2, p1 / p2));
    return features;
  }

  private ConfusionString getBetterAlternativeOrNull(AnalyzedSentence sentence, AnalyzedTokenReadings token, Set<ConfusionString> set, long factor) {
    List<String> candidates = set.stream().map(ConfusionString::getString).collect(Collectors.toList());
    if (!candidates.contains(token.getToken())) {
      candidates.add(token.getToken());
    }
    Map<String, Double> probabilities = bert.getTermProbabilities(sentence, token, candidates);
    double currentProb = probabilities.get(token.getToken());
    for (ConfusionString confused : set) {
      if (confused.getString().equals(token.getToken())) {
        continue;
      }
      double confusedProb = probabilities.get(confused.getString());
      //System.out.printf("BertConfusion: %s vs %s : %f vs %f; factor: %d%n", token.getToken(), confused.getString(), currentProb, confusedProb, factor);
      // TODO: original ConfusionProbabilityRule uses >= MIN_PROB, but that doesn't seem right
      if (confusedProb > MIN_PROB && confusedProb > currentProb * factor) {
        return confused;
      }
    }
    return null;
  }
}
