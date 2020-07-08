/*
 *  LanguageTool, a natural language style checker
 *  * Copyright (C) 2020 Fabian Richter
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
package org.languagetool.rules;

import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.JLanguageTool;
import org.languagetool.Languages;
import org.languagetool.markup.AnnotatedText;
import org.languagetool.rules.ml.MLServerProto;
import org.languagetool.rules.ml.MLServerProto.Match;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.stream.Collectors;

public class MissingArticlesRule extends GRPCRule {

	private final String description;
	private final Map<String, String> messages;

	public MissingArticlesRule(final ResourceBundle messageBundle, final RemoteRuleConfig config,
                             final String id, final String description, final Map<String, String> messages) {
		super(messageBundle, config);
    this.description = description;
    this.messages = messages;
	}

	@Override
	protected String getMessage(final Match match, final AnalyzedSentence sentence) {
		return messages.get(match.getSubId());
	}

	@Override
	public String getDescription() {
		return description;
	}


  protected static boolean isCandidate(AnalyzedSentence s) {
    AnalyzedTokenReadings[] tokens = s.getTokensWithoutWhitespace();
    for (int i = 1; i < tokens.length; i++) {
      if (tokens[i].hasPartialPosTag("NN") &&
          !(tokens[i-1].hasPartialPosTag("DT") ||
           i >= 2 && tokens[i-1].hasPartialPosTag("JJ") && tokens[i-2].hasPartialPosTag("DT"))) {
        return true;
      }
    }
    return false;
  }

  @Override
  protected RemoteRule.RemoteRequest prepareRequest(List<AnalyzedSentence> sentences, AnnotatedText annotatedText) {
    List<String> text = sentences.stream().filter(MissingArticlesRule::isCandidate).map(AnalyzedSentence::getText).collect(Collectors.toList());
    System.out.printf("Filtered sentences: %s -> %s%n.",
      sentences.stream().map(AnalyzedSentence::getText).collect(Collectors.joining(" | ")),
      String.join(" | ", text));
    MLServerProto.MatchRequest req = MLServerProto.MatchRequest.newBuilder().addAllSentences(text).build();
    // TODO: this doesn't work; executeRequest right now needs all sentences to compute offets
    //  (fixed in remoteRuleOffsets branch, which has other issues)
    // but req.sentences and  sentences need to be aligned as well
    // if offset computation is outside of remote rule, we can just pass the filtered sentences here as well and everything should work
    return new MLRuleRequest(req, sentences);
  }

  public static void main(String[] argv) throws IOException {
    JLanguageTool lt = new JLanguageTool(Languages.getLanguageForName("English"));
    String line;
    BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));
    while ((line = reader.readLine()) != null) {
      AnalyzedSentence sentence = lt.getAnalyzedSentence(line);
      System.out.println(isCandidate(sentence));
    }
  }
}
