package com.example.rwkvnotes.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.example.rwkvnotes.ai.AiProcessor
import com.example.rwkvnotes.ai.AiService
import com.example.rwkvnotes.ai.ModelEngineReloader
import com.example.rwkvnotes.config.AppConfig
import com.example.rwkvnotes.config.AppConfigLoader
import com.example.rwkvnotes.data.local.AppDatabase
import com.example.rwkvnotes.infer.InferenceRuntime
import com.example.rwkvnotes.infer.NativeRwkvBridge
import com.example.rwkvnotes.model.DefaultModelManager
import com.example.rwkvnotes.model.ModelManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

@Module
@InstallIn(SingletonComponent::class)
object AppProvidesModule {
    @Provides
    @Singleton
    fun provideAppConfig(loader: AppConfigLoader): AppConfig = loader.load()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context = context,
            klass = AppDatabase::class.java,
            name = "rwkv_notes.db",
        ).build()
    }

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindsModule {
    @Binds
    @Singleton
    abstract fun bindAiProcessor(service: AiService): AiProcessor

    @Binds
    @Singleton
    abstract fun bindModelEngineReloader(service: AiService): ModelEngineReloader

    @Binds
    @Singleton
    abstract fun bindModelManager(manager: DefaultModelManager): ModelManager

    @Binds
    @Singleton
    abstract fun bindInferenceRuntime(bridge: NativeRwkvBridge): InferenceRuntime
}
