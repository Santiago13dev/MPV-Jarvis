package com.whatsappmvp.application.reservation;

import java.text.Normalizer;
import java.util.Locale;

/** Normaliza texto de usuario: minúsculas, sin tildes, signos sustituidos por espacios. */
public final class TextNormalizer {

    private TextNormalizer() {}

    public static String normalize(String raw) {
        if (raw == null) return "";
        String s = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        // "p.m." / "a. m." → "pm" / "am" antes de limpiar signos
        s = s.replace("a.m.", "am").replace("p.m.", "pm").replace("a. m.", "am").replace("p. m.", "pm");
        // conserva letras, dígitos y separadores útiles para fechas/horas
        s = s.replaceAll("[^a-z0-9:/#.\\-\\s]", " ");
        // puntos que no forman parte de un número (hora/fecha)
        s = s.replaceAll("(?<!\\d)\\.|\\.(?!\\d)", " ");
        return s.replaceAll("\\s+", " ").trim();
    }
}
