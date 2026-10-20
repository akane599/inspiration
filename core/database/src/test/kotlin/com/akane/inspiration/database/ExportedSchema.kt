package com.akane.inspiration.database

/** Creates the tables, indices and Room metadata of exported schema [version] in [db]. */
internal fun createExportedSchema(
    db: android.database.sqlite.SQLiteDatabase,
    version: Int,
) {
    val schema =
        org.json
            .JSONObject(
                java.io
                    .File(
                        "schemas/com.akane.inspiration.database.InspirationDatabase/$version.json",
                    ).readText(),
            ).getJSONObject("database")
    val entities = schema.getJSONArray("entities")
    for (i in 0 until entities.length()) {
        val entity = entities.getJSONObject(i)
        val table = entity.getString("tableName")
        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
        val indices = entity.optJSONArray("indices") ?: org.json.JSONArray()
        for (j in 0 until indices.length()) {
            db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
        }
    }
    val setup = schema.getJSONArray("setupQueries")
    for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
}
