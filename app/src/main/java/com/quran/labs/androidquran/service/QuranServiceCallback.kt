package com.quran.labs.androidquran.service

import android.content.Intent
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
import com.quran.labs.androidquran.util.AudioUtils
import com.quran.labs.androidquran.util.QuranSettings
import com.quran.labs.feature.autoquran.common.BrowsableSurahBuilder
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(UnstableApi::class)
class QuranServiceCallback @AssistedInject constructor(
  @Assisted private val service: AudioService,
  private val surahBuilder: BrowsableSurahBuilder,
  private val audioUtils: AudioUtils,
  private val quranInfo: QuranInfo,
  private val quranSettings: QuranSettings
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
    Timber.d("onGetLibraryRoot called with browser: %s, params: %s", browser.packageName, params)
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
    Timber.d("onGetItem called with mediaId: %s", mediaId)
    val settable = SettableFuture.create<LibraryResult<MediaItem>>()
    MainScope().launch {
      val item = surahBuilder.child(mediaId)
      val result = if (item == null) {
        Timber.w("onGetItem: No item found for mediaId: %s", mediaId)
        LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
      } else {
        Timber.d("onGetItem: Found item for mediaId: %s", mediaId)
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
    Timber.d("onGetChildren called for parentId: %s, page: %d, pageSize: %d", parentId, page, pageSize)
    val settable = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
    scope.launch {
      val children = surahBuilder.children(parentId)
      Timber.d("onGetChildren: Found %d children for parentId: %s", children.size, parentId)
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
    Timber.d("onAddMediaItems called with %d items", mediaItems.size)
    val settable = SettableFuture.create<List<MediaItem>>()
    scope.launch {
      val items = mediaItems.mapNotNull { surahBuilder.child(it.mediaId) }
      Timber.d("onAddMediaItems: Resolved %d items", items.size)
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
    Timber.d("onSetMediaItems called with %d items, startIndex: %d", mediaItems.size, startIndex)
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
             Timber.d("Handling custom playback for sura: %d, qariId: %d", sura, qariId)
             scope.launch {
               val qari = audioUtils.getQariList(service).firstOrNull { it.id == qariId }
               if (qari != null) {
                 val request = audioUtils.createAudioRequest(
                   start = SuraAyah(sura, 1),
                   end = SuraAyah(sura, quranInfo.getNumberOfAyahs(sura)),
                   qari = qari,
                   enforceRange = true,
                   shouldStream = quranSettings.shouldStream()
                 ) ?: return@launch // should we fallback ?

//                 service.audioRequest = request
//                 service.audioQueue = AudioQueue(quranInfo, request, AudioPlaybackInfo(request.start, 1, 1, request.start.requiresBasmallah()))
////                 // Start playback
//                 service.processPlayRequest()

                 Timber.i("Starting service for audio playback request: %s", request)
                 val intent = Intent(service, AudioService::class.java)
                 intent.setAction(AudioService.ACTION_PLAYBACK)
                 intent.putExtra(AudioService.EXTRA_PLAY_INFO, request)
                 service.startService(intent)
               } else {
                 Timber.w("Could not find qari with id: %d", qariId)
               }
             }
             // Return success, but we handle playback manually
             return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs))
           }
         }
      }
    }
    
    return if (mediaItems.size == 1) {
      Timber.d("Expanding single media item for playback")
      val settable = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
      scope.launch {
        val firstItem = mediaItems.first()
        val items = surahBuilder.expandMediaItem(firstItem.mediaId)
        val index = items.indexOfFirst { it.mediaId == firstItem.mediaId }
        val startPosition = if (index != -1) index else 0
        Timber.d("Expanded to %d items, starting at index %d", items.size, startPosition)
        val result = MediaSession.MediaItemsWithStartPosition(items, startPosition, 0)
        settable.set(result)
      }
      settable
    } else {
      Timber.d("Passing onSetMediaItems to superclass")
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
    Timber.d("onSearch called with query: '%s'", query)
    val settable = SettableFuture.create<LibraryResult<Void>>()
    scope.launch {
      val searchResultCount = surahBuilder.search(query).size
      Timber.d("onSearch: a search for '%s' returned %d results", query, searchResultCount)
      session.notifySearchResultChanged(browser, query, searchResultCount, params)
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
    Timber.d("onGetSearchResult called for query: '%s', page: %d, pageSize: %d", query, page, pageSize)
    val settable = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
    scope.launch {
      val items = surahBuilder.search(query)
      Timber.d("onGetSearchResult: Found %d total items for query '%s'", items.size, query)
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
    Timber.d("onConnect from controller: %s", controller.packageName)
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
