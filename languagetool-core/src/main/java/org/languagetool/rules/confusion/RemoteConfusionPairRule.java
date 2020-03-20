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

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.languagetool.*;
import org.languagetool.databroker.ResourceDataBroker;
import org.languagetool.rules.*;
import org.languagetool.tools.Tools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

public abstract class RemoteConfusionPairRule extends RemoteRule {
  private static final Logger logger = LoggerFactory.getLogger(RemoteConfusionPairRule.class);
  public static final String RULE_ID = "ML_CONFUSION_RULE";

  private static final LoadingCache<RemoteRuleConfig, ConfPairModel> models =
    CacheBuilder.newBuilder().build(CacheLoader.from(serviceConfiguration -> {
      String host = serviceConfiguration.getUrl();
      int port = serviceConfiguration.getPort();
      boolean ssl = Boolean.parseBoolean(serviceConfiguration.getOptions().getOrDefault("secure", "false"));
      String key = serviceConfiguration.getOptions().get("clientKey");
      String cert = serviceConfiguration.getOptions().get("clientCertificate");
      String ca = serviceConfiguration.getOptions().get("rootCertificate");
      try {
        return new ConfPairModel(host, port, ssl, key, cert, ca);
      } catch (SSLException e) {
        throw new RuntimeException(e);
      }
    }));

  static {
    shutdownRoutines.add(() -> models.asMap().values().forEach(ConfPairModel::shutdown));
  }
  private final ConfPairModel model;
  //protected AhoCorasickDoubleArrayTrie<String> searcher;
  protected Map<String, List<ConfusionPair>> pairMap;

  protected static Map<String, List<ConfusionPair>> loadPairs(String confusionSetsFile, Language lang) {

    ResourceDataBroker dataBroker = JLanguageTool.getDataBroker();
    try (InputStream confusionSetStream = dataBroker.getFromResourceDirAsStream(confusionSetsFile)) {
      ConfusionSetLoader loader = new ConfusionSetLoader(lang);
      Map<String, List<ConfusionPair>> confusionPairs = loader.loadConfusionPairs(confusionSetStream);
      return confusionPairs;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
/*
  protected static AhoCorasickDoubleArrayTrie<String> buildSearcher(Map<String, List<ConfusionPair>> pairs) {
    TreeMap<String, String> map = new TreeMap<>();
    for (String candidate : pairs.keySet()) {
      map.put(candidate, candidate);
    }
    AhoCorasickDoubleArrayTrie<String> searcher = new AhoCorasickDoubleArrayTrie<>();
    searcher.build(map);
    return searcher;
  }
*/

  public RemoteConfusionPairRule(ResourceBundle messages, RemoteRuleConfig config, UserConfig userConfig) {
    super(messages, config);

    synchronized (models) {
      ConfPairModel model = null;
      if (getId().equals(userConfig.getAbTest())) {
        try {
          model = models.get(serviceConfiguration);
        } catch (Exception e) {
          logger.error("Could not connect to confusion pair service at " + serviceConfiguration, e);
        }
      }
      this.model = model;
    }
    this.pairMap = new HashMap<>();
    ConfusionPair pair = new ConfusionPair("if", "of", 2L, true);
    pairMap.put("if", Arrays.asList(pair));
    pairMap.put("of", Arrays.asList(pair));
    // needed if we want to match multi-token words, e.g. "it's"
    //this.searcher = buildSearcher(pairMap);
  }

  class ConfusionPairCandidates extends RemoteRequest {
    List<Integer> matchOffsets = new LinkedList<>();
    List<AnalyzedSentence> sentences = new LinkedList<>();
    List<List<RuleMatch>> candidates = new LinkedList<>();
  }

  @Override
  protected RemoteRule.RemoteRequest prepareRequest(List<AnalyzedSentence> sentences) {
    ConfusionPairCandidates request = new ConfusionPairCandidates();
    int offset = 0;
    for (AnalyzedSentence s : sentences) {
      String text = s.getText();
      List<AnalyzedTokenReadings> matched = new LinkedList<>();
      for (AnalyzedTokenReadings token : s.getTokensWithoutWhitespace()) {
        String word = token.getToken().toLowerCase();
        if (pairMap.containsKey(word)) {
          matched.add(token);
        }
      }
      if (!matched.isEmpty()) {
        request.matchOffsets.add(offset);
        request.sentences.add(s);
        request.candidates.add(matched.stream()
          .map(hit -> new RuleMatch(this, s, hit.getStartPos(), hit.getEndPos(), ""))
          .collect(Collectors.toList()));
      }
      //List<AhoCorasickDoubleArrayTrie.Hit<String>> hits = searcher.parseText(text);
      //if (!hits.isEmpty()) {
      //}
      offset += text.length();
    }
    return request;
  }

  @Override
  protected Callable<RemoteRuleResult> executeRequest(RemoteRule.RemoteRequest request) {
    if (!(request instanceof ConfusionPairCandidates)) {
      throw new IllegalArgumentException("Expected instance of class ConfusionPairCandidates");
    }
    return () -> {
      if (model == null) {
        return fallbackResults(request);
      }
      ConfusionPairCandidates candidates = (ConfusionPairCandidates) request;
      if (candidates.sentences.isEmpty()) {
        return new RemoteRuleResult(false, Collections.emptyList());
      }
      List<RuleMatch> matches = model.eval(candidates.sentences, candidates.candidates);
      List<RuleMatch> result = new LinkedList<>();
      for (RuleMatch match : matches) {
        String matched = match.getSentence().getText().substring(match.getFromPos(), match.getToPos());
        String description = pairMap.get(matched).stream()
          .flatMap(pair -> pair.getTerms().stream())
          .filter(cs -> cs.getString().equalsIgnoreCase(matched))
          .findFirst().map(ConfusionString::getDescription).orElse(null);
        String message;
        if (description == null) {
          message = Tools.i18n(messages, "statistics_suggest_negative1", matched);
        } else {
          message = Tools.i18n(messages, "statistics_suggest_negative2", matched, description);
        }

        int sentenceIndex = candidates.sentences.indexOf(match.getSentence());
        int offset = candidates.matchOffsets.get(sentenceIndex);
        match.setOffsetPosition(match.getFromPos() + offset, match.getToPos() + offset);

        RuleMatch newMatch = new RuleMatch(match, message, null);
        result.add(newMatch);
      }
      return new RemoteRuleResult(true, result);
    };
  }

  @Override
  protected RemoteRuleResult fallbackResults(RemoteRule.RemoteRequest request) {
    return new RemoteRuleResult(false, Collections.emptyList());
  }
}
