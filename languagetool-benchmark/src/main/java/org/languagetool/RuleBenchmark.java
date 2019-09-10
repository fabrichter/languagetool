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

package org.languagetool;

import org.jetbrains.annotations.Nullable;
import org.languagetool.rules.Rule;
import org.languagetool.rules.TextLevelRule;
import org.languagetool.tools.Tools;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;


@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class RuleBenchmark {

  static final class Settings {
    private Settings() {
    }

    static String dataFile(String language) {
      return Paths.get(Objects.requireNonNull(System.getProperty("benchmarkData")),
        String.format("tatoeba-%s.txt", language)).toString();
    }

    static String resultFile(String language) {
      return Paths.get(Objects.requireNonNull(System.getProperty("benchmarkResults")),
        String.format("benchmark-%s.csv", language)).toString();
    }

    static String cacheFile() {
      return Paths.get(System.getProperty("benchmarkCache", "/tmp/"),
        "benchmark-data-cache.bin").toString();
    }

    @Nullable
    static String ngramDirectory() {
      return System.getProperty("ngramIndex");
    }

    static Integer numSentences() {
      return Integer.parseInt(System.getProperty("benchmarkLimit", "1000"));
    }
  }

  @Param({"xx"})
  public String languageCode;

  @Param({"FAKE_RULE"})
  public String ruleID;

  private Language language;
  @Setup
  public void getLanguage() {
    language = Languages.getLanguageForShortCode(languageCode);
  }

  private List<Rule> rules;
  @Setup
  public void getRule() throws IOException {
    JLanguageTool lt = new JLanguageTool(language);
    String ngrams = Settings.ngramDirectory();
    if (ngrams != null) {
      lt.activateLanguageModelRules(new File(ngrams));
    }
    Tools.selectRules(lt, Collections.emptyList(), Collections.singletonList(ruleID), true);
    rules = lt.getAllActiveRules(); // can end up being multiple rules for PatternRules
  }

  private List<AnalyzedSentence> sentences;
  @Setup
  public void getSentences(BenchmarkTexts data) {
    sentences = data.get(language).sentences;
  }

  //@AuxCounters(AuxCounters.Type.OPERATIONS)
  //@State(Scope.Thread)
  //public static class SentenceCount {
  //  public int sentences = 0;
  //}

  @Benchmark
  public void testRule(/*SentenceCount sentenceCount, */Blackhole bh) throws IOException {
    for (AnalyzedSentence s : sentences) {
      for (Rule r : rules) {
        bh.consume(r.match(s));
      }
    }
    //sentenceCount.sentences += sentences.size();
  }

  public static void main(String[] args) throws RunnerException {
    //List<Language> languages = Collections.singletonList(Languages.getLanguageForShortCode("en-US"));
    List<Language> languages = Languages.get();
    for (Language lang : languages) {
      JLanguageTool lt = new JLanguageTool(lang);
      String[] ruleIDs = lt.getAllActiveRules().stream()
        .filter(r -> !(r instanceof TextLevelRule))
        .map(Rule::getId)
        .distinct()
        .toArray(String[]::new);
      String langCode = lang.getShortCodeWithCountryAndVariant();
      Options opt = new OptionsBuilder()
        .resultFormat(ResultFormatType.CSV)
        .result(Settings.resultFile(langCode))
        .include(RuleBenchmark.class.getSimpleName())
        .param("languageCode", langCode)
        .param("ruleID", ruleIDs)
        .build();

      new Runner(opt).run();
    }
  }

}
