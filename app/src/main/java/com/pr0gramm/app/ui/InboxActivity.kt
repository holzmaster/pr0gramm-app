package com.pr0gramm.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import androidx.viewpager.widget.ViewPager
import com.google.android.material.tabs.TabLayout
import com.pr0gramm.app.Instant
import com.pr0gramm.app.R
import com.pr0gramm.app.services.*
import com.pr0gramm.app.ui.base.BaseAppCompatActivity
import com.pr0gramm.app.ui.fragments.CommentsInboxFragment
import com.pr0gramm.app.ui.fragments.ConversationsFragment
import com.pr0gramm.app.ui.fragments.WrittenCommentFragment
import com.pr0gramm.app.util.find
import kotterknife.bindView
import org.kodein.di.erased.instance


/**
 * The activity that displays the inbox.
 */
class InboxActivity : BaseAppCompatActivity("InboxActivity"), ViewPager.OnPageChangeListener {
    private val userService: UserService by instance()
    private val inboxService: InboxService by instance()
    private val notificationService: NotificationService by instance()

    private val tabLayout: TabLayout by bindView(R.id.tabs)
    private val viewPager: ViewPager by bindView(R.id.pager)

    private lateinit var tabsAdapter: TabsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeHelper.theme.noActionBar)
        super.onCreate(savedInstanceState)

        if (!userService.isAuthorized) {
            MainActivity.open(this)
            finish()
            return
        }

        setContentView(R.layout.activity_inbox)

        setSupportActionBar(find(R.id.toolbar))

        supportActionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setDisplayHomeAsUpEnabled(true)
        }

        tabsAdapter = TabsAdapter(this, this.supportFragmentManager)

        tabsAdapter.addTab(R.string.inbox_type_private, ConversationsFragment::class.java)

        tabsAdapter.addTab(R.string.inbox_type_comments, CommentsInboxFragment::class.java)

        tabsAdapter.addTab(R.string.inbox_type_comments, WrittenCommentFragment::class.java)

        viewPager.adapter = tabsAdapter

        viewPager.addOnPageChangeListener(this)

        tabLayout.setupWithViewPager(viewPager)

        // restore previously selected tab
        if (savedInstanceState != null) {
            viewPager.currentItem = savedInstanceState.getInt("tab")
        } else {
            handleNewIntent(intent)
        }

        // track if we've clicked the notification!
        if (intent.getBooleanExtra(EXTRA_FROM_NOTIFICATION, false)) {
            Track.inboxNotificationClosed("clicked")
        }

        intent.getParcelableExtra<Instant>(EXTRA_MESSAGE_TIMESTAMP)?.let { timestamp ->
            inboxService.markAsRead(timestamp)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return super.onOptionsItemSelected(item) || when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            else -> false
        }
    }

    override fun onResume() {
        super.onResume()

        // remove pending notifications.
        notificationService.cancelForInbox()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNewIntent(intent)
    }

    private fun handleNewIntent(intent: Intent?) {
        val extras = intent?.extras ?: return
        showInboxType(InboxType.values()[extras.getInt(EXTRA_INBOX_TYPE, 0)])
    }

    private fun showInboxType(type: InboxType?) {
        if (type != null && type.ordinal < tabsAdapter.count) {
            viewPager.currentItem = type.ordinal
        }
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        onTabChanged()
    }

    private fun onTabChanged() {
        val index = viewPager.currentItem
        if (index >= 0 && index < tabsAdapter.count) {
            title = tabsAdapter.getPageTitle(index)
            trackScreen(index)
        }
    }

    /**
     * Might not be the most beautiful code, but works for now.
     */
    private fun trackScreen(index: Int) {
        when (index) {
            // TODO adjust
            0 -> Track.screen(this, "InboxPrivate")
            1 -> Track.screen(this, "InboxComments")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", viewPager.currentItem)
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {

    }

    override fun onPageSelected(position: Int) {
        onTabChanged()
    }

    override fun onPageScrollStateChanged(state: Int) {

    }

    companion object {
        const val EXTRA_INBOX_TYPE = "InboxActivity.inboxType"
        const val EXTRA_FROM_NOTIFICATION = "InboxActivity.fromNotification"
        const val EXTRA_MESSAGE_TIMESTAMP = "InboxActivity.maxMessageId"
    }
}
