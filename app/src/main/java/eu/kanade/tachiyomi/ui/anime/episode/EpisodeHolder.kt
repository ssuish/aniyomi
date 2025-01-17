package eu.kanade.tachiyomi.ui.anime.episode

import android.text.SpannableStringBuilder
import android.text.SpannedString
import android.view.View
import androidx.core.text.buildSpannedString
import androidx.core.text.color
import androidx.core.view.isVisible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.animesource.LocalAnimeSource
import eu.kanade.tachiyomi.data.database.models.Anime
import eu.kanade.tachiyomi.data.database.models.Episode
import eu.kanade.tachiyomi.databinding.EpisodesItemBinding
import eu.kanade.tachiyomi.ui.anime.episode.base.BaseEpisodeHolder
import eu.kanade.tachiyomi.util.lang.toRelativeString
import java.util.Date
import java.util.concurrent.TimeUnit

class EpisodeHolder(
    view: View,
    private val adapter: EpisodesAdapter,
) : BaseEpisodeHolder(view, adapter) {

    private val binding = EpisodesItemBinding.bind(view)

    init {
        binding.animedownload.setOnClickListener {
            onAnimeDownloadClick(it, bindingAdapterPosition)
        }
        binding.animedownload.setOnLongClickListener {
            onAnimeDownloadLongClick(it, bindingAdapterPosition)
            true
        }
    }

    fun bind(item: EpisodeItem, anime: Anime) {
        val episode = item.episode

        binding.episodeTitle.text = when (anime.displayMode) {
            Anime.EPISODE_DISPLAY_NUMBER -> {
                val number = adapter.decimalFormat.format(episode.episode_number.toDouble())
                itemView.context.getString(R.string.display_mode_episode, number)
            }
            else -> episode.name
            // TODO: show cleaned name consistently around the app
            // else -> cleanEpisodeName(episode, anime)
        }

        // Set correct text color
        val episodeTitleColor = when {
            episode.seen -> adapter.readColor
            episode.bookmark -> adapter.bookmarkedColor
            else -> adapter.unreadColor
        }
        binding.episodeTitle.setTextColor(episodeTitleColor)

        val episodeDescriptionColor = when {
            episode.seen -> adapter.readColor
            episode.bookmark -> adapter.bookmarkedColor
            else -> adapter.unreadColorSecondary
        }
        binding.episodeDescription.setTextColor(episodeDescriptionColor)

        binding.bookmarkIcon.isVisible = episode.bookmark

        val descriptions = mutableListOf<CharSequence>()

        if (episode.date_upload > 0) {
            descriptions.add(Date(episode.date_upload).toRelativeString(itemView.context, adapter.relativeTime, adapter.dateFormat))
        }
        if (!episode.seen && episode.last_second_seen > 0) {
            val lastSecondSeen: SpannedString
            if (episode.total_seconds > 3600000) {
                lastSecondSeen = buildSpannedString {
                    color(adapter.readColor) {
                        append(
                            itemView.context.getString(
                                R.string.episode_progress,
                                String.format(
                                    "%d:%02d:%02d",
                                    TimeUnit.MILLISECONDS.toHours(episode.last_second_seen),
                                    TimeUnit.MILLISECONDS.toMinutes(episode.last_second_seen) -
                                        TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(episode.last_second_seen)),
                                    TimeUnit.MILLISECONDS.toSeconds(episode.last_second_seen) -
                                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(episode.last_second_seen)),
                                ),
                                String.format(
                                    "%d:%02d:%02d",
                                    TimeUnit.MILLISECONDS.toHours(episode.total_seconds),
                                    TimeUnit.MILLISECONDS.toMinutes(episode.total_seconds) -
                                        TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(episode.total_seconds)),
                                    TimeUnit.MILLISECONDS.toSeconds(episode.total_seconds) -
                                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(episode.total_seconds)),
                                ),
                            ),
                        )
                    }
                }
            } else {
                lastSecondSeen = buildSpannedString {
                    color(adapter.readColor) {
                        append(
                            itemView.context.getString(
                                R.string.episode_progress,
                                String.format(
                                    "%d:%02d",
                                    TimeUnit.MILLISECONDS.toMinutes(episode.last_second_seen),
                                    TimeUnit.MILLISECONDS.toSeconds(episode.last_second_seen) -
                                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(episode.last_second_seen)),
                                ),
                                String.format(
                                    "%d:%02d",
                                    TimeUnit.MILLISECONDS.toMinutes(episode.total_seconds),
                                    TimeUnit.MILLISECONDS.toSeconds(episode.total_seconds) -
                                        TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(episode.total_seconds)),
                                ),
                            ),
                        )
                    }
                }
            }
            descriptions.add(lastSecondSeen)
        }
        if (!episode.scanlator.isNullOrBlank()) {
            descriptions.add(episode.scanlator!!)
        }

        if (descriptions.isNotEmpty()) {
            binding.episodeDescription.text = descriptions.joinTo(SpannableStringBuilder(), " • ")
        } else {
            binding.episodeDescription.text = ""
        }

        binding.animedownload.isVisible = item.anime.source != LocalAnimeSource.ID
        binding.animedownload.setState(item.status, item.progress)
    }

    private fun cleanEpisodeName(episode: Episode, anime: Anime): String {
        return episode.name
            .trim()
            .removePrefix(anime.title)
            .trim(*EPISODE_TRIM_CHARS)
    }
}

private val EPISODE_TRIM_CHARS = arrayOf(
    // Whitespace
    ' ',
    '\u0009',
    '\u000A',
    '\u000B',
    '\u000C',
    '\u000D',
    '\u0020',
    '\u0085',
    '\u00A0',
    '\u1680',
    '\u2000',
    '\u2001',
    '\u2002',
    '\u2003',
    '\u2004',
    '\u2005',
    '\u2006',
    '\u2007',
    '\u2008',
    '\u2009',
    '\u200A',
    '\u2028',
    '\u2029',
    '\u202F',
    '\u205F',
    '\u3000',

    // Separators
    '-',
    '_',
    ',',
    ':',
).toCharArray()
