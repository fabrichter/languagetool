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

package org.languagetool.rules

import com.hankcs.algorithm.AhoCorasickDoubleArrayTrie
import org.languagetool.{AnalyzedSentence, AnalyzedTokenReadings}

import scala.collection.JavaConverters._
import scala.collection.immutable.TreeMap

/**
 * Efficient search for a fixed list of strings in sentences
 * Patterns are treated as consisting of (possibly multiple) tokens (as defined by the LT tokenizer)
 * that are expected to be separated by whitespace or the beginning/end of a sentence
 * returns matches as tuples of (start, end)
 * @param patterns strings to match
 */
class StringSearcher(patterns: Seq[String]) {
  import StringSearcher._

  private val searcher = ahoCorasickSearcher(patterns)

  sealed case class Hit(start: Int, end: Int)

  def apply(sentence: AnalyzedSentence): Seq[Hit] = {
    val candidates = searcher.parseText(sentence.getText)
    val boundaries = computeWordBoundaries(sentence)
    (for {
      candidate <- candidates.asScala
      if StringSearcher.isValidMatch(boundaries, candidate.begin, candidate.end)
    } yield Hit(candidate.begin, candidate.end)).toList
  }
}

object StringSearcher {

  def apply(patterns: Seq[String]): StringSearcher = new StringSearcher(patterns)

  def ahoCorasickSearcher(words: Seq[String]): AhoCorasickDoubleArrayTrie[String] = {
    val patternSearcher = new AhoCorasickDoubleArrayTrie[String]
    val entries = words.map(w => w -> w)
    val patterns = Map(entries:_*)
    patternSearcher.build(patterns.asJava)
    patternSearcher
  }

  def computeWordBoundaries(sentence: AnalyzedSentence): WordBoundaries = {
    val tokenStartEntries = sentence.getTokens map ((token: AnalyzedTokenReadings) => token.getStartPos -> token)
    val tokenStarts = TreeMap(tokenStartEntries:_*)
    val tokenEndEntries = sentence.getTokens map ((token: AnalyzedTokenReadings) => token.getEndPos -> token)
    val tokenEnds = TreeMap(tokenEndEntries:_*)
    (tokenStarts, tokenEnds)
  }

  type WordBoundaries = (TreeMap[Int, AnalyzedTokenReadings], TreeMap[Int, AnalyzedTokenReadings])

  def isValidMatch(sentence: AnalyzedSentence, matchStart: Int, matchEnd: Int): Boolean =
    isValidMatch(StringSearcher.computeWordBoundaries(sentence), matchStart, matchEnd)

  def isValidMatch(boundaries: WordBoundaries, matchStart: Int, matchEnd: Int): Boolean = {

    val (tokenStartPos, tokenEndPos) = boundaries

    // in 2.13
//    val tokenBefore = tokenEndPos maxBefore matchStart + 1
//    val tokenAfter = tokenStartPos minAfter matchEnd

    val tokenBefore = (tokenEndPos until matchStart+1).lastOption
    val tokenAfter = (tokenStartPos from matchEnd).headOption

    // test if there is a token sequence with start and end equal to the match
    val isCovered = tokenStartPos.contains(matchStart) && tokenEndPos.contains(matchEnd)

    def separate(tokenOrNone: Option[(Int, AnalyzedTokenReadings)]) = tokenOrNone match {
      case Some((_, token)) => token.isWhitespace || token.isNonWord
      case None => true // no token before/after = separate
    }
    isCovered && separate(tokenBefore) && separate(tokenAfter)
  }
}
