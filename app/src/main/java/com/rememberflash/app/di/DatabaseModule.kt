package com.rememberflash.app.di

import android.content.Context
import androidx.room.Room
import com.rememberflash.app.data.local.database.RememberFlashDatabase
import com.rememberflash.app.data.local.database.dao.ContestDao
import com.rememberflash.app.data.local.database.dao.DisciplineDao
import com.rememberflash.app.data.local.database.dao.EssayDao
import com.rememberflash.app.data.local.database.dao.FlashcardDao
import com.rememberflash.app.data.local.database.dao.QuestionDao
import com.rememberflash.app.data.local.database.dao.ScheduleDao
import com.rememberflash.app.data.local.database.dao.TopicDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RememberFlashDatabase {
        return Room.databaseBuilder(
            context,
            RememberFlashDatabase::class.java,
            RememberFlashDatabase.DATABASE_NAME
        )
            .addMigrations(
                RememberFlashDatabase.MIGRATION_1_2,
                RememberFlashDatabase.MIGRATION_2_3,
                RememberFlashDatabase.MIGRATION_3_4,
                RememberFlashDatabase.MIGRATION_10_11,
                RememberFlashDatabase.MIGRATION_11_12,
                RememberFlashDatabase.MIGRATION_12_13
            )
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    @Provides
    fun provideContestDao(database: RememberFlashDatabase): ContestDao = database.contestDao()

    @Provides
    fun provideDisciplineDao(database: RememberFlashDatabase): DisciplineDao = database.disciplineDao()

    @Provides
    fun provideTopicDao(database: RememberFlashDatabase): TopicDao = database.topicDao()

    @Provides
    fun provideFlashcardDao(database: RememberFlashDatabase): FlashcardDao = database.flashcardDao()

    @Provides
    fun provideEssayDao(database: RememberFlashDatabase): EssayDao = database.essayDao()

    @Provides
    fun provideScheduleDao(database: RememberFlashDatabase): ScheduleDao = database.scheduleDao()

    @Provides
    fun provideQuestionDao(database: RememberFlashDatabase): QuestionDao = database.questionDao()

    @Provides
    fun provideMockExamAttemptDao(database: RememberFlashDatabase): com.rememberflash.app.data.local.database.dao.MockExamAttemptDao = database.mockExamAttemptDao()

    @Provides
    fun provideUserDao(database: RememberFlashDatabase): com.rememberflash.app.data.local.database.dao.UserDao = database.userDao()

    @Provides
    @Singleton
    fun provideMockExamNoSqlDataSource(
        @ApplicationContext context: Context
    ): com.rememberflash.app.data.remote.firebase.MockExamNoSqlDataSource {
        val firestoreDir = java.io.File(context.filesDir, "firestore_nosql")
        return com.rememberflash.app.data.remote.firebase.FirestoreMockExamDataSource(firestoreDir)
    }
}
