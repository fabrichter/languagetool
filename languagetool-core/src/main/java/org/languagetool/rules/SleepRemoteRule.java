/*
 *  LanguageTool, a natural language style checker
 *  * Copyright (C) 2021 Fabian Richter
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

package org.languagetool.rules;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeoutException;

import org.languagetool.AnalyzedSentence;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.rules.RemoteRule;
import org.languagetool.rules.RemoteRuleResult;

public class SleepRemoteRule extends RemoteRule {

    public static final String RULE_ID = "SLEEP_REMOTE_RULE";
    private final long sleep;

    public SleepRemoteRule(Language lang, RemoteRuleConfig config) {
      super(lang, JLanguageTool.getMessageBundle(lang), config, false);
      sleep = Long.parseLong(config.getOptions().get("sleepTime"));
    }

    class SleepRemoteRequest extends RemoteRequest {
      private final List<AnalyzedSentence> sentences;

      SleepRemoteRequest(List<AnalyzedSentence> sentences) {
        this.sentences = sentences;
      }
    }

    @Override
    protected RemoteRequest prepareRequest(List<AnalyzedSentence> sentences, Long textSessionId) {
      return new SleepRemoteRequest(sentences);
    }

    @Override
    protected Callable<RemoteRuleResult> executeRequest(RemoteRequest request, long timeoutMilliseconds) throws TimeoutException {
      return () -> {
        SleepRemoteRequest req = (SleepRemoteRequest) request;
        long end = System.currentTimeMillis() + sleep;
        while (System.currentTimeMillis() < end) {
          try {
            Thread.sleep(Math.min(end - System.currentTimeMillis(), 1));
          } catch (InterruptedException ignored) {
          }
        }
        return new RemoteRuleResult(true, true, Collections.emptyList(), req.sentences);
      };
    }

    @Override
    protected RemoteRuleResult fallbackResults(RemoteRequest request) {
      SleepRemoteRequest req = (SleepRemoteRequest) request;
      return new RemoteRuleResult(false, false, Collections.emptyList(), req.sentences);
    }

    @Override
    public String getDescription() {
      return "Sleeps to test performance impact of timeouts";
    }

    @Override
    public String getId() {
      return RULE_ID;
    }
}
