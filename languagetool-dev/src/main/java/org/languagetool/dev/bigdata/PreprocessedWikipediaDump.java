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

package org.languagetool.dev.bigdata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorInputStream;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.languagetool.Language;
import org.languagetool.Languages;
import org.languagetool.dev.dumpcheck.Sentence;
import org.languagetool.dev.dumpcheck.WikipediaSentenceSource;
import org.languagetool.tokenizers.Tokenizer;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class PreprocessedWikipediaDump extends Thread{

  private static final long WAIT = 5000L;

  private static final BlockingQueue<String> inputQueue = new LinkedBlockingQueue<>(100);
  private static final BlockingQueue<String> outputQueue = new LinkedBlockingQueue<>(100);
  private static BufferedWriter writer;
  private static Language language;
  private final Tokenizer wordTokenizer;
  private final Tokenizer sentenceTokenizer;
  private final ObjectMapper mapper;

  private PreprocessedWikipediaDump() {
    wordTokenizer = language.getWordTokenizer();
    sentenceTokenizer = language.getSentenceTokenizer();
    mapper = new ObjectMapper();
  }

  @Override
  public void run() {
    try {
      String input;
      while ((input = inputQueue.poll(WAIT, TimeUnit.MILLISECONDS)) != null) {
        List<String> sentences = sentenceTokenizer.tokenize(input);
        for (String sentence : sentences) {
          List<String> words = wordTokenizer.tokenize(sentence);
          String output = mapper.writeValueAsString(words);
          outputQueue.put(output);
        }
      }
    } catch (InterruptedException | JsonProcessingException error) {
      throw new RuntimeException(error);
    }
  }

  static class OutputWorker implements Runnable {

    @Override
    public void run() {
      String out;
      try {
        while ((out = outputQueue.poll(WAIT, TimeUnit.MILLISECONDS)) != null) {
          writer.append(out);
          writer.newLine();
        }
      } catch (InterruptedException | IOException error) {
        throw new RuntimeException(error);
      }
    }
  }

  static class InputWorker implements Runnable {
    private String dumpDir;

    InputWorker(String dir) {
      this.dumpDir = dir;
    }

    @Override
    public void run() {
      ObjectReader reader = new ObjectMapper().reader().forType(Map.class);

      try {
        Files.walk(Paths.get(dumpDir))
          .parallel()
          .unordered()
          .filter(Files::isRegularFile)
          .flatMap(path -> {
            try {
              return Files.lines(path);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          })
          .map(json -> {
            try {
              Map<Object, Object> map = reader.readValue(json);
              return (String) map.get("text");
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          })
          .filter(s -> s != null && !s.isEmpty())
          .forEach(sentence -> {
            try {
              inputQueue.put(sentence);
            } catch (InterruptedException e) {
              throw new RuntimeException(e);
            }
          });
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }



  public static void main(String[] args) throws IOException, InterruptedException, CompressorException {
    if (args.length != 3) {
      System.err.println("Usage: PreprocessedWikipediaDump wikipediaDump languageCode outputFile");
      System.exit(1);
    }

    String dump = args[0];
    String langCode = args[1];
    String output = args[2];

    InputStream input = null;

    try {
      language = Languages.getLanguageForShortCode(langCode);
      writer = new BufferedWriter(new FileWriter(output));

      if (Files.isRegularFile(Paths.get(dump))) {
        input = new BufferedInputStream(new FileInputStream(dump));

        if (!dump.endsWith(".xml")) {
          // assume dump is compressed
          input = new CompressorStreamFactory().createCompressorInputStream(input);
          System.out.println(((CompressorInputStream) input).getClass().getSimpleName());
        }
      }

      Thread outputWorker = new Thread(new OutputWorker());
      outputWorker.start();
      LinkedList<Thread> workers = new LinkedList<>();


      for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
        Thread worker = new PreprocessedWikipediaDump();
        worker.start();
        workers.add(worker);
      }

      workers.add(outputWorker);

      if (input != null) { // dump is (compressed / extracted) wikipedia dump (pages-articles-multistream)
        long numSentences = 0;
        WikipediaSentenceSource source = new WikipediaSentenceSource(input, language);

        while (source.hasNext()) {
          Sentence sentence = source.next();
          numSentences++;
          inputQueue.put(sentence.getText());
          if (numSentences % 1000 == 0) {
            System.out.printf("Processed %d sentences.%n", numSentences);
          }
        }
      } else { // dump is directory created by WikiExtractor, read using InputWorker
        Thread inputWorker = new Thread(new InputWorker(dump));
        inputWorker.start();
        workers.addFirst(inputWorker);
      }
      writer.flush();
      while (!workers.isEmpty()) {
        Thread worker = workers.pop();
        worker.join();
      }
    } finally {
      if (input != null) {
        input.close();
      }
      if (writer != null) {
        writer.close();
      }
    }
  }
}
