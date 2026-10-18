# Database Migration — Room & SQLDelight

## Room Migrations

### AutoMigration (Room 2.4+, recommended for simple changes)

AutoMigration handles:
- Adding new columns with default values
- Adding new tables
- Renaming tables/columns (with `@RenameTable` / `@RenameColumn`)
- Dropping columns (with `@DeleteColumn`)
- Dropping tables (with `@DeleteTable`)

```kotlin
@Database(
    entities = [WordEntity::class, TagEntity::class],
    version = 4,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),                              // simple column add
        AutoMigration(from = 2, to = 3, spec = Migration2To3::class), // rename needed
        AutoMigration(from = 3, to = 4, spec = Migration3To4::class), // delete column
    ],
    exportSchema = true,   // REQUIRED for AutoMigration — enables schema diff
)
abstract class AppDatabase : RoomDatabase() {
    companion object {
        const val DATABASE_VERSION = 4
    }
}
```

```kotlin
// Rename table — spec class required
@RenameTable(fromTableName = "words", toTableName = "word")
class Migration2To3 : AutoMigrationSpec

// Rename column
@RenameColumn(tableName = "word", fromColumnName = "word_text", toColumnName = "original")
class Migration3To4 : AutoMigrationSpec

// Delete column
@DeleteColumn(tableName = "word", columnName = "legacy_field")
class Migration4To5 : AutoMigrationSpec

// Delete table
@DeleteTable(tableName = "obsolete_cache")
class Migration5To6 : AutoMigrationSpec
```

### exportSchema = true

Always enable schema export — it's required for AutoMigration and critical for manual migration audits.

**With kapt:**
```kotlin
// build.gradle.kts
android {
    defaultConfig {
        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
            }
        }
    }
}
```

**With KSP (preferred):**
```kotlin
// build.gradle.kts — top-level, not inside android {}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}
```

Commit `schemas/` to version control — it's your migration audit trail.

---

### Manual Migration (complex data transforms)

Room wraps each `Migration.migrate()` call in a transaction automatically.

```kotlin
// Complex migration: split a column, backfill data, drop old column
val MIGRATION_5_TO_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Add new columns
        db.execSQL("ALTER TABLE word ADD COLUMN bucket INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE word ADD COLUMN next_review TEXT NOT NULL DEFAULT ''")

        // 2. Backfill from old column
        db.execSQL("""
            UPDATE word
            SET bucket = CASE
                WHEN srs_level <= 1 THEN 0
                WHEN srs_level <= 3 THEN 1
                ELSE 2
            END,
            next_review = date('now', '+1 day')
        """.trimIndent())

        // 3. Drop old column (requires full table recreation in SQLite)
        db.execSQL("""
            CREATE TABLE word_new (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                original TEXT NOT NULL,
                translated TEXT NOT NULL,
                bucket INTEGER NOT NULL DEFAULT 0,
                next_review TEXT NOT NULL,
                created_at TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            INSERT INTO word_new (id, original, translated, bucket, next_review, created_at)
            SELECT id, original, translated, bucket, next_review, created_at FROM word
        """.trimIndent())
        db.execSQL("DROP TABLE word")
        db.execSQL("ALTER TABLE word_new RENAME TO word")

        // 4. ALWAYS recreate any indices and foreign keys after table recreation
        db.execSQL("CREATE INDEX IF NOT EXISTS index_word_bucket ON word (bucket)")
    }
}

// Register in database builder
Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .addMigrations(MIGRATION_5_TO_6)
    .fallbackToDestructiveMigrationOnDowngrade()  // dev only
    .build()
```

---

### Migration Testing (Room)

Always test every migration. Use the updated `MigrationTestHelper` signature (Room 2.5+):

```kotlin
// androidTest
class MigrationTest {

    private val TEST_DB = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),                          // AutoMigrationSpec list if using AutoMigration
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate5to6() {
        // Create v5 database with test data
        helper.createDatabase(TEST_DB, 5).use { db ->
            db.execSQL("""
                INSERT INTO word (id, original, translated, srs_level, created_at)
                VALUES (1, 'hello', 'hola', 3, '2025-01-01T00:00:00Z')
            """.trimIndent())
        }

        // Run migration
        val db = helper.runMigrationsAndValidate(TEST_DB, 6, true, MIGRATION_5_TO_6)

        // Verify data integrity
        db.query("SELECT bucket, next_review FROM word WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(cursor.getColumnIndex("bucket")))
            assertNotNull(cursor.getString(cursor.getColumnIndex("next_review")))
        }
    }

    @Test
    fun migrateAll() {
        // Test full migration chain from version 1 to current
        helper.createDatabase(TEST_DB, 1)
        helper.runMigrationsAndValidate(
            TEST_DB,
            AppDatabase.DATABASE_VERSION,
            true,
            MIGRATION_1_TO_2,
            MIGRATION_2_TO_3,
            MIGRATION_3_TO_4,
            MIGRATION_4_TO_5,
            MIGRATION_5_TO_6,
        )
    }
}
```

---

## SQLDelight Migrations (.sqm files)

SQLDelight is the KMP-native database solution — the same `.sqm` migration files apply on **both Android** (via `AndroidSqliteDriver`) and **iOS** (via `NativeSqliteDriver`). This is a key advantage over Room, which is Android-only.

SQLDelight tracks schema version by migration file numbering. Current version = highest `.sqm` number + 1.

### Schema Version

```kotlin
// Android driver factory
val driver = AndroidSqliteDriver(
    schema = AppDatabase.Schema,
    context = context,
    name = "app.db",
)

// iOS/native driver (in iosMain)
val driver = NativeSqliteDriver(AppDatabase.Schema, "app.db")
```

### Migration Files

```
commonMain/sqldelight/migrations/
├── 1.sqm   ← version 1 → 2
├── 2.sqm   ← version 2 → 3
└── 3.sqm   ← version 3 → 4
```

```sql
-- 1.sqm — Add bucket and next_review columns
ALTER TABLE Word ADD COLUMN bucket INTEGER NOT NULL DEFAULT 0;
ALTER TABLE Word ADD COLUMN next_review TEXT NOT NULL DEFAULT '';

-- Backfill next_review for existing rows
UPDATE Word SET next_review = date('now', '+1 day') WHERE next_review = '';
```

```sql
-- 2.sqm — Add TagWord junction table
CREATE TABLE IF NOT EXISTS TagWord (
    tag_id   INTEGER NOT NULL,
    word_id  INTEGER NOT NULL,
    PRIMARY KEY (tag_id, word_id),
    FOREIGN KEY (tag_id) REFERENCES Tag(id) ON DELETE CASCADE,
    FOREIGN KEY (word_id) REFERENCES Word(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS index_TagWord_word_id ON TagWord(word_id);
```

```sql
-- 3.sqm — Rename column (SQLite requires full table recreation)
-- SQLDelight does NOT auto-wrap in a transaction; do it explicitly for multi-step migrations
BEGIN TRANSACTION;

CREATE TABLE Word_new (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    original     TEXT NOT NULL,
    translated   TEXT NOT NULL,
    bucket       INTEGER NOT NULL DEFAULT 0,
    next_review  TEXT NOT NULL DEFAULT '',
    created_at   TEXT NOT NULL
);

INSERT INTO Word_new SELECT id, original_text, translated_text, bucket, next_review, created_at FROM Word;

DROP TABLE Word;

ALTER TABLE Word_new RENAME TO Word;

-- Recreate any indices that existed on Word
CREATE INDEX IF NOT EXISTS index_Word_bucket ON Word(bucket);

COMMIT;
```

### Verifying Migration Version

```kotlin
// build.gradle.kts — SQLDelight
sqldelight {
    databases {
        create("AppDatabase") {
            packageName.set("com.example.db")
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/migrations"))
            migrationOutputDirectory.set(file("src/commonMain/sqldelight/migrations"))
            verifyMigrations.set(true)   // fail build if migrations don't match schema
        }
    }
}
```

### Migration Testing (SQLDelight)

Test SQLDelight migrations using an in-memory driver:

```kotlin
// commonTest or androidUnitTest
class SqlDelightMigrationTest {

    @Test
    fun migration1to2() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Create schema at version 1 (before migration)
        AppDatabase.Schema.create(driver)

        // Apply migration 1→2
        AppDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 2)

        val db = AppDatabase(driver)
        // Assert schema is valid and data is accessible
        db.wordQueries.selectAll().executeAsList()
    }

    @Test
    fun migrateAllVersions() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        AppDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = AppDatabase.Schema.version)
    }
}
```

---

## Migration Safety Rules

1. **Never drop columns in production without a grace period** — deploy code reading both old and new column first
2. **Always provide a DEFAULT when adding a NOT NULL column** — `ADD COLUMN x TEXT NOT NULL` fails on non-empty tables without a `DEFAULT`
3. **Always recreate indices and foreign keys after table recreation** — SQLite silently drops them with the old table
4. **Test every migration path** — 1→current AND n→n+1
5. **Backup before migration** — especially for destructive operations
6. **No breaking changes in a single deploy** — use expand-contract pattern for renaming
7. **Never edit a shipped migration** — once merged and released, a migration is immutable history; editing it breaks users who already ran it

### Expand-Contract for Zero-Downtime Rename

```
Phase 1 (deploy): Add new column "original", copy data, write to both columns
Phase 2 (deploy): Stop writing to old "word_text", read from "original"
Phase 3 (deploy): Drop "word_text"
```

```kotlin
// Phase 1 migration
val MIGRATION_EXPAND = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE word ADD COLUMN original TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE word SET original = word_text")
    }
}
```

---

## Fallback Strategies

```kotlin
// Development only — never ship to production without a migration
Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .fallbackToDestructiveMigration()               // wipe on any missing migration (DEV ONLY)
    .fallbackToDestructiveMigrationOnDowngrade()    // wipe on downgrade (acceptable in dev)
    .fallbackToDestructiveMigrationFrom(1, 2, 3)   // wipe only if coming from these versions
    .build()

// Production — fail loudly if migration is missing
Room.databaseBuilder(context, AppDatabase::class.java, "app.db")
    .addMigrations(*ALL_MIGRATIONS)
    // No fallback — crash is better than silent data loss
    .build()
```
