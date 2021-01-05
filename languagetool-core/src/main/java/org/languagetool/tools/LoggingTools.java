/*
 *  LanguageTool, a natural language style checker
 *  * Copyright (C) 2020 Fabian Richter
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

package org.languagetool.tools;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.MapMessage;

import java.util.Objects;

public final class LoggingTools {
  private LoggingTools() {
  }

  public static void log(Logger logger, Level level, String message, String tag, Object... fields) {
   log(logger, level, null, message, tag, fields);
  }
  public static void log(Logger logger, Level level, Throwable throwable, String message, String tag, Object... fields) {
    if (fields.length % 2 != 0) {
      throw new IllegalArgumentException("Odd number of varargs (key-value pairs for fields) provided");
    }
    MapMessage msg = new MapMessage<>(2 + fields.length / 2);
    msg.put("_msg", message);
    msg.put("_tag", tag);
    for (int i = 0; i <= fields.length / 2; i+=2) {
      Object key = Objects.requireNonNull(fields[i],
        "Logged field names must be non-null");
      Object value = fields[i+1];
      msg.put(key.toString(), Objects.toString(value));
    }
    if (throwable != null) {
      logger.log(level, msg, throwable);
    } else {
      logger.log(level, msg);
    }
  }
  public static void debug(Logger logger, String message, String tag, Object... fields) {
    log(logger, Level.DEBUG, message, tag, fields);
  }
  public static void info(Logger logger, String message, String tag, Object... fields) {
    log(logger, Level.INFO, message, tag, fields);
  }
  public static void warn(Logger logger, String message, String tag, Object... fields) {
    log(logger, Level.WARN, message, tag, fields);
  }
  public static void warn(Logger logger, Throwable throwable, String message, String tag, Object... fields) {
    log(logger, Level.WARN, throwable, message, tag, fields);
  }
  public static void error(Logger logger, String message, String tag, Object... fields) {
    log(logger, Level.ERROR, message, tag, fields);
  }
  public static void error(Logger logger, Throwable throwable, String message, String tag, Object... fields) {
    log(logger, Level.ERROR, throwable, message, tag, fields);
  }
  public static void fatal(Logger logger, String message, String tag, Object... fields) {
    log(logger, Level.FATAL, message, tag, fields);
  }
  public static void fatal(Logger logger, Throwable throwable, String message, String tag, Object... fields) {
    log(logger, Level.FATAL, throwable, message, tag, fields);
  }
}
