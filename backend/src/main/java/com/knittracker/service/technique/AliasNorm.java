package com.knittracker.service.technique;

import java.text.Normalizer;
import java.util.Locale;

/** "K2tog", "k2 tog", "ｋ２ｔｏｇ" and "k-2-tog" are one name: NFKC, lower case, no spaces or separators. */
public final class AliasNorm {

    private AliasNorm() {}

    public static String of(String term) {
        if (term == null) return "";
        String s = Normalizer.normalize(term, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return s.replaceAll("[\\s\\-_.,・･·'’\"()（）\\[\\]【】「」]", "");
    }
}
