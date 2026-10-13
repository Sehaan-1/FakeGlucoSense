package com.fakeglucosense.companion.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Schema version 1. Export is off for now; add a schema directory and migrations before the
 * first release that changes the table (ADR-0006).
 */
@Database(entities = [GlucoseEntity::class], version = 1, exportSchema = false)
abstract class GlucoseDatabase : RoomDatabase() {
    abstract fun glucoseDao(): GlucoseDao

    companion object {
        const val NAME = "glucose.db"
    }
}
