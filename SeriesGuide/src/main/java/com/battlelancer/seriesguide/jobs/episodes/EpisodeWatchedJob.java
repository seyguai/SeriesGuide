package com.battlelancer.seriesguide.jobs.episodes;

import android.content.Context;
import android.database.Cursor;
import com.battlelancer.seriesguide.R;
import com.battlelancer.seriesguide.appwidget.ListWidgetProvider;
import com.battlelancer.seriesguide.provider.SeriesGuideContract;
import com.battlelancer.seriesguide.util.ActivityTools;
import com.battlelancer.seriesguide.util.EpisodeTools;
import com.battlelancer.seriesguide.util.TextTools;
import com.uwetrottmann.seriesguide.backend.episodes.model.Episode;

public class EpisodeWatchedJob extends EpisodeBaseJob {

    public EpisodeWatchedJob(Context context, int showTvdbId, int episodeTvdbId, int season,
            int episode, int episodeFlags) {
        super(context, showTvdbId, episodeTvdbId, season, episode, episodeFlags,
                JobAction.EPISODE_WATCHED);
    }

    @Override
    protected void setHexagonFlag(Episode episode) {
        episode.setWatchedFlag(getFlagValue());
    }

    @Override
    protected String getDatabaseColumnToUpdate() {
        return SeriesGuideContract.Episodes.WATCHED;
    }

    private int getLastWatchedEpisodeTvdbId() {
        if (!EpisodeTools.isUnwatched(getFlagValue())) {
            return episodeTvdbId; // watched or skipped episode
        } else {
            // unwatched episode
            int lastWatchedId = -1; // don't change last watched episode by default

            // if modified episode is identical to last watched one (e.g. was just watched),
            // find an appropriate last watched episode
            final Cursor show = getContext().getContentResolver().query(
                    SeriesGuideContract.Shows.buildShowUri(String.valueOf(getShowTvdbId())),
                    new String[] {
                            SeriesGuideContract.Shows._ID,
                            SeriesGuideContract.Shows.LASTWATCHEDID
                    }, null, null, null
            );
            if (show != null) {
                // identical to last watched episode?
                if (show.moveToFirst() && show.getInt(1) == episodeTvdbId) {
                    if (season == 0) {
                        // keep last watched (= this episode) if we got a special
                        show.close();
                        return -1;
                    }
                    lastWatchedId = 0; // re-set if we don't find one

                    // get latest watched before this one
                    String season = String.valueOf(this.season);
                    final Cursor latestWatchedEpisode = getContext().getContentResolver()
                            .query(SeriesGuideContract.Episodes.buildEpisodesOfShowUri(String
                                            .valueOf(getShowTvdbId())),
                                    BaseJob.PROJECTION_EPISODE,
                                    SeriesGuideContract.Episodes.SELECTION_PREVIOUS_WATCHED,
                                    new String[] {
                                            season, season, String.valueOf(episode)
                                    }, SeriesGuideContract.Episodes.SORT_PREVIOUS_WATCHED
                            );
                    if (latestWatchedEpisode != null) {
                        if (latestWatchedEpisode.moveToFirst()) {
                            lastWatchedId = latestWatchedEpisode.getInt(0);
                        }

                        latestWatchedEpisode.close();
                    }
                }

                show.close();
            }

            return lastWatchedId;
        }
    }

    @Override
    public boolean applyLocalChanges() {
        if (!super.applyLocalChanges()) {
            return false;
        }

        // set a new last watched episode
        // set last watched time to now if marking as watched or skipped
        boolean unwatched = EpisodeTools.isUnwatched(getFlagValue());
        updateLastWatched(getLastWatchedEpisodeTvdbId(), !unwatched);

        if (EpisodeTools.isWatched(getFlagValue())) {
            // create activity entry for watched episode
            ActivityTools.addActivity(getContext(), episodeTvdbId, getShowTvdbId());
        } else if (unwatched) {
            // remove any previous activity entries for this episode
            // use case: user accidentally toggled watched flag
            ActivityTools.removeActivity(getContext(), episodeTvdbId);
        }

        ListWidgetProvider.notifyAllAppWidgetsViewDataChanged(getContext());

        return true;
    }

    @Override
    public String getConfirmationText() {
        if (EpisodeTools.isSkipped(getFlagValue())) {
            // skipping is not sent to trakt, no need for a message
            return null;
        }

        // show episode seen/unseen message
        String number = TextTools.getEpisodeNumber(getContext(), season, episode);
        return getContext().getString(
                EpisodeTools.isWatched(getFlagValue()) ? R.string.trakt_seen
                        : R.string.trakt_notseen,
                number
        );
    }
}