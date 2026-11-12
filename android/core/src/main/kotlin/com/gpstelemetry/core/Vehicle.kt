package com.gpstelemetry.core

/**
 * Categoria del veicolo usato in una sessione (scelta dal menu a tendina).
 * Il modello specifico (es. "Honda CBR1000RR") è un testo libero a parte.
 * Il nome dell'enum è il valore scambiato con il server.
 */
enum class VehicleType(val label: String) {
    CAR("Auto"),
    MOTORCYCLE("Moto"),
    KART("Kart"),
    BICYCLE("Bicicletta"),
    MTB("MTB"),
    RUNNING("Corsa a piedi"),
    SCOOTER("Monopattino"),
    TRUCK("Camion");

    companion object {
        /** Dal nome dell'enum o dall'etichetta (maiuscole indifferenti); null se sconosciuto. */
        fun parse(s: String?): VehicleType? {
            val t = s?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.find { it.name.equals(t, ignoreCase = true) || it.label.equals(t, ignoreCase = true) }
        }
    }
}

object Vehicle {
    /** Testo da mostrare: "Moto · Honda CBR1000RR", "Kart", "Honda CBR1000RR"… */
    fun describe(type: VehicleType?, model: String?): String? =
        listOfNotNull(type?.label, model?.trim()?.takeIf { it.isNotEmpty() })
            .joinToString(" · ")
            .ifEmpty { null }

    /**
     * Compatibilità con le sessioni salvate prima della categoria, quando il
     * veicolo era un solo testo libero: se coincide con una categoria
     * ("Kart", "Moto"…) diventa la categoria, altrimenti resta il modello.
     */
    fun migrate(type: VehicleType?, model: String?): Pair<VehicleType?, String?> {
        if (type != null) return type to model?.trim()?.takeIf { it.isNotEmpty() }
        val asType = VehicleType.parse(model)
        return if (asType != null) asType to null else null to model?.trim()?.takeIf { it.isNotEmpty() }
    }
}
