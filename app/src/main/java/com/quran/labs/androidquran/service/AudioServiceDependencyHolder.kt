package com.quran.labs.androidquran.service

import com.quran.data.core.QuranInfo
import com.quran.data.source.PageProvider
import com.quran.labs.androidquran.common.audio.repository.AudioStatusRepository
import com.quran.labs.androidquran.data.QuranDisplayData
import com.quran.labs.androidquran.data.TimingRepository
import com.quran.labs.androidquran.util.AudioUtils
import com.quran.labs.feature.autoquran.common.BrowsableSurahBuilder
import dev.zacsweers.metro.Inject

class AudioServiceDependencyHolder {
  @Inject
  lateinit var quranInfo: QuranInfo

  @Inject
  lateinit var quranDisplayData: QuranDisplayData

  @Inject
  lateinit var audioUtils: AudioUtils

  @Inject
  lateinit var audioStatusRepository: AudioStatusRepository

  @Inject
  lateinit var timingRepository: TimingRepository

  @Inject
  lateinit var pageProvider: PageProvider

  @Inject
  lateinit var surahBuilder: BrowsableSurahBuilder

  @Inject
  lateinit var quranFileUtils: com.quran.labs.androidquran.util.QuranFileUtils
}
