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

import org.languagetool.dev.dumpcheck.MixingSentenceSource;
import org.languagetool.dev.dumpcheck.SentenceSource;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.io.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class BenchmarkText implements Serializable {

  final List<AnalyzedSentence> sentences;
  //final AnnotatedText annotatedText;

  BenchmarkText(Language language) {
    JLanguageTool lt = new JLanguageTool(language);

    String file = RuleBenchmark.Settings.dataFile(language.getShortCode());
    int numSentences = RuleBenchmark.Settings.numSentences();
    try {
      SentenceSource source = MixingSentenceSource.create(Collections.singletonList(file), language);

      StringBuilder corpus = new StringBuilder();
      for (int i = 0; i < numSentences; i++) {
        corpus.append(source.next().getText());
      }
      String text = corpus.toString();

      //annotatedText = new AnnotatedTextBuilder().addText(text).build();
      sentences = lt.analyzeText(text);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }
}

@State(Scope.Benchmark)
public class BenchmarkTexts {
  private Map<String, BenchmarkText> benchmarkData = new HashMap<>();

  private void saveToCache(File file) throws IOException {
    try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(file))) {
      out.writeObject(benchmarkData);
    }
  }

  @SuppressWarnings("unchecked")
  private void loadFromCache(File file) throws IOException, ClassNotFoundException {
    try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(file))) {
      benchmarkData = (Map<String, BenchmarkText>) in.readObject();
    }
  }

  @Setup
  public void setup() throws IOException, ClassNotFoundException {
    File cached = new File(RuleBenchmark.Settings.cacheFile());
    if (cached.exists()) {
      loadFromCache(cached);
    } else {
      List<Language> languages = Collections.singletonList(Languages.getLanguageForShortCode("en"));//Languages.get();
      for (Language language : languages) {
        benchmarkData.put(language.getShortCode(), new BenchmarkText(language));
      }
      saveToCache(cached);
    }
  }

  BenchmarkText get(Language language) {
    return benchmarkData.get(language.getShortCode());
  }
}
