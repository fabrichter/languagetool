/* LanguageTool, a natural language style checker
 * Copyright (C) 2019 Daniel Naber (http://www.danielnaber.de)
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.rules.fr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jetbrains.annotations.NotNull;
import org.languagetool.AnalyzedSentence;
import org.languagetool.GlobalConfig;
import org.languagetool.markup.AnnotatedText;
import org.languagetool.rules.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Queries a local Grammalecte server.
 * @since 4.6
 */
public class GrammalecteRule extends RemoteRule {

  public static final String RULE_ID = "FR_GRAMMALECTE";
  private static Logger logger = LoggerFactory.getLogger(GrammalecteRule.class);
  private static final int TIMEOUT_MILLIS = 500;
  private static final float TIME_PER_CHAR_MILLIS = 0.5f;
  private static final int FALL = 3;
  private static final long DOWN_INTERVAL_MILLISECONDS = 5000;

  private final ObjectMapper mapper = new ObjectMapper();
  private final GlobalConfig globalConfig;

  // https://github.com/languagetooler-gmbh/languagetool-premium/issues/197:
  private final Set<String> ignoreRules = new HashSet<>(Arrays.asList(
    "tab_fin_ligne",
    "apostrophe_typographique",
    "typo_guillemets_typographiques_doubles_ouvrants",
    "nbsp_avant_double_ponctuation",
    "typo_guillemets_typographiques_doubles_fermants",
    // for discussion, see https://github.com/languagetooler-gmbh/languagetool-premium/issues/229:
    "nbsp_avant_deux_points",  // Useful only if we decide to have the rest of the non-breakable space rules.
    "nbsp_ajout_avant_double_ponctuation",  // Useful only if we decide to have the rest of the non-breakable space rules.
    "apostrophe_typographique_après_t",  // Not useful. While being the technically correct character, it does not matter much.
    "typo_tiret_début_ligne",  // Arguably the same as 50671 and 17342 ; the french special character for lists is a 'tiret cadratin' ; so it should be that instead of a dash. Having it count as a mistake is giving access to the otherwise unaccessible special character. However, lists are a common occurrence, and the special character does not make a real difference. Not really useful but debatable
    "typo_guillemets_typographiques_simples_fermants",
    "typo_apostrophe_incorrecte",
    "unit_nbsp_avant_unités1",
    "unit_nbsp_avant_unités2",
    "unit_nbsp_avant_unités3",
    "nbsp_après_double_ponctuation",
    "typo_guillemets_typographiques_simples_ouvrants",
    "num_grand_nombre_avec_espaces",
    "num_grand_nombre_soudé",
    "typo_parenthèse_ouvrante_collée",  // we already have UNPAIRED_BRACKETS
    "nbsp_après_chevrons_ouvrants",
    "nbsp_avant_chevrons_fermants",
    "nbsp_avant_chevrons_fermants1",
    "nbsp_avant_chevrons_fermants2",
    "typo_points_suspension1",
    "typo_points_suspension2",
    "typo_points_suspension3",
    "typo_tiret_incise", // picky
    "esp_avant_après_tiret", // picky
    "nbsp_après_tiret1", // picky
    "nbsp_après_tiret2", // picky
    "esp_mélangés1", // picky
    "esp_mélangés2", // picky
    "tab_début_ligne",
    "esp_milieu_ligne", // we already have WHITESPACE_RULE
    "typo_ponctuation_superflue1", // false alarm (1, 2, ...)
    "esp_insécables_multiples", // temp disabled, unsure how this works with the browser add-ons
    "typo_espace_manquant_après1", // false alarm in urls (e.g. '&rk=...')
    "typo_espace_manquant_après2", // false alarm in urls (e.g. '&rk=...')
    "typo_espace_manquant_après3", // false alarm in file names (e.g. 'La teaser.zip')
    "typo_tiret_incise2",  // picky
    "g1__eleu_élisions_manquantes__b1_a1_1" // picky
  ));

  public GrammalecteRule(ResourceBundle messages, GlobalConfig globalConfig) {
    super(messages, new RemoteRuleConfig(
      RULE_ID, globalConfig.getGrammalecteServer(), null,
      0, TIMEOUT_MILLIS * 2L, 0f,
      FALL, DOWN_INTERVAL_MILLISECONDS,
      Collections.emptyMap()), false);
    //addExamplePair(Example.wrong(""),
    //               Example.fixed(""));
    this.globalConfig = globalConfig;
  }

  class GrammalecteRequest extends RemoteRequest {
    private final List<AnalyzedSentence> sentences;
    private final String text;

    GrammalecteRequest(List<AnalyzedSentence> sentences) {
      this.sentences = sentences;
      text = sentences.stream().map(AnalyzedSentence::getText).collect(Collectors.joining());
    }
  }

  @Override
  protected RemoteRequest prepareRequest(List<AnalyzedSentence> sentences, AnnotatedText annotatedText) {
    return new GrammalecteRequest(sentences);
  }

  @Override
  protected Callable<RemoteRuleResult> executeRequest(RemoteRequest request) {
    GrammalecteRequest req = (GrammalecteRequest) request;
    return () -> {
      HttpURLConnection huc = null;
      URL serverUrl = null;
      try {
        serverUrl = new URL(globalConfig.getGrammalecteServer());
        huc = (HttpURLConnection) serverUrl.openConnection();
        HttpURLConnection.setFollowRedirects(false);
        huc.setConnectTimeout(TIMEOUT_MILLIS);
        huc.setReadTimeout(TIMEOUT_MILLIS * 2);
        if (globalConfig.getGrammalecteUser() != null && globalConfig.getGrammalectePassword() != null) {
          String authString = globalConfig.getGrammalecteUser() + ":" + globalConfig.getGrammalectePassword();
          String encoded = Base64.getEncoder().encodeToString(authString.getBytes());
          huc.setRequestProperty("Authorization", "Basic " + encoded);
        }
        huc.setRequestMethod("POST");
        huc.setDoOutput(true);
        huc.connect();
        try (DataOutputStream wr = new DataOutputStream(huc.getOutputStream())) {
          String urlParameters = "text=" + encode(req.text);
          byte[] postData = urlParameters.getBytes(StandardCharsets.UTF_8);
          wr.write(postData);
        }
        InputStream input = huc.getInputStream();
        List<RuleMatch> ruleMatches = parseJson(input, req.sentences);
        return new RemoteRuleResult(true, true, ruleMatches);
      } catch (Exception e) {
        // These are issue that can be request-specific, like wrong parameters. We don't throw an
        // exception, as the calling code would otherwise assume this is a persistent error:
        logger.warn("Warn: Failed to query Grammalecte server at " + serverUrl + ": " + e.getClass() + ": " + e.getMessage());
        e.printStackTrace();
      } finally {
        if (huc != null) {
          huc.disconnect();
        }
      }
      return new RemoteRuleResult(true, false, Collections.emptyList());
    };
  }

  @Override
  protected RemoteRuleResult fallbackResults(RemoteRequest request) {
    return new RemoteRuleResult(false, false, Collections.emptyList());
  }

  @Override
  public String getId() {
    return RULE_ID;
  }

  @Override
  public String getDescription() {
    return "Returns matches of a local Grammalecte server";
  }

  @NotNull
  private List<RuleMatch> parseJson(InputStream inputStream, List<AnalyzedSentence> sentences) throws IOException {
    Map map = mapper.readValue(inputStream, Map.class);
    List matches = (ArrayList) map.get("data");
    List<RuleMatch> result = new ArrayList<>();
    int[] offsets = new int[sentences.size()];
    int offset = 0;
    for (int i = 0; i < sentences.size(); i++) {
      offsets[i] = offset;
      // should this use getCorrectedTextLength()? probably not
      offset += sentences.get(i).getText().length();
      //offset += sentences.get(i).getCorrectedTextLength()
    }
    for (Object match : matches) {
      List<RuleMatch> remoteMatches = getMatches((Map<String, Object>)match, sentences, offsets);
      result.addAll(remoteMatches);
    }
    return result;
  }

  protected String encode(String plainText) throws UnsupportedEncodingException {
    return URLEncoder.encode(plainText, StandardCharsets.UTF_8.name());
  }

  @NotNull
  private List<RuleMatch> getMatches(Map<String, Object> match, List<AnalyzedSentence> sentences, int[] offsets) {
    List<RuleMatch> remoteMatches = new ArrayList<>();
    ArrayList matches = (ArrayList) match.get("lGrammarErrors");
    for (Object o : matches) {
      Map pairs = (Map) o;
      String id = (String)pairs.get("sRuleId");
      if (ignoreRules.contains(id)) {
        continue;
      }
      // offsets are for whole text, but we need offsets relative to the current sentence
      int absoluteOffset = (int) pairs.get("nStart");
      int absoluteEndOffset = (int)pairs.get("nEnd");
      int shift = 0;
      AnalyzedSentence sentence = null;
      for (int i = 0; i < offsets.length; i++) {
        int newShift = shift + offsets[i];
        sentence = sentences.get(i);
        if (absoluteOffset - newShift < 0) {
          break;
        }
        shift = newShift;
      }
      int offset = absoluteOffset - shift, endOffset = absoluteEndOffset - shift;
      String message = pairs.get("sMessage").toString();
      GrammalecteInternalRule rule = new GrammalecteInternalRule("grammalecte_" + id, message);
      RuleMatch extMatch = new RuleMatch(rule, sentence, offset, endOffset, message);
      List<String> suggestions = (List<String>) pairs.get("aSuggestions");
      //SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss ZZZZ");
      //System.out.println(sdf.format(new Date()) + " Grammalecte: " + pairs.get("sRuleId") + "; " + pairs.get("sMessage") + " => " + suggestions);
      extMatch.setSuggestedReplacements(suggestions);
      remoteMatches.add(extMatch);
    }
    return remoteMatches;
  }

  static class GrammalecteInternalRule extends Rule {
    private String id;
    private String desc;

    GrammalecteInternalRule(String id, String desc) {
      this.id = id;
      this.desc = desc;
    }

    @Override
    public String getId() {
      return id;
    }

    @Override
    public String getDescription() {
      return desc;
    }

    @Override
    public RuleMatch[] match(AnalyzedSentence sentence) {
      throw new RuntimeException("Not implemented");
    }
  }

}
