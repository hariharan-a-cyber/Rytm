package com.hariharan.rytm.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.hariharan.rytm.data.dao.*
import com.hariharan.rytm.data.database.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    private val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE habits ADD COLUMN identity TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE habits ADD COLUMN cue TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE habits ADD COLUMN twoMinuteVersion TEXT NOT NULL DEFAULT ''")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "rytm_database",
        ).addMigrations(MIGRATION_6_7)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideHabitDao(db: AppDatabase): HabitDao = db.habitDao()

    @Provides
    fun provideReminderDao(db: AppDatabase): ReminderDao = db.reminderDao()

    @Provides
    fun provideCompletionLogDao(db: AppDatabase): CompletionLogDao = db.completionLogDao()

    @Provides
    fun provideWaterReminderDao(db: AppDatabase): WaterReminderDao = db.waterReminderDao()

    @Provides
    fun provideWaterLogDao(db: AppDatabase): WaterLogDao = db.waterLogDao()

    @Provides
    fun provideAppSettingsDao(db: AppDatabase): AppSettingsDao = db.appSettingsDao()

    @Provides
    fun provideWaterReminderLogDao(db: AppDatabase): WaterReminderLogDao = db.waterReminderLogDao()
}

