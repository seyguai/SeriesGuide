package com.battlelancer.seriesguide.jobs.episodes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.support.annotation.CallSuper;
import com.battlelancer.seriesguide.provider.SeriesGuideContract;
import com.uwetrottmann.seriesguide.backend.episodes.model.Episode;
import com.uwetrottmann.trakt5.entities.SyncEpisode;
import com.uwetrottmann.trakt5.entities.SyncSeason;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

public abstract class BaseJob implements EpisodeFlagJob {

    public static final String[] PROJECTION_EPISODE = new String[] {
            SeriesGuideContract.Episodes._ID
    };

    private Context context;
    private int showTvdbId;
    private int flagValue;
    private JobAction action;

    public BaseJob(Context context, int showTvdbId, int flagValue, JobAction action) {
        this.context = context.getApplicationContext();
        this.action = action;
        this.showTvdbId = showTvdbId;
        this.flagValue = flagValue;
    }

    @Override
    public int getShowTvdbId() {
        return showTvdbId;
    }

    @Override
    public int getFlagValue() {
        return flagValue;
    }

    @Override
    public JobAction getAction() {
        return action;
    }

    protected Context getContext() {
        return context;
    }

    protected abstract Uri getDatabaseUri();

    protected abstract String getDatabaseSelection();

    /**
     * Return the column which should get updated, either {@link SeriesGuideContract.Episodes}
     * .WATCHED or {@link SeriesGuideContract.Episodes}.COLLECTED.
     */
    protected abstract String getDatabaseColumnToUpdate();

    /**
     * Set watched or collection property.
     */
    protected abstract void setHexagonFlag(Episode episode);

    /**
     * Builds a list of episodes ready to upload to hexagon. However, the show TVDb id is not
     * set. It should be set in a wrapping {@link com.uwetrottmann.seriesguide.backend.episodes.model.EpisodeList}.
     */
    @Override
    public List<Episode> getEpisodesForHexagon() {
        List<Episode> episodes = new ArrayList<>();

        // determine uri
        Uri uri = getDatabaseUri();
        String selection = getDatabaseSelection();

        // query and add episodes to list
        final Cursor episodeCursor = context.getContentResolver().query(
                uri,
                new String[] {
                        SeriesGuideContract.Episodes.SEASON, SeriesGuideContract.Episodes.NUMBER
                }, selection, null, null
        );
        if (episodeCursor != null) {
            while (episodeCursor.moveToNext()) {
                Episode episode = new Episode();
                setHexagonFlag(episode);
                episode.setSeasonNumber(episodeCursor.getInt(0));
                episode.setEpisodeNumber(episodeCursor.getInt(1));
                episodes.add(episode);
            }
            episodeCursor.close();
        }

        return episodes;
    }

    /**
     * Builds a list of {@link com.uwetrottmann.trakt5.entities.SyncSeason} objects to submit to
     * trakt.
     */
    protected List<SyncSeason> buildTraktEpisodeList() {
        List<SyncSeason> seasons = new ArrayList<>();

        // determine uri
        Uri uri = getDatabaseUri();
        String selection = getDatabaseSelection();

        // query and add episodes to list
        // sort ascending by season, then number for trakt
        final Cursor episodeCursor = context.getContentResolver().query(
                uri,
                new String[] {
                        SeriesGuideContract.Episodes.SEASON, SeriesGuideContract.Episodes.NUMBER
                },
                selection,
                null,
                SeriesGuideContract.Episodes.SORT_SEASON_ASC + ", "
                        + SeriesGuideContract.Episodes.SORT_NUMBER_ASC
        );
        if (episodeCursor != null) {
            SyncSeason currentSeason = null;
            while (episodeCursor.moveToNext()) {
                int seasonNumber = episodeCursor.getInt(0);

                // start new season?
                if (currentSeason == null || seasonNumber > currentSeason.number) {
                    currentSeason = new SyncSeason().number(seasonNumber);
                    currentSeason.episodes = new LinkedList<>();
                    seasons.add(currentSeason);
                }

                // add episode
                currentSeason.episodes.add(new SyncEpisode().number(episodeCursor.getInt(1)));
            }
            episodeCursor.close();
        }

        return seasons;
    }

    /**
     * Builds and executes the database op required to flag episodes in the local database,
     * notifies affected URIs, may update the list widget.
     */
    @Override
    @CallSuper
    public boolean applyLocalChanges() {
        // determine query uri
        Uri uri = getDatabaseUri();
        if (uri == null) {
            return false;
        }

        // build and execute query
        ContentValues values = new ContentValues();
        values.put(getDatabaseColumnToUpdate(), getFlagValue());
        int updated = context.getContentResolver()
                .update(uri, values, getDatabaseSelection(), null);
        if (updated < 0) {
            return false; // -1 means error
        }

        // notify some other URIs for updates
        context.getContentResolver()
                .notifyChange(SeriesGuideContract.Episodes.CONTENT_URI, null);
        context.getContentResolver()
                .notifyChange(SeriesGuideContract.ListItems.CONTENT_WITH_DETAILS_URI, null);

        return true;
    }

    /**
     * Set last watched episode and/or last watched time of a show.
     *
     * @param lastWatchedEpisodeId The last watched episode for a show to save to the database.
     * -1 for no-op.
     * @param setLastWatchedToNow Whether to set the last watched time of a show to now.
     */
    protected final void updateLastWatched(int lastWatchedEpisodeId,
            boolean setLastWatchedToNow) {
        if (lastWatchedEpisodeId != -1 || setLastWatchedToNow) {
            ContentValues values = new ContentValues();
            if (lastWatchedEpisodeId != -1) {
                values.put(SeriesGuideContract.Shows.LASTWATCHEDID, lastWatchedEpisodeId);
            }
            if (setLastWatchedToNow) {
                values.put(SeriesGuideContract.Shows.LASTWATCHED_MS,
                        System.currentTimeMillis());
            }
            context.getContentResolver().update(
                    SeriesGuideContract.Shows.buildShowUri(String.valueOf(showTvdbId)),
                    values, null, null);
        }
    }
}