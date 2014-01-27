/*
 * Copyright 2014 Uwe Trottmann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.battlelancer.seriesguide.ui;

import com.actionbarsherlock.app.SherlockFragmentActivity;
import com.battlelancer.seriesguide.enums.TraktAction;
import com.battlelancer.seriesguide.getglueapi.GetGlueCheckin;
import com.battlelancer.seriesguide.provider.SeriesContract.Episodes;
import com.battlelancer.seriesguide.provider.SeriesContract.Shows;
import com.battlelancer.seriesguide.settings.GetGlueSettings;
import com.battlelancer.seriesguide.settings.TraktSettings;
import com.battlelancer.seriesguide.ui.dialogs.GenericCheckInDialogFragment;
import com.battlelancer.seriesguide.ui.dialogs.TraktCancelCheckinDialogFragment;
import com.battlelancer.seriesguide.util.ShareUtils.ProgressDialog;
import com.battlelancer.seriesguide.util.TraktTask;
import com.battlelancer.seriesguide.util.Utils;
import com.uwetrottmann.androidutils.AndroidUtils;
import com.uwetrottmann.seriesguide.R;

import android.annotation.TargetApi;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.text.TextUtils;
import android.widget.Toast;

import de.greenrobot.event.EventBus;

/**
 * Blank activity, just used to quickly check into a show/episode on GetGlue/trakt.
 */
public class QuickCheckInActivity extends SherlockFragmentActivity {

    private static final String TAG_PROGRESS_FRAGMENT = "progress-dialog";

    public interface InitBundle {

        String EPISODE_TVDBID = "episode_tvdbid";
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    @Override
    protected void onCreate(Bundle arg0) {
        // make the activity show the wallpaper, nothing else
        if (AndroidUtils.isHoneycombOrHigher()) {
            setTheme(android.R.style.Theme_Holo_Wallpaper_NoTitleBar);
        } else {
            setTheme(android.R.style.Theme_Translucent_NoTitleBar);
        }
        super.onCreate(arg0);

        int episodeId = getIntent().getIntExtra(InitBundle.EPISODE_TVDBID, 0);
        if (episodeId == 0) {
            finish();
            return;
        }

        final Cursor episodeWithShow = getContentResolver()
                .query(Episodes.buildEpisodeWithShowUri(String.valueOf(episodeId)),
                        new String[]{
                                Episodes._ID, Episodes.TITLE, Episodes.SEASON, Episodes.NUMBER,
                                Shows.REF_SHOW_ID, Shows.TITLE
                        },
                        null,
                        null,
                        null);
        if (episodeWithShow == null) {
            finish();
            return;
        }

        if (!episodeWithShow.moveToFirst()) {
            episodeWithShow.close();
            finish();
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);

        // get values
        String title = episodeWithShow.getString(1);
        int season = episodeWithShow.getInt(2);
        int episode = episodeWithShow.getInt(3);
        int showTvdbId = episodeWithShow.getInt(4);
        episodeWithShow.close();
        String defaultMessage = Utils.getNextEpisodeString(this, season, episode, title);

        // get share service enabled settings
        boolean isShareWithGetGlue = GetGlueSettings.isSharingWithGetGlue(this);
        boolean isShareWithTrakt = TraktSettings.isSharingWithTrakt(this);

        if (isShareWithTrakt) {
            // We want to remove any currently showing
            // dialog, so make our own transaction and
            // take care of that here.
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            Fragment prev = getSupportFragmentManager().findFragmentByTag(TAG_PROGRESS_FRAGMENT);
            if (prev != null) {
                ft.remove(prev);
            }
            ProgressDialog newFragment = ProgressDialog.newInstance();
            newFragment.show(ft, TAG_PROGRESS_FRAGMENT);

            // check in with trakt
            AndroidUtils.executeAsyncTask(
                    new TraktTask(this)
                            .checkInEpisode(showTvdbId, season, episode, defaultMessage)
            );
        }

        if (isShareWithGetGlue) {
            boolean isAbortingCheckIn = false;
            String objectId = null;

            // require GetGlue authentication
            if (!GetGlueSettings.isAuthenticated(this)) {
                isAbortingCheckIn = true;
            } else {
                Cursor show = getContentResolver().query(
                        Shows.buildShowUri(String.valueOf(showTvdbId)), new String[]{
                        Shows._ID, Shows.GETGLUEID
                }, null, null, null);
                if (show != null) {
                    show.moveToFirst();
                    objectId = show.getString(1);
                    show.close();
                }

                // check for GetGlue id
                if (TextUtils.isEmpty(objectId)) {
                    isAbortingCheckIn = true;
                }
            }

            if (!isAbortingCheckIn) {
                // check in, use task on thread pool
                AndroidUtils.executeAsyncTask(new GetGlueCheckin.GetGlueCheckInTask(objectId, defaultMessage,
                        this));
            }
        }

        if (!isShareWithTrakt) {
            if (!isShareWithGetGlue) {
                // No service is setup, direct user to within SeriesGuide
                Toast.makeText(this, R.string.checkin_noaction, Toast.LENGTH_LONG).show();
                Intent intent = new Intent(this, EpisodesActivity.class);
                intent.putExtra(EpisodesActivity.InitBundle.EPISODE_TVDBID, episodeId);
                startActivity(intent);
            }

            // GetGlue check-ins do not need an activity to stay active
            finish();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventBus.getDefault().register(this);
    }

    @Override
    protected void onStop() {
        super.onStop();

        EventBus.getDefault().unregister(this);
    }

    public void onEvent(TraktTask.TraktActionCompleteEvent event) {
        // wrap up and finish
        if (event.mTraktAction == TraktAction.CHECKIN_EPISODE) {
            event.handle(this);
            dismissProgressDialog();
            finish();
        }
    }

    public void onEvent(TraktTask.TraktCheckInBlockedEvent event) {
        GenericCheckInDialogFragment.dismissProgressDialog(getSupportFragmentManager());
        TraktCancelCheckinDialogFragment newFragment = TraktCancelCheckinDialogFragment
                .newInstance(event.traktTaskArgs, event.waitMinutes);
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
        newFragment.show(ft, "cancel-checkin-dialog");
    }

    private void dismissProgressDialog() {
        // dismiss a potential progress dialog
        Fragment prev = getSupportFragmentManager().findFragmentByTag(TAG_PROGRESS_FRAGMENT);
        if (prev != null) {
            FragmentTransaction ft = getSupportFragmentManager().beginTransaction();
            ft.remove(prev);
            ft.commit();
        }
    }

}
