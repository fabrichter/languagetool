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

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.TimeUnit;

import org.jetbrains.annotations.Nullable;
import org.languagetool.languagemodel.LanguageModel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;


@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class RuleInitializationBenchmark {

  static {
    Languages.useLanguagesFromPackages();
  }

  static final class Settings {
    private Settings() {
    }

    static String resultFile(String language) {
      return Paths.get(Objects.requireNonNull(System.getProperty("benchmarkResults")),
        String.format("benchmark-%s.csv", language)).toString();
    }

    static String cacheFile() {
      return Paths.get(System.getProperty("benchmarkCache", "/tmp/"),
        "benchmark-data-cache.bin").toString();
    }

    static List<String> languages() {
      return Arrays.asList(Objects.requireNonNull(
        System.getProperty("benchmarkLanguages").split(",")));
    }

    @Nullable
    static String ngramDirectory() {
      return System.getProperty("ngramIndex");
    }
  }

  @Param({"xx"})
  public String languageCode;

  @Param({"org.languagetool.rules.DemoRule"})
  public String ruleClass;

  private Language language;
  private ResourceBundle messageBundle;
  private UserConfig user;
  private LanguageModel languageModel;

  private Constructor<?> ruleConstructor;
  private Object[] constructorArgs;

  @Setup
  public void getRuleConstructor() throws IOException, ClassNotFoundException  {
    language = Languages.getLanguageForShortCode(languageCode);
    messageBundle = JLanguageTool.getMessageBundle(language);
    user = new UserConfig();
    if (Settings.ngramDirectory() != null) {
      languageModel = language.getLanguageModel(new File(Settings.ngramDirectory()));
    }
    // try some common signatures to get a constructor we can use for the class
    final Class<?>[][] signatures = {
      { },
      { Language.class },
      { ResourceBundle.class },
      { ResourceBundle.class, Language.class },
      { ResourceBundle.class, Language.class, UserConfig.class },
      { ResourceBundle.class, LanguageModel.class, Language.class },
      { ResourceBundle.class, LanguageModel.class, UserConfig.class },
      { ResourceBundle.class, LanguageModel.class, Language.class, UserConfig.class }
    };
    final Object[][] arguments = {
      {},
      { language },
      { messageBundle },
      { messageBundle, language },
      { messageBundle, language, user },
      { messageBundle, languageModel, language },
      { messageBundle, languageModel, user },
      { messageBundle, languageModel, language, user }
    };
    assert(signatures.length == arguments.length);
    Class<?> rule = Class.forName(ruleClass);
    for (int i = 0; i < signatures.length; i++) {
      try {
        ruleConstructor = rule.getDeclaredConstructor(signatures[i]);
        constructorArgs = arguments[i];
        break;
      } catch(NoSuchMethodException e) {
      }
    }
  }

  @Benchmark
  public void testRule(Blackhole bh) throws Exception {
    if (ruleConstructor == null) {
      throw new NoSuchMethodException("Couldn't find any constructor to call");
    } else {
      bh.consume(ruleConstructor.newInstance(constructorArgs));
    }
  }

  public static void main(String[] args) throws IOException, RunnerException {
    List<String> languages = Settings.languages();

    for (String langCode : languages) {
      Language lang = Languages.getLanguageForShortCode(langCode);
      JLanguageTool lt = new JLanguageTool(lang);

      if (Settings.ngramDirectory() != null) {
        lt.activateLanguageModelRules(new File(Settings.ngramDirectory()));
      }

      String[] ruleClasses = lt.getAllActiveRules().stream()
        .map(r -> r.getClass().getName())
        .distinct()
        .toArray(String[]::new);
      Options opt = new OptionsBuilder()
        .resultFormat(ResultFormatType.CSV)
        .result(Settings.resultFile(langCode))
        .include(RuleInitializationBenchmark.class.getSimpleName())
        .param("languageCode", langCode)
        .param("ruleClass", ruleClasses)
        .build();

      new Runner(opt).run();
    }
  }

}
