package org.branneman.health.ai

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.branneman.health.HealthApplication
import org.branneman.health.auth.TokenStore
import org.branneman.health.auth.authDataStore
import org.branneman.health.db.HealthDatabase
import org.branneman.health.db.entities.LogEntryEntity
import org.branneman.health.network.AiEstimateApiResult
import org.branneman.health.network.HealthApiClient
import java.io.ByteArrayOutputStream
import java.time.OffsetDateTime

sealed interface AskAiState {
    data object Idle : AskAiState
    data object Loading : AskAiState
    data class Result(val kcal: Int, val explanation: String?, val inputText: String?, val aiDescription: String?) : AskAiState
    sealed interface Error : AskAiState {
        data object NotConfigured : Error
        data object KeyExpired : Error
        data object EstimateFailed : Error
        data object Network : Error
    }
}

class AskAiViewModel private constructor(
    application: Application,
    private val repository: AiRepository,
    private val db: HealthDatabase,
    private val tokenStore: TokenStore,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application = application,
        repository  = NetworkAiRepository(
            client   = HealthApiClient(),
            getToken = { TokenStore((application as HealthApplication).authDataStore).tokenFlow.first()?.token },
        ),
        db          = (application as HealthApplication).db,
        tokenStore  = TokenStore(application.authDataStore),
    )

    internal companion object {
        fun forTest(
            application: Application,
            repository:  AiRepository,
            db:          HealthDatabase = HealthDatabase.buildInMemory(application),
            tokenStore:  TokenStore     = TokenStore(application.authDataStore),
        ) = AskAiViewModel(
            application = application,
            repository  = repository,
            db          = db,
            tokenStore  = tokenStore,
        )
    }

    val state: MutableStateFlow<AskAiState>     = MutableStateFlow(AskAiState.Idle)
    val text: MutableStateFlow<String>           = MutableStateFlow("")
    val imageBitmap: MutableStateFlow<ImageBitmap?> = MutableStateFlow(null)
    val canEstimate: MutableStateFlow<Boolean>   = MutableStateFlow(false)

    private var imageBytes: ByteArray? = null
    private var lastLogged: LogEntryEntity? = null

    init {
        viewModelScope.launch {
            combine(text, imageBitmap) { t, img -> t.isNotBlank() || img != null }
                .collect { canEstimate.value = it }
        }
    }

    fun setText(value: String) { text.value = value }

    fun setImage(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = resizeToJpeg(context, uri)
            imageBytes = bytes
            imageBitmap.value = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }
    }

    fun clearImage() {
        imageBytes = null
        imageBitmap.value = null
    }

    fun estimate() {
        viewModelScope.launch {
            state.value = AskAiState.Loading
            val result = repository.estimate(
                text       = text.value.trim().ifEmpty { null },
                imageBytes = imageBytes,
            )
            state.value = when (result) {
                is AiEstimateApiResult.Success     ->
                    AskAiState.Result(
                        kcal          = result.dto.kcal,
                        explanation   = result.dto.explanation,
                        inputText     = text.value.trim().ifEmpty { null },
                        aiDescription = result.dto.description,
                    )
                AiEstimateApiResult.NotConfigured  -> AskAiState.Error.NotConfigured
                AiEstimateApiResult.KeyExpired     -> AskAiState.Error.KeyExpired
                AiEstimateApiResult.EstimateFailed -> AskAiState.Error.EstimateFailed
                AiEstimateApiResult.NetworkError   -> AskAiState.Error.Network
            }
        }
    }

    fun discard() { state.value = AskAiState.Idle }

    fun reset() {
        state.value = AskAiState.Idle
        text.value = ""
        imageBitmap.value = null
        imageBytes = null
    }

    fun logDirectly(kcal: Int, label: String?, aiDescription: String?) {
        viewModelScope.launch {
            val userId = tokenStore.tokenFlow.first()?.userId ?: return@launch
            val entity = LogEntryEntity(
                userId        = userId,
                loggedAt      = OffsetDateTime.now().toString(),
                mealType      = "unknown",
                quickAddKcal  = kcal,
                quickAddLabel = (aiDescription ?: label)?.trim()?.ifEmpty { null },
            )
            db.logEntryDao().upsert(entity)
            lastLogged = entity
        }
    }

    fun undoDirectLog() {
        viewModelScope.launch {
            lastLogged?.let {
                db.logEntryDao().deleteById(it.id)
                lastLogged = null
            }
        }
    }

    private fun resizeToJpeg(context: Context, uri: Uri, maxPx: Int = 1024, targetBytes: Long = 1_000_000): ByteArray {
        val source = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it)
        } ?: return ByteArray(0)

        val scale = minOf(1f, maxPx.toFloat() / maxOf(source.width, source.height))
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt(),
                (source.height * scale).toInt(),
                true,
            )
        } else source

        var quality = 90
        var bytes: ByteArray
        do {
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            bytes = stream.toByteArray()
            quality -= 10
        } while (bytes.size > targetBytes && quality > 10)
        return bytes
    }
}
