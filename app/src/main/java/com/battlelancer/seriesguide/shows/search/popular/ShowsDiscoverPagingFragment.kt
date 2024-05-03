// SPDX-License-Identifier: Apache-2.0
// Copyright 2018-2024 Uwe Trottmann

package com.battlelancer.seriesguide.shows.search.popular

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import com.battlelancer.seriesguide.R
import com.battlelancer.seriesguide.databinding.ActivityDiscoverShowsBinding
import com.battlelancer.seriesguide.databinding.FragmentShowsPopularBinding
import com.battlelancer.seriesguide.shows.search.discover.BaseAddShowsFragment
import com.battlelancer.seriesguide.shows.search.discover.DiscoverShowsActivity
import com.battlelancer.seriesguide.streaming.WatchProviderFilterDialogFragment
import com.battlelancer.seriesguide.ui.AutoGridLayoutManager
import com.battlelancer.seriesguide.util.LanguageTools
import com.battlelancer.seriesguide.util.ThemeUtils
import com.battlelancer.seriesguide.util.ViewTools
import com.battlelancer.seriesguide.util.findDialog
import com.battlelancer.seriesguide.util.safeShow
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Displays shows provided by a [BaseDiscoverShowsViewModel], expects to be hosted
 * in [DiscoverShowsActivity] which provides the filter UI.
 */
abstract class ShowsDiscoverPagingFragment : BaseAddShowsFragment() {

    abstract val model: BaseDiscoverShowsViewModel

    private lateinit var bindingActivity: ActivityDiscoverShowsBinding
    private var binding: FragmentShowsPopularBinding? = null

    private lateinit var snackbar: Snackbar
    private var yearPicker: YearPickerDialogFragment? = null
    private var languagePicker: LanguagePickerDialogFragment? = null

    private lateinit var adapter: ShowsPagingAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        bindingActivity = (requireActivity() as DiscoverShowsActivity).binding
        return FragmentShowsPopularBinding.inflate(inflater, container, false)
            .also { binding = it }
            .root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val binding = binding ?: return

        ThemeUtils.applyBottomPaddingForNavigationBar(binding.recyclerViewShowsPopular)
        ThemeUtils.applyBottomMarginForNavigationBar(binding.textViewPoweredByShowsPopular)

        binding.swipeRefreshLayoutShowsPopular.apply {
            ViewTools.setSwipeRefreshLayoutColors(requireActivity().theme, this)
            setOnRefreshListener { adapter.refresh() }
        }

        snackbar =
            Snackbar.make(binding.swipeRefreshLayoutShowsPopular, "", Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction(R.string.action_try_again) { adapter.refresh() }

        binding.recyclerViewShowsPopular.apply {
            setHasFixedSize(true)
            layoutManager =
                AutoGridLayoutManager(
                    context,
                    R.dimen.showgrid_columnWidth,
                    1,
                    1
                )
        }

        adapter = ShowsPagingAdapter(itemClickListener)
        binding.recyclerViewShowsPopular.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            model.items.collectLatest {
                adapter.submitData(it)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.loadStateFlow
                .distinctUntilChangedBy { it.refresh }
                .collectLatest { loadStates ->
                    Timber.d("loadStates=$loadStates")
                    val refresh = loadStates.refresh
                    binding.swipeRefreshLayoutShowsPopular.isRefreshing =
                        refresh is LoadState.Loading
                    if (refresh is LoadState.Error) {
                        snackbar.setText(refresh.error.message!!)
                        if (!snackbar.isShownOrQueued) snackbar.show()
                    } else {
                        if (snackbar.isShownOrQueued) snackbar.dismiss()
                    }
                }
        }

        // Re-attach listeners to any showing dialogs
        yearPicker = findDialog<YearPickerDialogFragment>(parentFragmentManager, TAG_YEAR_PICKER)
            ?.also { it.onPickedListener = onYearPickedListener }
        languagePicker =
            findDialog<LanguagePickerDialogFragment>(parentFragmentManager, TAG_LANGUAGE_PICKER)
                ?.also { it.onPickedListener = onLanguagePickedListener }

        bindingActivity.chipTraktShowsFirstReleaseYear.setOnClickListener {
            YearPickerDialogFragment.create(model.firstReleaseYear.value)
                .also { yearPicker = it }
                .apply { onPickedListener = onYearPickedListener }
                .safeShow(parentFragmentManager, TAG_YEAR_PICKER)
        }
        bindingActivity.chipTraktShowsOriginalLanguage.setOnClickListener {
            LanguagePickerDialogFragment.create(model.originalLanguage.value)
                .also { languagePicker = it }
                .apply { onPickedListener = onLanguagePickedListener }
                .safeShow(parentFragmentManager, TAG_LANGUAGE_PICKER)
        }
        bindingActivity.chipTraktShowsWatchProviders.setOnClickListener {
            WatchProviderFilterDialogFragment.showForShows(parentFragmentManager)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            model.filters.collectLatest {
                bindingActivity.chipTraktShowsFirstReleaseYear.apply {
                    val hasYear = it.firstReleaseYear != null
                    isChipIconVisible = hasYear
                    text = if (hasYear) {
                        it.firstReleaseYear.toString()
                    } else {
                        getString(R.string.filter_year)
                    }
                }
                bindingActivity.chipTraktShowsOriginalLanguage.apply {
                    val hasLanguage = it.originalLanguage != null
                    isChipIconVisible = hasLanguage
                    text = if (hasLanguage) {
                        LanguageTools.buildLanguageDisplayName(it.originalLanguage!!)
                    } else {
                        getString(R.string.filter_language)
                    }
                }
                bindingActivity.chipTraktShowsWatchProviders.apply {
                    isChipIconVisible = it.watchProviderIds?.isNotEmpty() ?: false
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
        yearPicker?.onPickedListener = null
        languagePicker?.onPickedListener = null
    }

    override fun setAllPendingNotAdded() {
        adapter.setAllPendingNotAdded()
    }

    override fun setStateForTmdbId(showTmdbId: Int, newState: Int) {
        adapter.setStateForTmdbId(showTmdbId, newState)
    }

    private val onYearPickedListener = object : YearPickerDialogFragment.OnPickedListener {
        override fun onPicked(year: Int?) {
            model.firstReleaseYear.value = year
        }
    }

    private val onLanguagePickedListener = object : LanguagePickerDialogFragment.OnPickedListener {
        override fun onPicked(languageCode: String?) {
            model.originalLanguage.value = languageCode
        }
    }

    companion object {
        val liftOnScrollTargetViewId = R.id.recyclerViewShowsPopular

        private const val TAG_YEAR_PICKER = "yearPicker"
        private const val TAG_LANGUAGE_PICKER = "languagePicker"
    }

}