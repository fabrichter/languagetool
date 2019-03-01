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

package org.languagetool.rules.spelling;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.languagetool.*;
import org.languagetool.databroker.ResourceDataBroker;
import org.languagetool.languagemodel.LanguageModel;
import org.languagetool.rules.ExtendedRuleMatch;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.morfologik.suggestions_ordering.SuggestionsOrderer;
import org.languagetool.rules.spelling.morfologik.suggestions_ordering.SuggestionsOrdererFeatureExtractor;
import org.languagetool.rules.spelling.morfologik.suggestions_ordering.SuggestionsRanker;
import org.languagetool.rules.spelling.symspell.implementation.SuggestItem;
import org.languagetool.rules.spelling.symspell.implementation.SuggestionStage;
import org.languagetool.rules.spelling.symspell.implementation.SymSpell;

import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SymSpellRule extends SpellingCheckRule {
  private static final LoadingCache<Language, SymSpell> spellerCache = CacheBuilder.newBuilder()
    .expireAfterAccess(10, TimeUnit.MINUTES)
    .build(new CacheLoader<Language, SymSpell>() {
      @Override
      public SymSpell load(Language lang) {
        return initDefaultDictSpeller(lang);
      }
    });

  private static final LoadingCache<Language, Set<String>> ignoredWordsCache = CacheBuilder.newBuilder()
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .build(new CacheLoader<Language, Set<String>>() {
      @Override
      public Set<String> load(Language lang) throws Exception {
        return getWordList(lang, "ignore.txt");
      }
    });

  @NotNull
  private static Set<String> getWordList(Language lang, String file) {
    String base = getSpellingDictBaseDir(lang);
    List<String> paths = Collections.singletonList(base + file);
    Set<String> words = new HashSet<>();
    forEachLineInResources(paths, words::add);
    return Collections.unmodifiableSet(words);
  }

  private static final LoadingCache<Language, Set<String>> prohibitedWordsCache = CacheBuilder.newBuilder()
    .expireAfterAccess(30, TimeUnit.MINUTES)
    .build(new CacheLoader<Language, Set<String>>() {
      @Override
      public Set<String> load(Language lang) throws Exception {
        return getWordList(lang, "probibit.txt");
      }
    });

  protected final SymSpell defaultDictSpeller;
  protected final SymSpell userDictSpeller;

  private int editDistance = 3;
  private SymSpell.Verbosity verbosity = SymSpell.Verbosity.Closest;
  private SuggestionsOrderer orderer = null;

  protected static String getSpellingDictBaseDir(Language lang) {
    return lang.getShortCode() + "/hunspell/";
  }

  private static void forEachLineInResources(List<String> resources, Consumer<String> function) {
    ResourceDataBroker broker = JLanguageTool.getDataBroker();
    for (String resource : resources) {
      if (broker.resourceExists(resource)) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
          broker.getFromResourceDirAsStream(resource)))) {
          String line;
          while ((line = reader.readLine()) != null) {
            function.accept(line);
          }
        } catch (IOException e) {
          throw new RuntimeException("Could not read resource " + resource, e);
        }
      }
    }

  }

  /**
   *
   * @param config
   * @return Spell checker using users personal dictionary, or null if no custom speller is needed
   */
  @Nullable
  protected static SymSpell initUserDictSpeller(UserConfig config) {
    if (config != null && config.getAcceptedWords() != null && !config.getAcceptedWords().isEmpty()) {
      List<String> dict = config.getAcceptedWords();
      SymSpell speller = new SymSpell(0, 3, -1, 0);
      SuggestionStage stage = new SuggestionStage(dict.size());
      dict.forEach(word -> {
        speller.createDictionaryEntry(word, 1, stage);
      });
      speller.commitStaged(stage);
      return speller;
    } else {
      return null;
    }
  }

  protected static SymSpell initDefaultDictSpeller(Language lang) {
    SymSpell speller = new SymSpell(100000, 3, -1, 0);
    System.out.println("Initalizing symspell");
    Set<String> prohibitedWords = prohibitedWordsCache.getUnchecked(lang);
    long startTime = System.currentTimeMillis();

    String base = getSpellingDictBaseDir(lang);
    List<String> additional = Arrays.asList(base + "spelling.txt",
      base + "spelling_" + lang.getShortCodeWithCountryAndVariant() + ".txt");
    List<String> dict = Collections.singletonList(
      base + lang.getShortCodeWithCountryAndVariant().replaceFirst("-", "_") + ".dic");


    SuggestionStage stage = new SuggestionStage(100000);
    forEachLineInResources(additional, word -> {
      if (!prohibitedWords.contains(word)) {
        speller.createDictionaryEntry(word, 1, stage);
      }
    });
    AtomicInteger dictWords = new AtomicInteger(0);
    forEachLineInResources(dict, line -> {
      int split = line.lastIndexOf("+");
      if (split == -1 || line.length() <= split + 1) {
        throw new RuntimeException(String.format("Could not parse frequency dictionary line '%s'.", line));
      }
      String word = line.substring(0, split);
      char freqClass = line.charAt(split + 1);
      int freq = 1 + ((int) freqClass - (int) 'A'); // A - least frequent, Z - most frequent
      // exact frequencies don't matter, only used as tiebreaker for sorting

      if (!prohibitedWords.contains(word)) {
        speller.createDictionaryEntry(word, freq, stage);
        dictWords.incrementAndGet();
      }
    });
    System.out.printf("Loaded %d words from dictionary.%n", dictWords.intValue());
    speller.commitStaged(stage);
    long delta = System.currentTimeMillis() - startTime;
    System.out.printf("Reading dictionaries took %f seconds.%n", (float) delta / 1000.0);
    return speller;
  }

  private void initParameters() {
    if (SuggestionsChanges.getInstance() != null &&
      SuggestionsChanges.getInstance().getCurrentExperiment() != null) {
      if (SuggestionsChanges.getInstance().getCurrentExperiment().parameters.get("candidates") != null) {
        String candidatesParam = (String) SuggestionsChanges.getInstance().getCurrentExperiment()
          .parameters.get("candidates");
        verbosity = SymSpell.Verbosity.valueOf(candidatesParam);
      }
      if (SuggestionsChanges.getInstance().getCurrentExperiment().parameters.get("editDistance") != null) {
        editDistance = (Integer) SuggestionsChanges.getInstance().getCurrentExperiment()
          .parameters.get("editDistance");
      }
      if (SuggestionsChanges.isRunningExperiment("SymSpell+NewSuggestionsOrderer")) {
        orderer = new SuggestionsOrdererFeatureExtractor(language, languageModel);
      }
    }

  }

  public SymSpellRule(ResourceBundle messages, Language language, UserConfig userConfig) {
    this(messages, language, userConfig, Collections.emptyList());
  }

  public SymSpellRule(ResourceBundle messages, Language language, UserConfig userConfig, List<Language> altLanguages) {
    this(messages, language, userConfig, altLanguages, null);
  }

  public SymSpellRule(ResourceBundle messages, Language language, UserConfig userConfig, List<Language> altLanguages, @Nullable LanguageModel languageModel) {
    super(messages, language, userConfig, altLanguages, languageModel);
    initParameters();
    defaultDictSpeller = spellerCache.getUnchecked(language);
    userDictSpeller = initUserDictSpeller(userConfig);
  }

  @Override
  public String getId() {
    return "SYMSPELL_RULE";
  }

  @Override
  public String getDescription() {
    return "Spell checking rule using SymSpell algorithm";
  }

  @Override
  public RuleMatch[] match(AnalyzedSentence sentence) throws IOException {
    List<RuleMatch> matches = new ArrayList<>();
    Set<String> ignoredWords = ignoredWordsCache.getUnchecked(language);
    for (AnalyzedTokenReadings token : sentence.getTokensWithoutWhitespace()) {
      if (token.isSentenceStart() || token.isSentenceEnd() || token.isNonWord())
        continue;
      String word = token.getToken();
      if (ignoredWords.contains(word)) {
        continue;
      }
      List<String> candidates = filterCandidates(getSpellerMatches(word, defaultDictSpeller));
      List<String> userCandidates = getSpellerMatches(word, userDictSpeller);
      // TODO: messages
      if (candidates.size() + userCandidates.size() == 0) {
        RuleMatch match = new RuleMatch(this, sentence, token.getStartPos(), token.getEndPos(), "Misspelling or unknown word!");
        matches.add(match);
      } else if (!(candidates.size() > 0 && candidates.get(0).equals(word) ||
        userCandidates.size() > 0 && userCandidates.get(0).equals(word))) {
        RuleMatch match = new RuleMatch(this, sentence, token.getStartPos(), token.getEndPos(), "Misspelling!");

        if (orderer != null) {
          if (orderer instanceof SuggestionsRanker) {
            // don't rank words form user dictionary, assign confidence 0.0, but add at start
            // hard to ensure performance on unknown words
            SuggestionsRanker ranker = (SuggestionsRanker) orderer;
            Pair<List<String>, List<Float>> defaultSuggestions = ranker.rankSuggestions(
              candidates, word, sentence, token.getStartPos());
            ExtendedRuleMatch extendedRuleMatch = new ExtendedRuleMatch(match);

            if (userCandidates.size() == 0) {
              extendedRuleMatch.setAutoCorrect(ranker.shouldAutoCorrect(defaultSuggestions));
              extendedRuleMatch.setSuggestedReplacements(defaultSuggestions.getLeft());
              extendedRuleMatch.setSuggestionConfidence(defaultSuggestions.getRight());
            } else {
              List<String> combinedSuggestions = new ArrayList<>();
              List<Float> combinedConfidence = new ArrayList<>();
              combinedSuggestions.addAll(userCandidates);
              combinedSuggestions.addAll(defaultSuggestions.getLeft());
              combinedConfidence.addAll(Collections.nCopies(userCandidates.size(), 0f));
              combinedConfidence.addAll(defaultSuggestions.getRight());
              extendedRuleMatch.setSuggestedReplacements(combinedSuggestions);
              extendedRuleMatch.setSuggestionConfidence(combinedConfidence);
              // no auto correct when words from personal dictionaries are included
              extendedRuleMatch.setAutoCorrect(false);
            }

            match = extendedRuleMatch;
          } else if (orderer instanceof SuggestionsOrdererFeatureExtractor) {
            // computing features for words in user dictionaries is unproblematic
            SuggestionsOrdererFeatureExtractor featureExtractor = (SuggestionsOrdererFeatureExtractor) orderer;
            Pair<List<String>, List<SortedMap<String, Float>>> defaultSuggestions = featureExtractor.computeFeatures(
              candidates, word, sentence, token.getStartPos());
            Pair<List<String>, List<SortedMap<String, Float>>> userSuggestions = featureExtractor.computeFeatures(
              userCandidates, word, sentence, token.getStartPos());

            // prefer suggestions from user dictionary
            List<String> combinedSuggestions = new ArrayList<>();
            List<SortedMap<String, Float>> combinedFeatures = new ArrayList<>();
            combinedSuggestions.addAll(userSuggestions.getLeft());
            combinedSuggestions.addAll(defaultSuggestions.getLeft());
            combinedFeatures.addAll(userSuggestions.getRight());
            combinedFeatures.addAll(defaultSuggestions.getRight());

            ExtendedRuleMatch extendedRuleMatch = new ExtendedRuleMatch(match);
            extendedRuleMatch.setSuggestedReplacements(combinedSuggestions);
            extendedRuleMatch.setSuggestedReplacementsMetadata(combinedFeatures);
            match = extendedRuleMatch;
          } else {
            List<String> combinedSuggestions = new ArrayList<>();
            combinedSuggestions.addAll(orderer.orderSuggestionsUsingModel(userCandidates, word, sentence, token.getStartPos()));
            combinedSuggestions.addAll(orderer.orderSuggestionsUsingModel(candidates, word, sentence, token.getStartPos()));
            match.setSuggestedReplacements(combinedSuggestions);
          }
        } else {
          match.setSuggestedReplacements(candidates);
        }
        matches.add(match);
      }
    }
    return matches.toArray(new RuleMatch[0]);
  }

  @NotNull
  private List<String> filterCandidates(List<String> candidates) {
    Set<String> ignoredWords = ignoredWordsCache.getUnchecked(language);
    Set<String> prohibitedWords = prohibitedWordsCache.getUnchecked(language);
    return candidates.stream()
      .filter(c -> !ignoredWords.contains(c))
      .filter(c -> !prohibitedWords.contains(c))
      .collect(Collectors.toList());
  }

  @NotNull
  private List<String> getSpellerMatches(String word, SymSpell speller) {
    if (speller == null) {
      return Collections.emptyList();
    }
    List<SuggestItem> candidatesData = speller.lookup(word, verbosity, editDistance);
    return candidatesData.stream().map(candidate -> candidate.term).collect(Collectors.toList());
  }

  public static void main(String[] args) throws IOException, ClassNotFoundException {
    Language lang = Languages.getLanguageForShortCode("en-US");
    JLanguageTool lt = new JLanguageTool(lang);
    SymSpellRule r = new SymSpellRule(JLanguageTool.getMessageBundle(), lang, new UserConfig());

    SymSpell speller = r.defaultDictSpeller;
    ByteArrayOutputStream outBuffer = new ByteArrayOutputStream();
    long timeStart = System.currentTimeMillis();
    ObjectOutputStream serializer = new ObjectOutputStream(outBuffer);
    serializer.writeObject(speller);
    serializer.close();
    System.out.printf("Serializing took %d ms.%n", System.currentTimeMillis() - timeStart);
    ByteArrayInputStream inBuffer = new ByteArrayInputStream(outBuffer.toByteArray());
    timeStart = System.currentTimeMillis();
    ObjectInputStream deserializer = new ObjectInputStream(inBuffer);
    SymSpell speller2 = (SymSpell) deserializer.readObject();
    System.out.printf("Deserializing took %d ms.%n", System.currentTimeMillis() - timeStart);
    deserializer.close();

    System.out.println(speller.lookupCompound("This is a mistak."));
    System.out.println(speller2.lookupCompound("This is a mistak."));
  }
}
