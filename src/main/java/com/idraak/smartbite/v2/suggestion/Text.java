package com.idraak.smartbite.v2.suggestion;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Normalisation and whole-word matching for dish names, ingredients and preferences. */
public final class Text {

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_WORD = Pattern.compile("[^a-z0-9]+");

    private Text() {
    }

    /** Lower case, accents removed, punctuation collapsed to single spaces. */
    public static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        n = MARKS.matcher(n).replaceAll("").toLowerCase(Locale.ROOT);
        return NON_WORD.matcher(n).replaceAll(" ").trim();
    }

    /** True if normalised {@code phrase} appears in normalised {@code text} on word boundaries. */
    public static boolean containsPhrase(String text, String phrase) {
        String t = " " + norm(text) + " ";
        String p = norm(phrase);
        if (p.isEmpty()) return false;
        if (t.contains(" " + p + " ")) return true;
        // simple plural: "egg" matches "eggs", "chickpea" matches "chickpeas"
        return t.contains(" " + p + "s ") || t.contains(" " + p + "es ");
    }

    /** Same dish if normalised names are equal. */
    public static boolean sameDish(String a, String b) {
        return !norm(a).isEmpty() && norm(a).equals(norm(b));
    }

    /** Equal, or one name contains the other as whole words ("Seekh Kebab" vs "Seekh Kebab Platter"). */
    public static boolean dishMatches(String a, String b) {
        if (sameDish(a, b)) return true;
        String na = norm(a);
        String nb = norm(b);
        if (na.length() < 3 || nb.length() < 3) return false;
        return containsPhrase(na, nb) || containsPhrase(nb, na);
    }
}
