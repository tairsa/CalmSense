package com.example.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device persistence for the trained logistic-regression weights.
 *
 * Panic attacks happen in places without signal — the decision must run
 * offline. We persist the most recently *trained* weights to SharedPreferences
 * and rehydrate them on cold start so the model is ready before (or even
 * without) a successful backend fetch. Untrained/default responses are
 * deliberately not cached, so we never clobber a known-good model with a
 * zero-vector fallback returned during a server outage.
 */
class PanicModelCache(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): PanicModel? {
        val json = prefs.getString(KEY_MODEL_JSON, null) ?: return null
        return runCatching {
            val obj = JSONObject(json)
            val wArr = obj.getJSONArray("weights")
            val weights = DoubleArray(wArr.length()) { wArr.getDouble(it) }
            PanicModel(
                weights = weights,
                source = obj.optString("source", "cached"),
                modelType = obj.optString("model_type", null).takeIf { it.isNotEmpty() },
                trainedAt = obj.optString("trained_at", null).takeIf { it.isNotEmpty() },
                testAccuracy = if (obj.has("test_accuracy") && !obj.isNull("test_accuracy"))
                    obj.getDouble("test_accuracy") else null,
            )
        }.getOrNull()
    }

    /** Persist only trained models — never overwrite a good model with defaults. */
    fun save(model: PanicModel) {
        if (model.isUntrained()) return
        val wArr = JSONArray()
        model.weights.forEach { wArr.put(it) }
        val obj = JSONObject().apply {
            put("weights", wArr)
            put("source", model.source)
            put("model_type", model.modelType)
            put("trained_at", model.trainedAt)
            if (model.testAccuracy != null) put("test_accuracy", model.testAccuracy)
        }
        prefs.edit().putString(KEY_MODEL_JSON, obj.toString()).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_MODEL_JSON).apply()
    }

    companion object {
        private const val PREFS_NAME = "calmsense_model"
        private const val KEY_MODEL_JSON = "panic_model_json"
    }
}
