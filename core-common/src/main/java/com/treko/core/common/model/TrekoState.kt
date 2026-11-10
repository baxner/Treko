package com.treko.core.common.model

/**
 * Representa el estado conversacional principal de Treko.
 */
enum class TrekoState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR;

    val defaultEmotion: TrekoEmotion
        get() = when (this) {
            IDLE -> TrekoEmotion.RELAX
            LISTENING -> TrekoEmotion.ESCUCHA
            THINKING -> TrekoEmotion.PIENSA
            SPEAKING -> TrekoEmotion.FELIZ_ABIERTO
            ERROR -> TrekoEmotion.CONFUSED
        }
}

/**
 * Representa las expresiones y emociones faciales oficiales de Treko.
 */
enum class TrekoEmotion(val fileName: String) {
    RELAX("relax.gif"),
    HAPPY("happy.gif"),
    FELIZ("feliz.gif"),
    FELIZ_ABIERTO("feliz_abierto.gif"),
    ESCUCHA("escucha.gif"),
    PIENSA("piensa.gif"),
    THINKING("thinking.gif"),
    ANGRY("angry.gif"),
    CARINO("carino.gif"),
    COMPLICIDAD("complicidad.gif"),
    CONFIDENT("confident.gif"),
    CONFUSED("confused.gif"),
    COOL("cool.gif"),
    CRYING("crying.gif"),
    DELICIOUS("delicious.gif"),
    EMBARRASSED("embarrassed.gif"),
    FUNNY("funny.gif"),
    KISSY("kissy.gif"),
    LAUGHING("laughing.gif"),
    LOVING("loving.gif"),
    NEUTRAL("neutral.gif"),
    OOPS("oops.gif"),
    RISA("risa.gif"),
    RISA_APENADA("risa_apenada.gif"),
    RISA_OJOS("risa_ojos.gif"),
    SAD("sad.gif"),
    SEGURO("seguro.gif"),
    SHOCKED("shocked.gif"),
    SILLY("silly.gif"),
    SLEEPY("sleepy.gif"),
    SURPRISED("surprised.gif"),
    VERGUENZA("verguenza.gif"),
    WINKING("winking.gif"),

    // Pantallas especiales
    TREKO_240("treko_240.gif"),
    TREKO_CALMA("treko_calma.gif"),
    TREKO_DESCUBRE("treko_descubre.gif"),
    TREKO_OVEJAS("treko_ovejas.gif"),
    TREKO_PELOTA("treko_pelota.gif");

    companion object {
        fun fromString(name: String?): TrekoEmotion {
            if (name.isNullOrBlank()) return RELAX
            val clean = name.trim().lowercase()
            return entries.firstOrNull { 
                it.name.lowercase() == clean || it.fileName.removeSuffix(".gif") == clean 
            } ?: RELAX
        }
    }
}
