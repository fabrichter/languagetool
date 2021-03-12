# Benchmarks for LanguageTool

This module contains some microbenchmarks for LanguageTool using [JMH](https://github.com/openjdk/jmh).

## Overview

Right now these benchmarks exist:

+ *RuleBenchmark*

    Tests the performance of Rule.match() on a corpus of text, i.e. without initalization or preprocessing (tokenization, tagging, ...)

+ *RuleInitializationBenchmark*

    Tests how long rule initialization takes. 
    At the moment, it only runs the constructor once. To account for caching and lazy initialization,
    subsequent runs of the constructor and a call to match() should be measured as well

## Running the benchmarks

``` sh
java -jar languagetool-benchmark/target/benchmarks.jar
# TODO: document configuration options, running via main()
```


