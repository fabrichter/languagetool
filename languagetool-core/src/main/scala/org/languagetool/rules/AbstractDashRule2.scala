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

import java.io.IOException

import org.languagetool.{AnalyzedSentence, JLanguageTool, Language}
import resource._

import scala.annotation.tailrec
import scala.io.Source

/**
 * Another use of the compounds file -- check for compounds written with
 * dashes instead of hyphens (for example, Rabka — Zdrój).
 *
 * Optimised version, benchmark results:
 *
 * Benchmark               (languageCode)       (ruleID)  Mode  Cnt     Score     Error  Units
 * RuleBenchmark.testRule           en-US   EN_DASH_RULE  avgt    6  2176.915 ± 673.174  ms/op
 * RuleBenchmark.testRule           en-US  EN_DASH_RULE2  avgt    6     6.441 ±   0.878  ms/op
 *
 * @since 3.8
 */
object AbstractDashRule2 {
  private val dashChars = List("—", "–")
  private val dashes = dashChars ++ dashChars.flatMap(dash => s" $dash " :: s" $dash" :: s"$dash " :: Nil)
  private val hyphen = "-"

  protected def dashVariations(parts: List[String]): List[String] = {
    @tailrec
    def variations(parts: List[String], result: List[String]): List[String] = parts match {
      case Nil => result
      case part :: Nil => dashes.flatMap(dash => result.map(_ + dash + part))
      case part :: rest => variations(rest, dashes.flatMap(dash => result.map(_ + dash + part)))
    }
    variations(parts drop 1, parts take 1)
  }

  protected def replacements(line: String): Map[String, String] =
      // ignore comments
      if (line.isEmpty || line(0) == '#') Map.empty
      // skip non-hyphenated suggestions
      else if (line.endsWith("+")) Map.empty
      // TODO implement ?/$
      else {
        val word = if (line.endsWith("*")) line.dropRight(1) else line
        val parts = word.split(hyphen).toList
        val variations = dashVariations(parts)
        val replacements = variations zip List.fill(variations.size)(word)
        replacements.toMap
      }


  def loadCompoundFile(path: String, msg: String, lang: Language): Map[String, String] = {
      managed(JLanguageTool.getDataBroker.getFromResourceDirAsStream(path)).map(input => {
        val source = Source.fromInputStream(input, "utf-8")
        source.getLines().flatMap(replacements).toMap
      }).opt.get
  }

}

abstract class AbstractDashRule2(replacements: Map[String, String], message: String) extends Rule {

  override def getId = "DASH_RULE"

  override def estimateContextForSureMatch = 2

  private val searcher = StringSearcher(replacements.keys.toSeq)

  @throws[IOException]
  override def `match`(sentence: AnalyzedSentence): Array[RuleMatch] = {
    (for {
      hit <- searcher(sentence)
      matched = sentence.getText.substring(hit.start, hit.end)
      replacement = replacements(matched)
      ruleMatch = {
        val rm = new RuleMatch(this, sentence, hit.start, hit.end, message, replacement)
        rm.setSuggestedReplacement(replacement)
        rm
      }
    } yield ruleMatch).toArray
  }
}
