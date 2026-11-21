package com.treko.assistant.service

import android.content.Context
import com.treko.assistant.R
import com.treko.assistant.data.CameraAlertTexts

/** [CameraAlertTexts] from the app's string resources (the phone's language). */
internal fun cameraAlertTexts(context: Context): CameraAlertTexts = CameraAlertTexts(
    followupPrompt = context.getString(R.string.camera_alert_followup_prompt),
    fallbackSpeech = context.getString(R.string.camera_alert_fallback_speech),
    noLevel = context.getString(R.string.camera_alert_no_level),
    people = { n -> context.resources.getQuantityString(R.plurals.camera_alert_people, n, n) },
    vehicles = { n -> context.resources.getQuantityString(R.plurals.camera_alert_vehicles, n, n) },
    onePackage = context.getString(R.string.camera_alert_one_package),
    somethingToCheck = context.getString(R.string.camera_alert_something_to_check),
    nothingSeen = context.getString(R.string.camera_alert_nothing_seen),
    subjectPeople = { n -> context.resources.getQuantityString(R.plurals.camera_alert_subject_people, n, n) },
    subjectVehicles = { n -> context.resources.getQuantityString(R.plurals.camera_alert_subject_vehicles, n, n) },
    subjectPackage = context.getString(R.string.camera_alert_subject_package),
    reassurances = context.resources.getStringArray(R.array.camera_alert_reassurances).toList()
)
