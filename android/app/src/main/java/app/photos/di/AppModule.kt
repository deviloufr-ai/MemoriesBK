package app.photos.di

import android.content.Context
import androidx.room.Room
import app.photos.data.AppDatabase
import app.photos.data.LocalMediaDao
import app.photos.data.MIGRATION_1_2
import app.photos.data.PhotosApi
import app.photos.data.SERVER_PLACEHOLDER
import app.photos.data.ServerInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun okHttp(server: ServerInterceptor): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(server)
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(2, TimeUnit.MINUTES) // un bloc de 8 Mio sur une connexion mobile lente
        .readTimeout(2, TimeUnit.MINUTES)
        .build()

    @Provides
    @Singleton
    fun api(client: OkHttpClient): PhotosApi = Retrofit.Builder()
        .baseUrl("$SERVER_PLACEHOLDER/")
        .client(client)
        .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
        .build()
        .create()

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "photos.db").addMigrations(MIGRATION_1_2).build()

    @Provides
    fun localMediaDao(db: AppDatabase): LocalMediaDao = db.localMedia()
}
