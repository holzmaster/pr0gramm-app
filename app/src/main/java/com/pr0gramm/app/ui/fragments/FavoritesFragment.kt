package com.pr0gramm.app.ui.fragments

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.liveData
import com.pr0gramm.app.R
import com.pr0gramm.app.databinding.FragmentFavoritesBinding
import com.pr0gramm.app.feed.FeedFilter
import com.pr0gramm.app.feed.FeedType
import com.pr0gramm.app.services.CollectionsService
import com.pr0gramm.app.services.PostCollection
import com.pr0gramm.app.services.UserService
import com.pr0gramm.app.ui.FilterFragment
import com.pr0gramm.app.ui.ScrollHideToolbarListener
import com.pr0gramm.app.ui.TabsStateAdapter
import com.pr0gramm.app.ui.base.BaseFragment
import com.pr0gramm.app.ui.base.bindViews
import com.pr0gramm.app.ui.fragments.feed.FeedFragment
import com.pr0gramm.app.util.*
import com.pr0gramm.app.util.di.instance

/**
 */
class FavoritesFragment : BaseFragment("FavoritesFragment", R.layout.fragment_favorites), FilterFragment {
    private val views by bindViews(FragmentFavoritesBinding::bind)

    private val userService: UserService by instance()
    private val collectionsService: CollectionsService by instance()

    private val argUsername: String by fragmentArgument(name = ARG_USERNAME)

    override var currentFilter = FeedFilter().withFeedType(FeedType.NEW)
        private set

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fixViewTopOffset(view)
        resetToolbar()

        // check what to load
        val ownUsername = userService.loginState.name
        val ownView = ownUsername.equalsIgnoreCase(argUsername)

        val collectionsLiveData = when {
            ownView -> collectionsService.collections

            else -> liveData {
                val info = userService.info(argUsername)
                emit(PostCollection.fromApi(info.collections))
            }
        }

        currentFilter = currentFilter.withCollection(argUsername, "**ANY", "**ANY")

        collectionsLiveData.observe(viewLifecycleOwner) { collections ->
            views.favoritesPager.adapter = TabsStateAdapter(requireContext(), this).apply {
                for (collection in collections) {
                    val filter = FeedFilter()
                            .withFeedType(FeedType.NEW)
                            .withCollection(argUsername, collection.key, collection.title)

                    addTab(collection.title,
                            FeedFragment.newEmbedArguments(filter),
                            fragmentConstructor = ::FeedFragment)

                }

                if (ownView) {
                    addTab(getString(R.string.action_kfav),
                            fragmentConstructor = ::FavedCommentFragment)
                }
            }
        }

        views.favoritesPager.offscreenPageLimit = 2

        views.tabs.setupWithViewPager(views.favoritesPager)

        if (ownView) {
            // trigger a reload of the users collections
            doInBackground { collectionsService.refresh() }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return true
    }

    private fun resetToolbar() {
        val activity = activity
        if (activity is ScrollHideToolbarListener.ToolbarActivity) {
            val listener = activity.scrollHideToolbarListener
            listener.reset()
        }
    }

    private fun fixViewTopOffset(view: View) {
        val params = view.layoutParams
        if (params is ViewGroup.MarginLayoutParams) {
            params.topMargin = AndroidUtility.getActionBarContentOffset(view.context)
            view.layoutParams = params
        }
    }

    companion object {
        const val ARG_USERNAME = "FavoritesFragment.username"

        fun newInstance(username: String): FavoritesFragment {
            return FavoritesFragment().arguments {
                putString(ARG_USERNAME, username)
            }
        }
    }
}
