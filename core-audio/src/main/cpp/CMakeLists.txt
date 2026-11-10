cmake_minimum_required(VERSION 3.22.1)
project("treko_audio")

# Añadir librería nativa de audio de Treko
add_library(
    treko_audio
    SHARED
    oboe_audio_engine.cpp
)

# Enlazar con librerías del sistema de Android (OpenSL ES, aaudiio, log)
find_library(log-lib log)
find_library(aaudio-lib aaudio)

target_link_libraries(
    treko_audio
    ${log-lib}
    ${aaudio-lib}
)
