package com.psddev.cms.hunspell;

import com.psddev.cms.db.ToolUserDictionary;
import com.atlascopco.hunspell.Hunspell;
import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.psddev.cms.nlp.SpellChecker;
import com.psddev.dari.db.Query;
import com.psddev.dari.util.ObjectUtils;

import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Spell checker implementation using
 * <a href="http://hunspell.sourceforge.net/">Hunspell</a>.
 *
 * <p>The dictionary files should be in the classpath with their names
 * starting with {@code HunspellDictionary} and ending with
 * {@link #AFFIX_FILE_SUFFIX} or {@link #DICTIONARY_FILE_SUFFIX}.</p>
 *
 * <p>For example, if the locale is {@code ko-KR}, the affix file should be
 * named {@code HunspellDictionary_ko_KR.aff}, and the dictionary file should
 * be named {@code HunspellDictionary_ko_KR.dic}.</p>
 */
public class HunspellSpellChecker implements SpellChecker {

    /**
     * Affix file suffix/extension.
     *
     * @see <a href="http://sourceforge.net/projects/hunspell/files/Hunspell/Documentation/">Hunspell Manual</a>
     */
    public static final String AFFIX_FILE_SUFFIX = ".aff";

    /**
     * Dictionary file suffix/extension.
     *
     * @see <a href="http://sourceforge.net/projects/hunspell/files/Hunspell/Documentation/">Hunspell Manual</a>
     */
    public static final String DICTIONARY_FILE_SUFFIX = ".dic";

    public static final String DICTIONARY_BASE_NAME = "HunspellDictionary";

    private final LoadingCache<Locale, Optional<Hunspell>> hunspells = CacheBuilder
            .newBuilder()
            .removalListener(new RemovalListener<Locale, Optional<Hunspell>>() {

                @Override
                @ParametersAreNonnullByDefault
                public void onRemoval(RemovalNotification<Locale, Optional<Hunspell>> removalNotification) {
                    Optional<Hunspell> hunspellOptional = removalNotification.getValue();

                    if (hunspellOptional != null) {
                        hunspellOptional.ifPresent(Hunspell::close);
                    }
                }
            })
            .build(new CacheLoader<Locale, Optional<Hunspell>>() {

                @Override
                @ParametersAreNonnullByDefault
                public Optional<Hunspell> load(Locale locale) throws IOException {

                    ResourceBundle.Control control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_CLASS);
                    List<String> names = control
                            .getCandidateLocales(DICTIONARY_BASE_NAME, locale)
                            .stream()
                            .filter(l -> !Locale.ROOT.equals(l))
                            .map(l -> control.toBundleName(DICTIONARY_BASE_NAME, l))
                            .collect(Collectors.toList());

                    for (String name : names) {

                        String variant = locale.getVariant();
                        name = name.replace("_" + variant, "");

                        try (InputStream affixInput = getClass().getResourceAsStream("/" + name + AFFIX_FILE_SUFFIX)) {
                            if (affixInput != null) {
                                try (InputStream dictionaryInput = getClass().getResourceAsStream("/" + name + DICTIONARY_FILE_SUFFIX)) {
                                    if (dictionaryInput != null) {

                                        ToolUserDictionary userDictionary = Query.from(ToolUserDictionary.class)
                                                .where("userId = ?", locale.getVariant()).and("localeLanguageCode = ?", locale.toLanguageTag()).first();

                                        if (userDictionary == null) {
                                            userDictionary = new ToolUserDictionary();
                                            userDictionary.setUserId(ObjectUtils.to(UUID.class, locale.getVariant()));
                                            userDictionary.setLocaleLanguageCode(locale.toLanguageTag());
                                            userDictionary.save();
                                        }

                                        String prefixPath = name + "_" + userDictionary.getId();

                                        String tmpdir = System.getProperty("java.io.tmpdir");
                                        Path affixPath = Paths.get(tmpdir, prefixPath + AFFIX_FILE_SUFFIX);
                                        Path dictionaryPath = Paths.get(tmpdir, prefixPath + DICTIONARY_FILE_SUFFIX);

                                        Files.copy(affixInput, affixPath, StandardCopyOption.REPLACE_EXISTING);
                                        Files.copy(dictionaryInput, dictionaryPath, StandardCopyOption.REPLACE_EXISTING);

                                        Hunspell hunspell = new Hunspell(dictionaryPath.toString(), affixPath.toString());

                                        for (String userWord : userDictionary.getWords()) {
                                            hunspell.add(userWord);
                                        }

                                        return Optional.of(hunspell);
                                    }
                                }
                            }
                        }
                    }
                    return Optional.empty();
                }
            });

    public Hunspell findHunspell(Locale locale) {
        return hunspells.getUnchecked(locale).orElse(null);
    }

    @Override
    public boolean isSupported(Locale locale) {
        Preconditions.checkNotNull(locale);

        return findHunspell(locale) != null;
    }

    @Override
    public boolean isPreferred(Locale locale) {
        Preconditions.checkNotNull(locale);

        return false;
    }

    @Override
    public List<String> suggest(Locale locale, String word) {
        Preconditions.checkNotNull(locale);
        Preconditions.checkNotNull(word);

        Hunspell hunspell = findHunspell(locale);

        if (hunspell == null) {
            throw new UnsupportedOperationException();

        } else if (hunspell.spell(word)) {
            return null;

        } else {
            return hunspell.suggest(word);
        }
    }

    @Override
    public boolean add(Locale locale, String word, boolean addToUserDictionary) {
        Preconditions.checkNotNull(locale);
        Preconditions.checkNotNull(word);

        Hunspell hunspell = findHunspell(locale);

        if (hunspell == null) {
            throw new UnsupportedOperationException();

        } else if (hunspell.spell(word)) {
            return false;
        } else {
            hunspell.add(word);

            if (addToUserDictionary) {
                ToolUserDictionary userDictionary = Query.from(ToolUserDictionary.class)
                        .where("userId = ?", locale.getVariant()).and("localeLanguageCode = ?", locale.getLanguage()).first();

                if (userDictionary == null) {
                    userDictionary = new ToolUserDictionary();
                    userDictionary.setUserId(ObjectUtils.to(UUID.class, locale.getVariant()));
                    userDictionary.setLocaleLanguageCode(locale.toLanguageTag());
                }
                userDictionary.add(word);
                userDictionary.save();
            }

            return true;
        }
    }

    @Override
    public boolean remove(Locale locale, String word) {
        Preconditions.checkNotNull(locale);
        Preconditions.checkNotNull(word);

        Hunspell hunspell = findHunspell(locale);

        if (hunspell == null) {
            throw new UnsupportedOperationException();

        } else if (hunspell.spell(word)) {
            hunspell.remove(word);
            return true;
        } else {
            return false;
        }
    }
}
