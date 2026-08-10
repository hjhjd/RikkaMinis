package com.openminis.app.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentMigrationInstrumentedTest {
    private var helper: SupportSQLiteOpenHelper? = null

    @After
    fun close() {
        helper?.close()
        ApplicationProvider.getApplicationContext<android.content.Context>().deleteDatabase(DB_NAME)
    }

    @Test
    fun migration10To11CreatesDefaultAgentAndBackfillsSessions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(DB_NAME)
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(object : SupportSQLiteOpenHelper.Callback(10) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE sessions (id TEXT NOT NULL PRIMARY KEY, title TEXT, model_id TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO sessions(id, title, model_id, created_at, updated_at) VALUES ('legacy', 'Old', 'm1', 1, 1)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val db = requireNotNull(helper).writableDatabase

        AppDatabase.MIGRATION_10_11.migrate(db)

        db.query("SELECT id, name, is_default FROM agents").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(AgentIds.DEFAULT, cursor.getString(0))
            assertEquals("RikkaMinis", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
        }
        db.query("SELECT agent_id FROM sessions WHERE id = 'legacy'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(AgentIds.DEFAULT, cursor.getString(0))
        }
        db.query("PRAGMA index_list('sessions')").use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_sessions_agent_id") found = true
            }
            assertTrue("agent_id index must be created", found)
        }
    }

    private companion object {
        const val DB_NAME = "agent-migration-test.db"
    }
}
