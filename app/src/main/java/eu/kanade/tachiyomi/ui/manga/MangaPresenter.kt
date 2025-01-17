package eu.kanade.tachiyomi.ui.manga

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.jakewharton.rxrelay.PublishRelay
import eu.kanade.domain.category.interactor.GetCategories
import eu.kanade.domain.chapter.interactor.GetChapterByMangaId
import eu.kanade.domain.chapter.model.toDbChapter
import eu.kanade.domain.manga.interactor.GetDuplicateLibraryManga
import eu.kanade.domain.manga.model.toDbManga
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaCategory
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.toDomainManga
import eu.kanade.tachiyomi.data.database.models.toMangaInfo
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.saver.Image
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.data.track.AnimeTrackService
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.toSChapter
import eu.kanade.tachiyomi.source.model.toSManga
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.util.chapter.ChapterSettingsHelper
import eu.kanade.tachiyomi.util.chapter.getChapterSort
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithTrackServiceTwoWay
import eu.kanade.tachiyomi.util.editCover
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.launchUI
import eu.kanade.tachiyomi.util.lang.withUIContext
import eu.kanade.tachiyomi.util.prepUpdateCover
import eu.kanade.tachiyomi.util.removeCovers
import eu.kanade.tachiyomi.util.shouldDownloadNewChapters
import eu.kanade.tachiyomi.util.system.logcat
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.updateCoverLastModified
import eu.kanade.tachiyomi.widget.ExtendedNavigationView.Item.TriStateGroup.State
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.supervisorScope
import logcat.LogPriority
import rx.Observable
import rx.Subscription
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.util.Date
import eu.kanade.domain.category.model.Category as DomainCategory

class MangaPresenter(
    val manga: Manga,
    val source: Source,
    val preferences: PreferencesHelper = Injekt.get(),
    private val db: DatabaseHelper = Injekt.get(),
    private val trackManager: TrackManager = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val getChapterByMangaId: GetChapterByMangaId = Injekt.get(),
    private val getDuplicateLibraryManga: GetDuplicateLibraryManga = Injekt.get(),
    private val getCategories: GetCategories = Injekt.get(),
) : BasePresenter<MangaController>() {

    /**
     * Subscription to update the manga from the source.
     */
    private var fetchMangaJob: Job? = null

    var allChapters: List<ChapterItem> = emptyList()
        private set
    var filteredAndSortedChapters: List<ChapterItem> = emptyList()
        private set

    /**
     * Subject of list of chapters to allow updating the view without going to DB.
     */
    private val chaptersRelay by lazy { PublishRelay.create<List<ChapterItem>>() }

    /**
     * Whether the chapter list has been requested to the source.
     */
    var hasRequested = false
        private set

    /**
     * Subscription to retrieve the new list of chapters from the source.
     */
    private var fetchChaptersJob: Job? = null

    /**
     * Subscription to observe download status changes.
     */
    private var observeDownloadsStatusSubscription: Subscription? = null
    private var observeDownloadsPageSubscription: Subscription? = null

    private var _trackList: List<TrackItem> = emptyList()
    val trackList get() = _trackList

    private val loggedServices by lazy { trackManager.services.filter { it.isLogged && it !is AnimeTrackService } }

    private val imageSaver: ImageSaver by injectLazy()

    private var trackSubscription: Subscription? = null
    private var searchTrackerJob: Job? = null
    private var refreshTrackersJob: Job? = null

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        if (!manga.favorite) {
            ChapterSettingsHelper.applySettingDefaults(manga)
        }

        // Manga info - start

        getMangaObservable()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeLatestCache({ view, manga -> view.onNextMangaInfo(manga, source) })

        getTrackingObservable()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeLatestCache(MangaController::onTrackingCount) { _, error ->
                logcat(LogPriority.ERROR, error)
            }

        // Prepare the relay.
        chaptersRelay.flatMap { applyChapterFilters(it) }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeLatestCache(
                { _, chapters ->
                    filteredAndSortedChapters = chapters
                    view?.onNextChapters(chapters)
                },
                { _, error -> logcat(LogPriority.ERROR, error) },
            )

        // Manga info - end

        // Chapters list - start

        // Keeps subscribed to changes and sends the list of chapters to the relay.
        presenterScope.launchIO {
            manga.id?.let { mangaId ->
                getChapterByMangaId.subscribe(mangaId)
                    .collectLatest { domainChapters ->
                        val chapterItems = domainChapters.map { it.toDbChapter().toModel() }
                        setDownloadedChapters(chapterItems)
                        this@MangaPresenter.allChapters = chapterItems
                        observeDownloads()
                        chaptersRelay.call(chapterItems)
                    }
            }
        }

        // Chapters list - end

        fetchTrackers()
    }

    suspend fun getDuplicateLibraryManga(manga: Manga): Manga? {
        return getDuplicateLibraryManga.await(manga.title, manga.source)?.toDbManga()
    }

    // Manga info - start

    private fun getMangaObservable(): Observable<Manga> {
        return db.getManga(manga.url, manga.source).asRxObservable()
    }

    private fun getTrackingObservable(): Observable<Int> {
        if (!trackManager.hasLoggedMangaServices()) {
            return Observable.just(0)
        }

        return db.getTracks(manga.id).asRxObservable()
            .map { tracks ->
                val loggedServices = trackManager.services.filter { it.isLogged }.map { it.id }
                tracks.filter { it.sync_id in loggedServices }
            }
            .map { it.size }
    }

    /**
     * Fetch manga information from source.
     */
    fun fetchMangaFromSource(manualFetch: Boolean = false) {
        if (fetchMangaJob?.isActive == true) return
        fetchMangaJob = presenterScope.launchIO {
            try {
                val networkManga = source.getMangaDetails(manga.toMangaInfo())
                val sManga = networkManga.toSManga()
                manga.prepUpdateCover(coverCache, sManga, manualFetch)
                manga.copyFrom(sManga)
                if (!manga.favorite) {
                    // if the manga isn't a favorite, set its title from source and update in db
                    manga.title = sManga.title
                }
                manga.initialized = true
                db.insertManga(manga).executeAsBlocking()

                withUIContext { view?.onFetchMangaInfoDone() }
            } catch (e: Throwable) {
                withUIContext { view?.onFetchMangaInfoError(e) }
            }
        }
    }

    /**
     * Update favorite status of manga, (removes / adds) manga (to / from) library.
     *
     * @return the new status of the manga.
     */
    fun toggleFavorite(): Boolean {
        manga.favorite = !manga.favorite
        manga.date_added = when (manga.favorite) {
            true -> Date().time
            false -> 0
        }
        if (!manga.favorite) {
            manga.removeCovers(coverCache)
        }
        db.insertManga(manga).executeAsBlocking()
        return manga.favorite
    }

    /**
     * Returns true if the manga has any downloads.
     */
    fun hasDownloads(): Boolean {
        return downloadManager.getDownloadCount(manga) > 0
    }

    /**
     * Deletes all the downloads for the manga.
     */
    fun deleteDownloads() {
        downloadManager.deleteManga(manga, source)
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    suspend fun getCategories(): List<DomainCategory> {
        return getCategories.subscribe().firstOrNull() ?: emptyList()
    }

    /**
     * Gets the category id's the manga is in, if the manga is not in a category, returns the default id.
     *
     * @param manga the manga to get categories from.
     * @return Array of category ids the manga is in, if none returns default id
     */
    fun getMangaCategoryIds(manga: Manga): Array<Long> {
        val categories = db.getCategoriesForManga(manga).executeAsBlocking()
        return categories.mapNotNull { it?.id?.toLong() }.toTypedArray()
    }

    /**
     * Move the given manga to categories.
     *
     * @param manga the manga to move.
     * @param categories the selected categories.
     */
    fun moveMangaToCategories(manga: Manga, categories: List<Category>) {
        val mc = categories.filter { it.id != 0 }.map { MangaCategory.create(manga, it) }
        db.setMangaCategories(mc, listOf(manga))
    }

    /**
     * Move the given manga to the category.
     *
     * @param manga the manga to move.
     * @param category the selected category, or null for default category.
     */
    fun moveMangaToCategory(manga: Manga, category: Category?) {
        moveMangaToCategories(manga, listOfNotNull(category))
    }

    /**
     * Save manga cover Bitmap to picture or temporary share directory.
     *
     * @param image the image with specified location
     * @return flow Flow which emits the Uri which specifies where the image is saved when
     */
    fun saveImage(image: Image): Uri {
        return imageSaver.save(image)
    }

    /**
     * Update cover with local file.
     *
     * @param context Context.
     * @param data uri of the cover resource.
     */
    fun editCover(context: Context, data: Uri) {
        presenterScope.launchIO {
            context.contentResolver.openInputStream(data)?.use {
                try {
                    val result = manga.toDomainManga()!!.editCover(context, it)
                    launchUI { if (result) view?.onSetCoverSuccess() }
                } catch (e: Exception) {
                    launchUI { view?.onSetCoverError(e) }
                }
            }
        }
    }

    fun deleteCustomCover(manga: Manga) {
        Observable
            .fromCallable {
                coverCache.deleteCustomCover(manga.id)
                manga.updateCoverLastModified(db)
            }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeFirst(
                { view, _ -> view.onSetCoverSuccess() },
                { view, e -> view.onSetCoverError(e) },
            )
    }

    // Manga info - end

    // Chapters list - start

    private fun observeDownloads() {
        observeDownloadsStatusSubscription?.let { remove(it) }
        observeDownloadsStatusSubscription = downloadManager.queue.getStatusObservable()
            .observeOn(Schedulers.io())
            .onBackpressureBuffer()
            .filter { download -> download.manga.id == manga.id }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeLatestCache(
                { view, it ->
                    onDownloadStatusChange(it)
                    view.onChapterDownloadUpdate(it)
                },
                { _, error ->
                    logcat(LogPriority.ERROR, error)
                },
            )

        observeDownloadsPageSubscription?.let { remove(it) }
        observeDownloadsPageSubscription = downloadManager.queue.getProgressObservable()
            .observeOn(Schedulers.io())
            .onBackpressureBuffer()
            .filter { download -> download.manga.id == manga.id }
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeLatestCache(MangaController::onChapterDownloadUpdate) { _, error ->
                logcat(LogPriority.ERROR, error)
            }
    }

    /**
     * Converts a chapter from the database to an extended model, allowing to store new fields.
     */
    private fun Chapter.toModel(): ChapterItem {
        // Create the model object.
        val model = ChapterItem(this, manga)

        // Find an active download for this chapter.
        val download = downloadManager.queue.find { it.chapter.id == id }

        if (download != null) {
            // If there's an active download, assign it.
            model.download = download
        }
        return model
    }

    /**
     * Finds and assigns the list of downloaded chapters.
     *
     * @param chapters the list of chapter from the database.
     */
    private fun setDownloadedChapters(chapters: List<ChapterItem>) {
        chapters
            .filter { downloadManager.isChapterDownloaded(it, manga) }
            .forEach { it.status = Download.State.DOWNLOADED }
    }

    /**
     * Requests an updated list of chapters from the source.
     */
    fun fetchChaptersFromSource(manualFetch: Boolean = false) {
        hasRequested = true

        if (fetchChaptersJob?.isActive == true) return
        fetchChaptersJob = presenterScope.launchIO {
            try {
                val chapters = source.getChapterList(manga.toMangaInfo())
                    .map { it.toSChapter() }

                val (newChapters, _) = syncChaptersWithSource(chapters, manga, source)
                if (manualFetch) {
                    downloadNewChapters(newChapters)
                }

                withUIContext { view?.onFetchChaptersDone() }
            } catch (e: Throwable) {
                withUIContext { view?.onFetchChaptersError(e) }
            }
        }
    }

    /**
     * Updates the UI after applying the filters.
     */
    private fun refreshChapters() {
        chaptersRelay.call(allChapters)
    }

    /**
     * Applies the view filters to the list of chapters obtained from the database.
     * @param chapters the list of chapters from the database
     * @return an observable of the list of chapters filtered and sorted.
     */
    private fun applyChapterFilters(chapters: List<ChapterItem>): Observable<List<ChapterItem>> {
        var observable = Observable.from(chapters).subscribeOn(Schedulers.io())

        val unreadFilter = onlyUnread()
        if (unreadFilter == State.INCLUDE) {
            observable = observable.filter { !it.read }
        } else if (unreadFilter == State.EXCLUDE) {
            observable = observable.filter { it.read }
        }

        val downloadedFilter = onlyDownloaded()
        if (downloadedFilter == State.INCLUDE) {
            observable = observable.filter { it.isDownloaded || it.manga.isLocal() }
        } else if (downloadedFilter == State.EXCLUDE) {
            observable = observable.filter { !it.isDownloaded && !it.manga.isLocal() }
        }

        val bookmarkedFilter = onlyBookmarked()
        if (bookmarkedFilter == State.INCLUDE) {
            observable = observable.filter { it.bookmark }
        } else if (bookmarkedFilter == State.EXCLUDE) {
            observable = observable.filter { !it.bookmark }
        }

        return observable.toSortedList(getChapterSort(manga))
    }

    /**
     * Called when a download for the active manga changes status.
     * @param download the download whose status changed.
     */
    private fun onDownloadStatusChange(download: Download) {
        // Assign the download to the model object.
        if (download.status == Download.State.QUEUE) {
            allChapters.find { it.id == download.chapter.id }?.let {
                if (it.download == null) {
                    it.download = download
                }
            }
        }

        // Force UI update if downloaded filter active and download finished.
        if (onlyDownloaded() != State.IGNORE && download.status == Download.State.DOWNLOADED) {
            refreshChapters()
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): ChapterItem? {
        return if (sortDescending()) {
            return filteredAndSortedChapters.findLast { !it.read }
        } else {
            filteredAndSortedChapters.find { !it.read }
        }
    }

    fun getUnreadChaptersSorted(): List<ChapterItem> {
        val chapters = allChapters
            .sortedWith(getChapterSort(manga))
            .filter { !it.read && it.status == Download.State.NOT_DOWNLOADED }
            .distinctBy { it.name }
        return if (sortDescending()) {
            chapters.reversed()
        } else {
            chapters
        }
    }

    fun startDownloadingNow(chapter: Chapter) {
        downloadManager.startDownloadNow(chapter.id)
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param selectedChapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(selectedChapters: List<ChapterItem>, read: Boolean) {
        val chapters = selectedChapters.map { chapter ->
            chapter.read = read
            if (!read) {
                chapter.last_page_read = 0
            }
            chapter
        }

        launchIO {
            db.updateChaptersProgress(chapters).executeAsBlocking()

            if (preferences.removeAfterMarkedAsRead()) {
                deleteChapters(chapters.filter { it.read })
            }
        }
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    fun downloadChapters(chapters: List<Chapter>) {
        downloadManager.downloadChapters(manga, chapters)
    }

    /**
     * Bookmarks the given list of chapters.
     * @param selectedChapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(selectedChapters: List<ChapterItem>, bookmarked: Boolean) {
        launchIO {
            selectedChapters
                .forEach {
                    it.bookmark = bookmarked
                    db.updateChapterProgress(it).executeAsBlocking()
                }
        }
    }

    /**
     * Deletes the given list of chapter.
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<ChapterItem>) {
        launchIO {
            try {
                downloadManager.deleteChapters(chapters, manga, source).forEach {
                    if (it is ChapterItem) {
                        it.status = Download.State.NOT_DOWNLOADED
                        it.download = null
                    }
                }

                if (onlyDownloaded() != State.IGNORE) {
                    refreshChapters()
                }

                view?.onChaptersDeleted(chapters)
            } catch (e: Throwable) {
                view?.onChaptersDeletedError(e)
            }
        }
    }

    private fun downloadNewChapters(chapters: List<Chapter>) {
        if (chapters.isEmpty() || !manga.shouldDownloadNewChapters(db, preferences)) return

        downloadChapters(chapters)
    }

    /**
     * Reverses the sorting and requests an UI update.
     */
    fun reverseSortOrder() {
        manga.setChapterOrder(if (sortDescending()) Manga.CHAPTER_SORT_ASC else Manga.CHAPTER_SORT_DESC)
        db.updateChapterFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Sets the read filter and requests an UI update.
     * @param state whether to display only unread chapters or all chapters.
     */
    fun setUnreadFilter(state: State) {
        manga.readFilter = when (state) {
            State.IGNORE -> Manga.SHOW_ALL
            State.INCLUDE -> Manga.CHAPTER_SHOW_UNREAD
            State.EXCLUDE -> Manga.CHAPTER_SHOW_READ
        }
        db.updateChapterFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Sets the download filter and requests an UI update.
     * @param state whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(state: State) {
        manga.downloadedFilter = when (state) {
            State.IGNORE -> Manga.SHOW_ALL
            State.INCLUDE -> Manga.CHAPTER_SHOW_DOWNLOADED
            State.EXCLUDE -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
        }
        db.updateChapterFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Sets the bookmark filter and requests an UI update.
     * @param state whether to display only bookmarked chapters or all chapters.
     */
    fun setBookmarkedFilter(state: State) {
        manga.bookmarkedFilter = when (state) {
            State.IGNORE -> Manga.SHOW_ALL
            State.INCLUDE -> Manga.CHAPTER_SHOW_BOOKMARKED
            State.EXCLUDE -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }
        db.updateChapterFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Sets the active display mode.
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Int) {
        manga.displayMode = mode
        db.updateChapterFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Sets the sorting method and requests an UI update.
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Int) {
        manga.sorting = sort
        db.updateChapterFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Whether downloaded only mode is enabled.
     */
    private fun forceDownloaded(): Boolean {
        return manga.favorite && preferences.downloadedOnly().get()
    }

    /**
     * Whether the display only downloaded filter is enabled.
     */
    private fun onlyDownloaded(): State {
        if (forceDownloaded()) {
            return State.INCLUDE
        }
        return when (manga.downloadedFilter) {
            Manga.CHAPTER_SHOW_DOWNLOADED -> State.INCLUDE
            Manga.CHAPTER_SHOW_NOT_DOWNLOADED -> State.EXCLUDE
            else -> State.IGNORE
        }
    }

    /**
     * Whether the display only downloaded filter is enabled.
     */
    private fun onlyBookmarked(): State {
        return when (manga.bookmarkedFilter) {
            Manga.CHAPTER_SHOW_BOOKMARKED -> State.INCLUDE
            Manga.CHAPTER_SHOW_NOT_BOOKMARKED -> State.EXCLUDE
            else -> State.IGNORE
        }
    }

    /**
     * Whether the display only unread filter is enabled.
     */
    private fun onlyUnread(): State {
        return when (manga.readFilter) {
            Manga.CHAPTER_SHOW_UNREAD -> State.INCLUDE
            Manga.CHAPTER_SHOW_READ -> State.EXCLUDE
            else -> State.IGNORE
        }
    }

    /**
     * Whether the sorting method is descending or ascending.
     */
    fun sortDescending(): Boolean {
        return manga.sortDescending()
    }

    // Chapters list - end

    // Track sheet - start

    private fun fetchTrackers() {
        trackSubscription?.let { remove(it) }
        trackSubscription = db.getTracks(manga.id)
            .asRxObservable()
            .map { tracks ->
                loggedServices.map { service ->
                    TrackItem(tracks.find { it.sync_id == service.id }, service)
                }
            }
            .observeOn(AndroidSchedulers.mainThread())
            .doOnNext { _trackList = it }
            .subscribeLatestCache(MangaController::onNextTrackers)
    }

    fun refreshTrackers() {
        refreshTrackersJob?.cancel()
        refreshTrackersJob = launchIO {
            supervisorScope {
                try {
                    trackList
                        .filter { it.track != null }
                        .map {
                            async {
                                val track = it.service.refresh(it.track!!)
                                db.insertTrack(track).executeAsBlocking()

                                if (it.service is EnhancedTrackService) {
                                    syncChaptersWithTrackServiceTwoWay(db, allChapters, track, it.service)
                                }
                            }
                        }
                        .awaitAll()
                } catch (e: Throwable) {
                    withUIContext { view?.onTrackingRefreshError(e) }
                }
            }
        }
    }

    fun trackingSearch(query: String, service: TrackService) {
        searchTrackerJob?.cancel()
        searchTrackerJob = launchIO {
            try {
                val results = service.search(query)
                withUIContext { view?.onTrackingSearchResults(results) }
            } catch (e: Throwable) {
                withUIContext { view?.onTrackingSearchResultsError(e) }
            }
        }
    }

    fun registerTracking(item: Track?, service: TrackService) {
        if (item != null) {
            item.manga_id = manga.id!!
            launchIO {
                try {
                    val hasReadChapters = allChapters.any { it.read }
                    service.bind(item, hasReadChapters)
                    db.insertTrack(item).executeAsBlocking()

                    if (service is EnhancedTrackService) {
                        syncChaptersWithTrackServiceTwoWay(db, allChapters, item, service)
                    }
                } catch (e: Throwable) {
                    withUIContext { view?.applicationContext?.toast(e.message) }
                }
            }
        } else {
            unregisterTracking(service)
        }
    }

    fun unregisterTracking(service: TrackService) {
        db.deleteTrackForManga(manga, service).executeAsBlocking()
    }

    private fun updateRemote(track: Track, service: TrackService) {
        launchIO {
            try {
                service.update(track)
                db.insertTrack(track).executeAsBlocking()
            } catch (e: Throwable) {
                withUIContext { view?.onTrackingRefreshError(e) }

                // Restart on error to set old values
                fetchTrackers()
            }
        }
    }

    fun setTrackerStatus(item: TrackItem, index: Int) {
        val track = item.track!!
        track.status = item.service.getStatusList()[index]
        if (track.status == item.service.getCompletionStatus() && track.total_chapters != 0) {
            track.last_chapter_read = track.total_chapters.toFloat()
        }
        updateRemote(track, item.service)
    }

    fun setTrackerScore(item: TrackItem, index: Int) {
        val track = item.track!!
        track.score = item.service.indexToScore(index)
        updateRemote(track, item.service)
    }

    fun setTrackerLastChapterRead(item: TrackItem, chapterNumber: Int) {
        val track = item.track!!
        if (track.last_chapter_read == 0F && track.last_chapter_read < chapterNumber && track.status != item.service.getRereadingStatus()) {
            track.status = item.service.getReadingStatus()
        }
        track.last_chapter_read = chapterNumber.toFloat()
        if (track.total_chapters != 0 && track.last_chapter_read.toInt() == track.total_chapters) {
            track.status = item.service.getCompletionStatus()
        }
        updateRemote(track, item.service)
    }

    fun setTrackerStartDate(item: TrackItem, date: Long) {
        val track = item.track!!
        track.started_reading_date = date
        updateRemote(track, item.service)
    }

    fun setTrackerFinishDate(item: TrackItem, date: Long) {
        val track = item.track!!
        track.finished_reading_date = date
        updateRemote(track, item.service)
    }

    // Track sheet - end
}
