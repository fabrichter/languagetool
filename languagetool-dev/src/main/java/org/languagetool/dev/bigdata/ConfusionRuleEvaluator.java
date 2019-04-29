/* LanguageTool, a natural language style checker 
 * Copyright (C) 2014 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool.dev.bigdata;

import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.languagetool.AnalyzedSentence;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.chunking.Chunker;
import org.languagetool.dev.dumpcheck.MixingSentenceSource;
import org.languagetool.dev.dumpcheck.PlainTextSentenceSource;
import org.languagetool.dev.dumpcheck.Sentence;
import org.languagetool.dev.dumpcheck.SentenceSource;
import org.languagetool.dev.eval.FMeasure;
import org.languagetool.language.English;
import org.languagetool.languagemodel.LanguageModel;
import org.languagetool.languagemodel.LuceneLanguageModel;
import org.languagetool.languagemodel.bert.BertTokenClassifier;
import org.languagetool.languagemodel.bert.grpc.BertLmProto;
import org.languagetool.rules.ConfusionSet;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.ngrams.ConfusionProbabilityRule;
import org.languagetool.tagging.Tagger;
import org.languagetool.tagging.xx.DemoTagger;
import org.languagetool.tools.StringTools;
import org.languagetool.tools.ml.LogisticRegressionClassifier;
import org.languagetool.tools.ml.StatsTools;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Locale.ENGLISH;
import static java.util.stream.Collectors.toList;

/**
 * Loads sentences with a homophone (e.g. there/their) from Wikipedia or confusion set files
 * and evaluates EnglishConfusionProbabilityRule with them.
 * 
 * @see AutomaticConfusionRuleEvaluator
 * @since 3.0
 * @author Daniel Naber 
 */
class ConfusionRuleEvaluator {

  private static final boolean CASE_SENSITIVE = false;
  private static final List<Long> EVAL_FACTORS = Arrays.asList(10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L, 10_000_000L);
  private static final int MAX_SENTENCES = 1000;

  private final Language language;
  private final boolean caseSensitive;
  private final ConfusionProbabilityRule rule;
  private final Map<Long, RuleEvalValues> evalValues = new HashMap<>();
  private final LogisticRegressionClassifier classifier = new LogisticRegressionClassifier("127.0.0.1", 50051);
  private final BertTokenClassifier tokenClassifier = new BertTokenClassifier("127.0.0.1", 50051);

  private boolean verbose = true;
  public static final float TRAIN_TEST_SPLIT = 0.7f;
  protected Random rng = new Random(0);

  ConfusionRuleEvaluator(Language language, LanguageModel languageModel, boolean caseSensitive) {
    this.language = language;
    this.caseSensitive = caseSensitive;
    try {
      List<Rule> rules = language.getRelevantLanguageModelRules(JLanguageTool.getMessageBundle(), languageModel);
      if (rules == null) {
        throw new RuntimeException("Language " + language + " doesn't seem to support a language model");
      }
      ConfusionProbabilityRule foundRule = null;
      for (Rule rule : rules) {
        if (rule.getId().equals(ConfusionProbabilityRule.RULE_ID)) {
          foundRule = (ConfusionProbabilityRule)rule;
          break;
        }
      }
      if (foundRule == null) {
        throw new RuntimeException("Language " + language + " has no language model rule with id " + ConfusionProbabilityRule.RULE_ID);
      } else {
        this.rule = foundRule;
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
  
  void setVerboseMode(boolean verbose) {
    this.verbose = verbose;
  }

  Map<Long, RuleEvalResult> runWithFactors(List<String> inputsOrDir, String token, String homophoneToken, int maxSentences, List<Long> evalFactors) throws IOException {
    for (Long evalFactor : evalFactors) {
      evalValues.put(evalFactor, new RuleEvalValues());
    }
    List<Sentence> allTokenSentences = getRelevantSentences(inputsOrDir, token, maxSentences);
    // Load the sentences with a homophone and later replace it so we get error sentences:
    List<Sentence> allHomophoneSentences = getRelevantSentences(inputsOrDir, homophoneToken, maxSentences);
    //if (allTokenSentences.size() < 20 || allHomophoneSentences.size() < 20) {
    //  System.out.println("Skipping " + token + " / " + homophoneToken);
    //  return null;
    //}
    evaluate(allTokenSentences, true, token, homophoneToken, evalFactors);
    evaluate(allTokenSentences, false, homophoneToken, token, evalFactors);
    evaluate(allHomophoneSentences, false, token, homophoneToken, evalFactors);
    evaluate(allHomophoneSentences, true, homophoneToken, token, evalFactors);
    return printEvalResult(allTokenSentences, allHomophoneSentences, inputsOrDir, token, homophoneToken);
  }

  RuleEvalResult runWithLogisticRegression(List<String> inputsOrDir, String token, String homophoneToken, int maxSentences) throws IOException {
    List<Sentence> allTokenSentences = getRelevantSentences(inputsOrDir, token, maxSentences);
    // Load the sentences with a homophone and later replace it so we get error sentences:
    List<Sentence> allHomophoneSentences = getRelevantSentences(inputsOrDir, homophoneToken, maxSentences);
    //if (allTokenSentences.size() < 20 || allHomophoneSentences.size() < 20) {
    //  System.out.println("Skipping " + token + " / " + homophoneToken);
    //  return null;
    //}
    RuleEvalValues values = trainClassifier(allTokenSentences, allHomophoneSentences, token, homophoneToken);
    return computeEvalResult(allTokenSentences, allHomophoneSentences, token, homophoneToken, values, null);
  }


  @SuppressWarnings("ConstantConditions")
  private void evaluate(List<Sentence> sentences, boolean isCorrect, String token, String homophoneToken, List<Long> evalFactors) throws IOException {
    println("======================");
    printf("Starting evaluation on " + sentences.size() + " sentences with %s/%s:\n", token, homophoneToken);
    JLanguageTool lt = new JLanguageTool(language);
    List<Rule> allActiveRules = lt.getAllActiveRules();
    for (Rule activeRule : allActiveRules) {
      lt.disableRule(activeRule.getId());
    }
    for (Sentence sentence : sentences) {
      /*
       * evaluate pair foo/bar
       * test w/ sentences containing foo, rules should not trigger - isCorrect == true, token == foo, homophoneToken == bar
       * test w/ sentences containing foo, replacing foo w/ bar: rules should trigger, isCorrect == false, token == bar, homophoneToken == foo
       * vice versa for sentences containing bar
       *
       * token stays in input sentence (if isCorrect)
       * homophoneToken in sentence will get replaced by token (if !isCorrect)
       * -> homophone is never in final checked sentence
       */
      String textToken = isCorrect ? token : homophoneToken;
      String plainText = sentence.getText();
      String replacement = plainText.indexOf(textToken) == 0 ? StringTools.uppercaseFirstChar(token) : token;
      String replacedTokenSentence = isCorrect ? plainText : plainText.replaceFirst("(?i)\\b" + textToken + "\\b", replacement);
      AnalyzedSentence analyzedSentence = lt.getAnalyzedSentence(replacedTokenSentence);
      for (Long factor : evalFactors) {
        rule.setConfusionSet(new ConfusionSet(factor, homophoneToken, token));
        RuleMatch[] matches = rule.match(analyzedSentence);
        boolean consideredCorrect = matches.length == 0;
        String displayStr = plainText.replaceFirst("(?i)\\b" + textToken + "\\b", "**" + replacement + "**");
        if (consideredCorrect && isCorrect) {
          evalValues.get(factor).trueNegatives++;
        } else if (!consideredCorrect && isCorrect) {
          evalValues.get(factor).falsePositives++;
          println("false positive with factor " + factor + ": " + displayStr);
        } else if (consideredCorrect && !isCorrect) {
          //println("false negative: " + displayStr);
          evalValues.get(factor).falseNegatives++;
        } else {
          evalValues.get(factor).truePositives++;
          //System.out.println("true positive: " + displayStr);
        }
      }
    }
  }

  public void export(List<String> inputsOrDir, String token, String homophoneToken, int maxSentences, CSVPrinter trainFile, CSVPrinter testFile) throws IOException {
    List<Sentence> tokenSentences = getRelevantSentences(inputsOrDir, token, maxSentences);
    // Load the sentences with a homophone and later replace it so we get error sentences:
    List<Sentence> homophoneSentences = getRelevantSentences(inputsOrDir, homophoneToken, maxSentences);

    List<EvaluationSample> samples = getEvaluationSamples(tokenSentences, homophoneSentences, token, homophoneToken);
    Collections.shuffle(samples, rng);

    for (EvaluationSample sample : samples) {
      boolean train = rng.nextFloat() < TRAIN_TEST_SPLIT;
      if (train) {
        trainFile.printRecord(sample.sentence, sample.token, sample.target);
      } else {
        testFile.printRecord(sample.sentence, sample.token, sample.target);
      }
    }
  }

  static class EvaluationSample {
    String sentence;
    String token;
    String homophone;
    boolean target;
    double prediction;
  }

  private EvaluationSample createSample(String text, boolean isPositiveSample, String textToken, @NotNull String homophone) {
    EvaluationSample sample = new EvaluationSample();
    // positive class = error, so that:
    // precision = not labeling correct sentences as errors
    // recall = amount of errors caught
    sample.target = !isPositiveSample;
    if (isPositiveSample) {
      sample.sentence = text;
      sample.token = textToken;
      sample.homophone = homophone;
    } else {
      String replacement = text.indexOf(textToken) == 0 ? StringTools.uppercaseFirstChar(homophone) : homophone;
      sample.sentence = text.replaceFirst("(?i)\\b" + textToken + "\\b", Objects.requireNonNull(replacement));
      sample.token = replacement;
      sample.homophone = textToken;
    }
    return sample;
  }

  /**
   * Build samples out of sentences with respective tokens of confusion pair
   * Use real sentences and artificial errors were token has been replaced with the alternative from the confusion pair
   * Fetch probabilities for both alternatives and train a classifier to predict
   * @param tokenSentences
   * @param homophoneSentences
   * @param token
   * @param homophoneToken
   * @throws IOException
   */
  private RuleEvalValues trainClassifier(List<Sentence> tokenSentences, List<Sentence> homophoneSentences, String token, String homophoneToken) throws IOException {
    println("======================");
    printf("Starting evaluation on %s+%s sentences with %s/%s:\n",
      "" + tokenSentences.size(), "" + homophoneSentences.size(), token, homophoneToken);
    JLanguageTool lt = new JLanguageTool(language);
    List<Rule> allActiveRules = lt.getAllActiveRules();
    for (Rule activeRule : allActiveRules) {
      lt.disableRule(activeRule.getId());
    }
    List<EvaluationSample> samples = getEvaluationSamples(tokenSentences, homophoneSentences, token, homophoneToken);

    Collections.shuffle(samples, rng);

    List<EvaluationSample> evaluatedSamples = new ArrayList<>();
    List<LogisticRegressionClassifier.Sample> data = new ArrayList<>(samples.size());

    List<String> sentences = samples.stream().map(evaluationSample -> evaluationSample.sentence).collect(toList());
    List<String> tokens = samples.stream().map(evaluationSample -> evaluationSample.token).collect(toList());
    List<Boolean> labels = samples.stream().map(evaluationSample -> evaluationSample.target).collect(toList());
    List<Boolean> split = new ArrayList<>(labels.size());
    for (int i = 0; i < labels.size(); i++) {
      split.add(rng.nextFloat() < TRAIN_TEST_SPLIT);
    }

    BertLmProto.Model bertModel = tokenClassifier.train(sentences, tokens, labels, split);
    System.out.printf("Trained model '%s' with accuracy %f on pair %s; %s%n", bertModel.getId(), bertModel.getAccuracy(), token, homophoneToken);
    //BertLmProto.Model bertModel = BertLmProto.Model.newBuilder().setId("9c01b709-290e-424e-bc1b-6098c3a0f6a4").build();
    List<String> testSentences = new ArrayList<>();
    List<String> testTokens = new ArrayList<>();
    List<EvaluationSample> testSamples = new ArrayList<>();
    for (int i = 0; i < split.size(); i++) {
      if (!split.get(i)) {
        testSentences.add(sentences.get(i));
        testTokens.add(tokens.get(i));
        testSamples.add(samples.get(i));
      }
    }
    System.out.printf("Evaluating on %d sentences.%n", testSentences.size());
    List<List<Double>> bertPredictions = tokenClassifier.classify(bertModel, testSentences, testTokens);

    int numFeatures = -1;

    for (int i = 0; i < testSamples.size(); i++) {
      List<Double> probabilities = bertPredictions.get(i);
      if (!probabilities.isEmpty()) {
        if (numFeatures == -1) {
          numFeatures = probabilities.size();
        }
        if (probabilities.stream().anyMatch(p -> p.isInfinite() || p.isNaN())) {
          continue;
        }
        evaluatedSamples.add(testSamples.get(i));
        data.add(new LogisticRegressionClassifier.Sample(
          testSamples.get(i).target, probabilities));
      }
    }

    // TODO: make this a closure / configurable?
    /*
    for (EvaluationSample sample : samples) {
      AnalyzedSentence analyzedSentence = lt.getAnalyzedSentence(sample.sentence);

      List<Double> probs = rule.evaluateConfusionPair(analyzedSentence, sample.token, sample.homophone);
      if (probs == null) {
        continue; // token not found -> skips e.g. compounds or hyphenated words if rule would not match these
      }
      //if (correctionProb >= rule.getMinProb()) {
      data.add(new LogisticRegressionClassifier.Sample(sample.target, probs));
      evaluatedSamples.add(sample);
      //}
    }
     */
    //

    LogisticRegressionClassifier.Model model = classifier.train(data, numFeatures);

    RuleEvalValues result = new RuleEvalValues();
    List<Double> predictions = new ArrayList<>(evaluatedSamples.size());
    List<Boolean> targets = new ArrayList<>(evaluatedSamples.size());
    for (int i = 0; i < evaluatedSamples.size(); i++) {
      double prediction = model.predict(data.get(i).getFeatures());
      EvaluationSample sample = evaluatedSamples.get(i);
      sample.prediction = prediction;
      predictions.add(sample.prediction);
      targets.add(sample.target);
    }

    List<StatsTools.PRCurveEntry> prCurve = StatsTools.getInstance().computePRCurve(predictions, targets);
    // TODO: can curve be empty?
    double bestScore = 0.0;
    StatsTools.PRCurveEntry bestThreshold = prCurve.get(0);
    for (StatsTools.PRCurveEntry entry : prCurve) {
      double score = FMeasure.getFMeasure(entry.precision, entry.recall, 0.05);
      System.out.printf("Threshold: %f / F_0.05 = %f / p = %f / r = %f %n",
        entry.threshold, score, entry.precision, entry.recall);
      if (score > bestScore) {
        bestScore = score;
        bestThreshold = entry;
      }
    }
    System.out.printf("%nBest Threshold: %f / F_0.05 = %f / p = %f / r = %f %n",
      bestThreshold.threshold, bestScore, bestThreshold.precision, bestThreshold.recall);

    for (EvaluationSample sample : evaluatedSamples) {
      boolean binaryPrediction = sample.prediction >= bestThreshold.threshold;
      if (binaryPrediction && sample.target) {
        result.truePositives++;
      } else if (!binaryPrediction && sample.target) {
        result.falseNegatives++;
      } else if (binaryPrediction && !sample.target) {
        result.falsePositives++;
      } else {
        result.trueNegatives++;
      }
    }

    return result;
  }

  @NotNull
  private List<EvaluationSample> getEvaluationSamples(List<Sentence> tokenSentences, List<Sentence> homophoneSentences, String token, String homophoneToken) {
    List<EvaluationSample> samples = new ArrayList<>();
    for (Sentence sentence : tokenSentences) {
      samples.add(createSample(sentence.getText(), true, token, homophoneToken));
      samples.add(createSample(sentence.getText(), false, token, homophoneToken));
    }
    for (Sentence sentence : homophoneSentences) {
      samples.add(createSample(sentence.getText(), true, homophoneToken, token));
      samples.add(createSample(sentence.getText(), false, homophoneToken, token));
    }
    return samples;
  }


  private Map<Long, RuleEvalResult> printEvalResult(List<Sentence> allTokenSentences, List<Sentence> allHomophoneSentences, List<String> inputsOrDir,
                                                    String token, String homophoneToken) {
    Map<Long, RuleEvalResult> results = new HashMap<>();
    int sentences = allTokenSentences.size() + allHomophoneSentences.size();
    System.out.println("\nEvaluation results for " + token + "/" + homophoneToken
            + " with " + sentences + " sentences as of " + new Date() + ":");
    System.out.printf(ENGLISH, "Inputs:       %s\n", inputsOrDir);
    System.out.printf(ENGLISH, "Case sensit.: %s\n", caseSensitive);
    List<Long> factors = evalValues.keySet().stream().sorted().collect(toList());
    for (Long factor : factors) {
      RuleEvalValues evalValues = this.evalValues.get(factor);
      RuleEvalResult result = computeEvalResult(allTokenSentences, allHomophoneSentences,
        token, homophoneToken, evalValues, factor);
      results.put(factor, result);
    }
    return results;
  }

  private RuleEvalResult computeEvalResult(List<Sentence> allTokenSentences, List<Sentence> allHomophoneSentences, String token, String homophoneToken, RuleEvalValues evalValues, @Nullable Long factor) {
    float precision = (float)evalValues.truePositives / (evalValues.truePositives + evalValues.falsePositives);
    float recall = (float) evalValues.truePositives / (evalValues.truePositives + evalValues.falseNegatives);
    String date = new SimpleDateFormat("yyyy-MM-dd").format(new Date());
    String spaces = factor != null ? StringUtils.repeat(" ", 82-Long.toString(factor).length()) : "";
    String word1 = token;
    String word2 = homophoneToken;
    if (word1.compareTo(word2) > 0) {
      String temp = word1;
      word1 = word2;
      word2 = temp;
    }
    String factorString = factor != null ? String.format("%d; %s", factor, spaces) : "";
    String summary = String.format(ENGLISH, "%s; %s; %s # p=%.3f, r=%.3f, %d+%d, %dgrams, %s",
            word1, word2, factorString, precision, recall, allTokenSentences.size(), allHomophoneSentences.size(), rule.getNGrams(), date);
    if (verbose) {
      System.out.println();
      String factorString2 = factor != null ? String.format("Factor: %d - ", factor) : "";
      System.out.printf(ENGLISH, "%s%d false positives, %d false negatives, %d true positives, %d true negatives\n",
                        factorString2, evalValues.falsePositives, evalValues.falseNegatives, evalValues.truePositives, evalValues.trueNegatives);
      //System.out.printf(ENGLISH, "Precision:    %.3f (%d false positives)\n", precision, evalValues.falsePositives);
      //System.out.printf(ENGLISH, "Recall:       %.3f (%d false negatives)\n", recall, evalValues.falseNegatives);
      //double fMeasure = FMeasure.getWeightedFMeasure(precision, recall);
      //System.out.printf(ENGLISH, "F-measure:    %.3f (beta=0.5)\n", fMeasure);
      //System.out.printf(ENGLISH, "Good Matches: %d (true positives)\n", evalValues.truePositives);
      //System.out.printf(ENGLISH, "All matches:  %d\n", evalValues.truePositives + evalValues.falsePositives);
      System.out.printf(summary + "\n");
    }
    return new RuleEvalResult(summary, precision, recall);
  }

  private List<Sentence> getRelevantSentences(List<String> inputs, String token, int maxSentences) throws IOException {
    List<Sentence> sentences = new ArrayList<>();
    for (String input : inputs) {
      if (new File(input).isDirectory()) {
        File file = new File(input, token + ".txt");
        if (!file.exists()) {
          throw new RuntimeException("File with example sentences not found: " + file);
        }
        try (FileInputStream fis = new FileInputStream(file)) {
          SentenceSource sentenceSource = new PlainTextSentenceSource(fis, language);
          sentences = getSentencesFromSource(inputs, token, maxSentences, sentenceSource);
        }
      } else {
        SentenceSource sentenceSource = MixingSentenceSource.create(inputs, language);
        sentences = getSentencesFromSource(inputs, token, maxSentences, sentenceSource);
      }
    }
    return sentences;
  }

  private List<Sentence> getSentencesFromSource(List<String> inputs, String token, int maxSentences, SentenceSource sentenceSource) {
    List<Sentence> sentences = new ArrayList<>();
    Pattern pattern = Pattern.compile(".*\\b" + (caseSensitive ? token : token.toLowerCase()) + "\\b.*");
    while (sentenceSource.hasNext()) {
      Sentence sentence = sentenceSource.next();
      String sentenceText = caseSensitive ? sentence.getText() : sentence.getText().toLowerCase();
      Matcher matcher = pattern.matcher(sentenceText);
      if (matcher.matches()) {
        sentences.add(sentence);
        if (sentences.size() % 250 == 0) {
          println("Loaded sentence " + sentences.size() + " with '" + token + "' from " + inputs);
        }
        if (sentences.size() >= maxSentences) {
          break;
        }
      }
    }
    println("Loaded " + sentences.size() + " sentences with '" + token + "' from " + inputs);
    return sentences;
  }

  private void println(String msg) {
    if (verbose) {
      System.out.println(msg);
    }
  }

  private void printf(String msg, String... args) {
    if (verbose) {
      System.out.printf(msg, args);
    }
  }

  public static void main(String[] args) throws IOException {
    if (args.length < 5 || args.length > 6) {
      System.err.println("Usage: " + ConfusionRuleEvaluator.class.getSimpleName()
              + " <token> <homophoneToken> <langCode> <languageModelTopDir> <wikipediaXml|tatoebaFile|plainTextFile|dir>...");
      System.err.println("   <languageModelTopDir> is a directory with sub-directories like 'en' which then again contain '1grams',");
      System.err.println("                      '2grams', and '3grams' sub directories with Lucene indexes");
      System.err.println("                      See http://wiki.languagetool.org/finding-errors-using-n-gram-data");
      System.err.println("   <wikipediaXml|tatoebaFile|plainTextFile|dir> either a Wikipedia XML dump, or a Tatoeba file, or");
      System.err.println("                      a plain text file with one sentence per line, or a directory with");
      System.err.println("                      example sentences (where <word>.txt contains only the sentences for <word>).");
      System.err.println("                      You can specify both a Wikipedia file and a Tatoeba file.");
      System.exit(1);
    }
    long startTime = System.currentTimeMillis();
    String token = args[0];
    String homophoneToken = args[1];
    String langCode = args[2];
    Language lang;
    if ("en".equals(langCode)) {
      lang = new EnglishLight();
    } else {
      lang = Languages.getLanguageForShortCode(langCode);
    }
    LanguageModel languageModel = new LuceneLanguageModel(new File(args[3], lang.getShortCode()));
    //LanguageModel languageModel = new BerkeleyRawLanguageModel(new File("/media/Data/berkeleylm/google_books_binaries/ger.blm.gz"));
    //LanguageModel languageModel = new BerkeleyLanguageModel(new File("/media/Data/berkeleylm/google_books_binaries/ger.blm.gz"));
    List<String> inputsFiles = new ArrayList<>();
    inputsFiles.add(args[4]);
    if (args.length >= 6) {
      inputsFiles.add(args[5]);
    }
    ConfusionRuleEvaluator generator = new ConfusionRuleEvaluator(lang, languageModel, CASE_SENSITIVE);
    generator.runWithFactors(inputsFiles, token, homophoneToken, MAX_SENTENCES, EVAL_FACTORS);
    long endTime = System.currentTimeMillis();
    System.out.println("\nTime: " + (endTime-startTime)+"ms");
  }

  // faster version of English as it uses no chunking:
  static class EnglishLight extends English {

    private DemoTagger tagger;

    @Override
    public String getName() {
      return "English Light";
    }

    @Override
    public Tagger getTagger() {
      if (tagger == null) {
        tagger = new DemoTagger();
      }
      return tagger;
    }

    @Override
    public Chunker getChunker() {
      return null;
    }
  }

}
