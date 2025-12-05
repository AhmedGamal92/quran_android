package com.quran.labs.androidquran.service

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.quran.data.core.QuranInfo
import com.quran.data.model.SuraAyah
import com.quran.labs.androidquran.common.audio.model.playback.AudioPathInfo
import com.quran.labs.androidquran.common.audio.model.playback.AudioRequest
import com.quran.labs.androidquran.dao.audio.AudioPlaybackInfo
import com.quran.labs.androidquran.extension.requiresBasmallah
import com.quran.labs.androidquran.presenter.audio.service.AudioQueue
import com.quran.labs.androidquran.util.AudioUtils
import com.quran.labs.androidquran.util.QuranFileUtils
import com.quran.labs.feature.autoquran.common.BrowsableSurahBuilder
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class QuranServiceCallback @AssistedInject constructor(
  @Assisted private val service: AudioService,
  private val surahBuilder: BrowsableSurahBuilder,
  private val quranFileUtils: QuranFileUtils,
  private val audioUtils: AudioUtils,
  private val quranInfo: QuranInfo,
) : MediaLibraryService.MediaLibrarySession.Callback {

  private val rootMediaItem: MediaItem by lazy {
    MediaItem.Builder()
      .setMediaId(BrowsableSurahBuilder.ROOT_ID)
      .setMediaMetadata(
        MediaMetadata.Builder()
          .setIsBrowsable(true)
          .setMediaType(MediaMetadata.MEDIA_TYPE_MIXED)
          .setIsPlayable(false)
          .build()
      )
      .build()
  }

  private val scope = MainScope()
  
  @AssistedFactory
  interface Factory {
    fun create(service: AudioService): QuranServiceCallback
  }

  override fun onGetLibraryRoot(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    params: MediaLibraryService.LibraryParams?
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val rootExtras = Bundle().apply {
      putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
      putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM)
    }
    val libraryParams = MediaLibraryService.LibraryParams.Builder().setExtras(rootExtras).build()
    return Futures.immediateFuture(LibraryResult.ofItem(rootMediaItem, libraryParams))
  }

  override fun onGetItem(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    mediaId: String
  ): ListenableFuture<LibraryResult<MediaItem>> {
    val settable = SettableFuture.create<LibraryResult<MediaItem>>()
    MainScope().launch {
      val item = surahBuilder.child(mediaId)
      val result = if (item == null) {
        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      } else {
        LibraryResult.ofItem(item, MediaLibraryService.LibraryParams.Builder().build())
      }
      settable.set(result)
    }
    return settable
  }

  override fun onGetChildren(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    parentId: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val settable = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
    scope.launch {
      val children = surahBuilder.children(parentId)
      val result =
        LibraryResult.ofItemList(children, MediaLibraryService.LibraryParams.Builder().build())
      settable.set(result)
    }
    return settable
  }

  override fun onAddMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>
  ): ListenableFuture<List<MediaItem>> {
    val settable = SettableFuture.create<List<MediaItem>>()
    scope.launch {
      val items = mediaItems.mapNotNull { surahBuilder.child(it.mediaId) }
      settable.set(items)
    }
    return settable
  }

  override fun onSetMediaItems(
    mediaSession: MediaSession,
    controller: MediaSession.ControllerInfo,
    mediaItems: List<MediaItem>,
    startIndex: Int,
    startPositionMs: Long
  ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
    // If we have a single item that is a sura, we want to play it using our custom logic
    if (mediaItems.size == 1) {
      val item = mediaItems.first()
      val mediaId = item.mediaId
      if (mediaId.startsWith("sura_")) {
         // Parse mediaId: sura_SURA_QARI
         val parts = mediaId.split("_")
         if (parts.size >= 3) {
           val sura = parts[1].toIntOrNull()
           val qariId = parts[2].toIntOrNull()
           if (sura != null && qariId != null) {
             scope.launch {
               val qari = audioUtils.getQariList(service).firstOrNull { it.id == qariId }
               if (qari != null) {
                 // Create AudioRequest
                 val pathInfo = AudioPathInfo(
                   urlFormat = qari.url,
                   localDirectory = quranFileUtils.audioFileDirectory() ?: "",
                   gaplessDatabase = qari.databaseName,
                   allowedExtensions = listOf("mp3")
                 )
                 val start = SuraAyah(sura, 1)
                 val end = SuraAyah(sura, quranInfo.getNumberOfAyahs(sura))
                 val request = AudioRequest(
                   start = start,
                   end = end,
                   qari = qari,
                   enforceBounds = true,
                   shouldStream = true,
                   audioPathInfo = pathInfo
                 )

                 service.audioRequest = request
                 service.audioQueue = AudioQueue(quranInfo, request, AudioPlaybackInfo(request.start, 1, 1, request.start.requiresBasmallah()))
                 
                 // Start playback
                 service.playAudio()
               }
             }
             // Return success, but we handle playback manually
             return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
           }
         }
      }
    }
    
    return if (mediaItems.size == 1) {
      val settable = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
      scope.launch {
        val firstItem = mediaItems.first()
        val items = surahBuilder.expandMediaItem(firstItem.mediaId)
        val index = items.indexOfFirst { it.mediaId == firstItem.mediaId }
        val startPosition = if (index != -1) index else 0
        val result = MediaSession.MediaItemsWithStartPosition(items, startPosition, 0)
        settable.set(result)
      }
      settable
    } else {
      super.onSetMediaItems(
        mediaSession,
        controller,
        mediaItems,
        startIndex,
        startPositionMs
      )
    }
  }

  override fun onSearch(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    params: MediaLibraryService.LibraryParams?
  ): ListenableFuture<LibraryResult<Void>> {
    val settable = SettableFuture.create<LibraryResult<Void>>()
    scope.launch {
      session.notifySearchResultChanged(browser, query, surahBuilder.search(query).size, params)
      settable.set(LibraryResult.ofVoid(params))
    }
    return settable
  }

  override fun onGetSearchResult(
    session: MediaLibraryService.MediaLibrarySession,
    browser: MediaSession.ControllerInfo,
    query: String,
    page: Int,
    pageSize: Int,
    params: MediaLibraryService.LibraryParams?
  ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
    val settable = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
    scope.launch {
      val items = surahBuilder.search(query)
      settable.set(
        LibraryResult.ofItemList(items, MediaLibraryService.LibraryParams.Builder().build())
      )
    }
    return settable
  }

  override fun onConnect(
    session: MediaSession,
    controller: MediaSession.ControllerInfo
  ): MediaSession.ConnectionResult {
    val connectionResult = super.onConnect(session, controller)
    val sessionCommands = connectionResult.availableSessionCommands
      .buildUpon()
      // Add custom commands if needed
      .build()
    return MediaSession.ConnectionResult.accept(
      sessionCommands,
      connectionResult.availablePlayerCommands
    )
  }
}
