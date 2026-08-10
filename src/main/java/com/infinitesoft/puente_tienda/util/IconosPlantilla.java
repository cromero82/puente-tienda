package com.infinitesoft.puente_tienda.util;

import java.util.List;
import java.util.Set;

public final class IconosPlantilla {

    public static final String CLASSPATH_DIR = "assets/iconos/metodos-pago/";

    public static final List<String> DISPONIBLES = List.of(
            "qr-bancolombia.png",
            "breve-logo.png",
            "otro-metodo.png"
    );

    private static final Set<String> ALLOWED = Set.copyOf(DISPONIBLES);

    private IconosPlantilla() {}

    public static boolean isAllowed(String filename) {
        return filename != null && ALLOWED.contains(filename);
    }

    public static String defaultIcono() {
        return "otro-metodo.png";
    }
}
