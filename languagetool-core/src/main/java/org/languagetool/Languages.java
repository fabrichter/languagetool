/* LanguageTool, a natural language style checker
 * Copyright (C) 2015 Daniel Naber (http://www.danielnaber.de)
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
package org.languagetool;

import org.jetbrains.annotations.Nullable;
import org.languagetool.language.Contributor;
import org.languagetool.noop.NoopLanguage;
import org.languagetool.rules.Rule;
import org.languagetool.rules.patterns.AbstractPatternRule;
import org.languagetool.rules.spelling.morfologik.MorfologikSpellerRule;
import org.languagetool.tools.StringTools;
import org.reflections.Reflections;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper methods to list all supported languages and to get language objects
 * by their name or language code etc.
 * @since 2.9
 */
public final class Languages {

  private static final Language NOOP_LANGUAGE = new NoopLanguage();

  private static final List<Language> languages = new ArrayList<>(getAllLanguages());
  private static final List<Language> dynLanguages = new ArrayList<>();
  
  private Languages() {
  }

  private static List<Language> getAllLanguages() {
    Reflections reflections = new Reflections("org.languagetool");
    Set<Class<? extends Language>> languages = reflections.getSubTypesOf(Language.class);

    return languages.stream().map(languageClass -> {
      try {
        return (Language) languageClass.getConstructor().newInstance();
      } catch (NoSuchMethodException e) {
        return null;
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }).filter(Objects::nonNull).collect(Collectors.toList());
  }

  /**
   * Adds the language to the list of supported languages.
   * By default, languages are loaded via reflection:
   * all subclasses of {@link Language} in {@link org.languagetool} and subpackages qualify
   * a public constructor without any parameters is required as well
   * This method can be used to add further languages
   * @since 4.7
   * @param language the language to add
   */
  @Experimental
  public static void addLanguage(Language language) {
    languages.add(language);
  }

  /**
   * @since 4.5
   */
  public static Language addLanguage(String name, String code, File dictPath) {
    Language lang = new Language() {
      @Override
      public String getShortCode() {
        return code;
      }
      @Override
      public String getName() {
        return name;
      }
      @Override
      public List<String> getRuleFileNames() {
        return Collections.emptyList();
      }
      @Override
      protected synchronized List<AbstractPatternRule> getPatternRules() {
        return Collections.emptyList();
      }
      @Override
      public String getCommonWordsPath() {
        return new File(dictPath.getParentFile(), "common_words.txt").getAbsolutePath();
      }
      @Override
      public String[] getCountries() { return new String[0]; }
      @Override
      public Contributor[] getMaintainers() { return new Contributor[0]; }
      @Override
      public boolean isSpellcheckOnlyLanguage() {
        return true;
      }
      @Override
      public List<Rule> getRelevantRules(ResourceBundle messages, UserConfig userConfig, Language motherTongue, List<Language> altLanguages) throws IOException {
        MorfologikSpellerRule r = new MorfologikSpellerRule(JLanguageTool.getMessageBundle(Languages.getLanguageForShortCode("en-US")), this) {
          @Override
          public String getFileName() {
            return dictPath.getAbsolutePath();
          }
          @Override
          public String getId() {
            return code.toUpperCase() + "_SPELLER_RULE";
          }
          @Override
          public String getSpellingFileName() {
            return null;
          }
        };
        return Collections.singletonList(r);
      }
    };
    dynLanguages.add(lang);
    return lang;
  }
  
  /**
   * By default, languages are loaded via reflection:
   * All subclasses of {@link Language} in {@link org.languagetool} and subpackages qualify.
   * A public constructor without any parameters is required as well.
   * @return an unmodifiable list of all supported languages
   */
  public static List<Language> get() {
    List<Language> result = new ArrayList<>();
    for (Language lang : getStaticAndDynamicLanguages()) {
      if (!"xx".equals(lang.getShortCode()) && !"zz".equals(lang.getShortCode())) {  // skip demo and noop language
        result.add(lang);
      }
    }
    return Collections.unmodifiableList(result);
  }

  /**
   * Like {@link #get()} but the list contains also LanguageTool's internal 'Demo'
   * language, if available. Only useful for tests.
   * @return an unmodifiable list
   */
  public static List<Language> getWithDemoLanguage() {
    return Collections.unmodifiableList(getStaticAndDynamicLanguages());
  }

  private static List<Language> getStaticAndDynamicLanguages() {
    return Stream.concat(languages.stream(), dynLanguages.stream()).collect(Collectors.toList());
  }

  /**
   * Get the Language object for the given language name.
   *
   * @param languageName e.g. <code>English</code> or <code>German</code> (case is significant)
   * @return a Language object or {@code null} if there is no such language
   */
  @Nullable
  public static Language getLanguageForName(String languageName) {
    for (Language element : getStaticAndDynamicLanguages()) {
      if (languageName.equals(element.getName())) {
        return element;
      }
    }
    return null;
  }

  /**
   * Get the Language object for the given language code.
   * @param langCode e.g. <code>en</code> or <code>en-US</code>
   * @throws IllegalArgumentException if the language is not supported or if the language code is invalid
   * @since 3.6
   */
  public static Language getLanguageForShortCode(String langCode) {
    return getLanguageForShortCode(langCode, Collections.emptyList());
  }
  
  /**
   * Get the Language object for the given language code.
   * @param langCode e.g. <code>en</code> or <code>en-US</code>
   * @param noopLanguageCodes list of languages that can be detected but that will not actually find any errors
   *                           (can be used so non-supported languages are not detected as some other language)
   * @throws IllegalArgumentException if the language is not supported or if the language code is invalid
   * @since 4.4
   */
  public static Language getLanguageForShortCode(String langCode, List<String> noopLanguageCodes) {
    Language language = getLanguageForShortCodeOrNull(langCode);
    if (language == null) {
      if (noopLanguageCodes.contains(langCode)) {
        return NOOP_LANGUAGE;
      } else {
        List<String> codes = new ArrayList<>();
        for (Language realLanguage : getStaticAndDynamicLanguages()) {
          codes.add(realLanguage.getShortCodeWithCountryAndVariant());
        }
        Collections.sort(codes);
        throw new IllegalArgumentException("'" + langCode + "' is not a language code known to LanguageTool." +
                " Supported language codes are: " + String.join(", ", codes) + ". See http://wiki.languagetool.org/java-api for details.");
      }
    }
    return language;
  }

  /**
   * Return whether a language with the given language code is supported. Which languages
   * are supported depends on the classpath when the {@code Language} object is initialized.
   * @param langCode e.g. {@code en} or {@code en-US}
   * @return true if the language is supported
   * @throws IllegalArgumentException in some cases of an invalid language code format
   */
  public static boolean isLanguageSupported(String langCode) {
    return getLanguageForShortCodeOrNull(langCode) != null;
  }

  /**
   * Get the best match for a locale, using American English as the final fallback if nothing
   * else fits. The returned language will be a country variant language (e.g. British English, not just English)
   * if available.
   * Note: this does not consider languages added dynamically
   * @throws RuntimeException if no language was found and American English as a fallback is not available
   */
  public static Language getLanguageForLocale(Locale locale) {
    Language language = getLanguageForLanguageNameAndCountry(locale);
    if (language != null) {
      return language;
    } else {
      Language firstFallbackLanguage = getLanguageForLanguageNameOnly(locale);
      if (firstFallbackLanguage != null) {
        return firstFallbackLanguage;
      }
    }
    for (Language aLanguage : languages) {
      if (aLanguage.getShortCodeWithCountryAndVariant().equals("en-US")) {
        return aLanguage;
      }
    }
    throw new RuntimeException("No appropriate language found, not even en-US. Supported languages: " + get());
  }

  @Nullable
  private static Language getLanguageForShortCodeOrNull(String langCode) {
    StringTools.assureSet(langCode, "langCode");
    Language result = null;
    if (langCode.contains("-x-")) {
      // e.g. "de-DE-x-simple-language"
      for (Language element : getStaticAndDynamicLanguages()) {
        if (element.getShortCode().equalsIgnoreCase(langCode)) {
          return element;
        }
      }
    } else if (langCode.contains("-")) {
      String[] parts = langCode.split("-");
      if (parts.length == 2) { // e.g. en-US
        for (Language element : getStaticAndDynamicLanguages()) {
          if (parts[0].equalsIgnoreCase(element.getShortCode())
                  && element.getCountries().length == 1
                  && parts[1].equalsIgnoreCase(element.getCountries()[0])) {
            result = element;
            break;
          }
        }
      } else if (parts.length == 3) { // e.g. ca-ES-valencia
        for (Language element : getStaticAndDynamicLanguages()) {
          if (parts[0].equalsIgnoreCase(element.getShortCode())
                  && element.getCountries().length == 1
                  && parts[1].equalsIgnoreCase(element.getCountries()[0])
                  && parts[2].equalsIgnoreCase(element.getVariant())) {
            result = element;
            break;
          }
        }
      } else {
        throw new IllegalArgumentException("'" + langCode + "' isn't a valid language code");
      }
    } else {
      for (Language element : getStaticAndDynamicLanguages()) {
        if (langCode.equalsIgnoreCase(element.getShortCode())) {
          result = element;
            /* TODO: It should return the DefaultLanguageVariant,
             * not the first language found */
          break;
        }
      }
    }
    return result;
  }

  @Nullable
  private static Language getLanguageForLanguageNameAndCountry(Locale locale) {
    for (Language language : getStaticAndDynamicLanguages()) {
      if (language.getShortCode().equals(locale.getLanguage())) {
        List<String> countryVariants = Arrays.asList(language.getCountries());
        if (countryVariants.contains(locale.getCountry())) {
          return language;
        }
      }
    }
    return null;
  }

  @Nullable
  private static Language getLanguageForLanguageNameOnly(Locale locale) {
    // use default variant if available:
    for (Language language : getStaticAndDynamicLanguages()) {
      if (language.getShortCode().equals(locale.getLanguage()) && language.hasVariant()) {
        Language defaultVariant = language.getDefaultLanguageVariant();
        if (defaultVariant != null) {
          return defaultVariant;
        }
      }
    }
    // use the first match otherwise (which should be the only match):
    for (Language language : getStaticAndDynamicLanguages()) {
      if (language.getShortCode().equals(locale.getLanguage()) && !language.hasVariant()) {
        return language;
      }
    }
    return null;
  }

}
